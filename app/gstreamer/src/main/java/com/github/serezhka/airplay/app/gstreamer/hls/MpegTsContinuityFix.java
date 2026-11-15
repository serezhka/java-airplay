package com.github.serezhka.airplay.app.gstreamer.hls;

import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.PadProbeInfo;
import org.freedesktop.gstreamer.PadProbeReturn;
import org.freedesktop.gstreamer.PadProbeType;
import org.freedesktop.gstreamer.glib.Natives;
import org.freedesktop.gstreamer.lowlevel.GstNative;
import org.freedesktop.gstreamer.lowlevel.GstPadProbeInfo;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * YouTube-style HLS MPEG-TS → safe for {@code tsdemux} (e.g. GStreamer 1.28).
 * <p>
 * Fragments often reset CC at cuts and end with empty {@code afc=3} stuffing
 * ({@code adaptation_field_length == 183}) that does not advance demuxer CC. The next
 * fragment looks like loss → mid-GOP drop → smear. This probe rewrites CC so only
 * real-payload packets advance; stuffing mirrors the last payload CC. Discontinuity
 * indicators re-learn without forcing. Null packets are skipped.
 * <p>
 * Live path carries incomplete 188-byte tails across buffers (same as the test helper):
 * HLS HTTP often splits mid-packet; dropping the tail desyncs PES and yields periodic
 * {@code error while decoding MB} / smear even with software decode. Changed or
 * reframed buffers are swapped in with {@code gst_pad_probe_info_set_buffer}.
 */
@Slf4j
final class MpegTsContinuityFix {

    private static final int TS_PACKET = 188;
    private static final int PID_COUNT = 0x2000;
    private static final int NULL_PID = 0x1FFF;
    private static final int CC_UNSET = -1;
    private static final int ADAPT_LEN_NO_PAYLOAD = 183;
    private static final int SCRATCH_MIN = 256 * 1024;

    private static final Field PROBE_INFO_FIELD = probeInfoField();
    private static final ProbeApi PROBE_API = loadProbeApi();
    private static final boolean CAN_REPLACE = PROBE_API != null && PROBE_INFO_FIELD != null;

    private final int[] lastCc = new int[PID_COUNT];
    /** Snapshot when a live replace may fail and state must roll back. */
    private final int[] lastCcSnapshot = new int[PID_COUNT];
    private final byte[] pending = new byte[TS_PACKET - 1];
    private final byte[] pendingSnapshot = new byte[TS_PACKET - 1];
    private int pendingLen;
    private final AtomicInteger rewrittenPackets = new AtomicInteger();
    private final AtomicInteger rewrittenBuffers = new AtomicInteger();
    private final ThreadLocal<byte[]> scratch = ThreadLocal.withInitial(() -> new byte[SCRATCH_MIN]);

    private interface ProbeApi extends com.sun.jna.Library {
        void gst_pad_probe_info_set_buffer(GstPadProbeInfo info, Pointer buffer);
    }

    /** Result of framing + CC rewrite for one input slice. */
    record ProcessResult(byte[] data, int fixed, boolean usedPending) {
        static final ProcessResult EMPTY = new ProcessResult(new byte[0], 0, false);
    }

    MpegTsContinuityFix() {
        Arrays.fill(lastCc, CC_UNSET);
    }

    void reset() {
        Arrays.fill(lastCc, CC_UNSET);
        pendingLen = 0;
    }

    int pendingLen() {
        return pendingLen;
    }

    /**
     * Frame incomplete tails across calls, then rewrite CC on full packets.
     * Used by unit tests and the live pad probe.
     */
    byte[] processForTest(byte[] input) {
        return processWithPending(input == null ? new byte[0] : input).data;
    }

    ProcessResult processWithPending(byte[] input) {
        if (input == null) {
            input = new byte[0];
        }
        boolean usedPending = pendingLen > 0;
        int total = pendingLen + input.length;
        byte[] chunk = scratch(Math.max(total, 1));
        if (pendingLen > 0) {
            System.arraycopy(pending, 0, chunk, 0, pendingLen);
        }
        if (input.length > 0) {
            System.arraycopy(input, 0, chunk, pendingLen, input.length);
        }
        pendingLen = 0;

        int syncAt = 0;
        if (total > 0 && (chunk[0] & 0xFF) != 0x47) {
            syncAt = findSync(chunk, 0, total);
            if (syncAt < 0) {
                stashPending(chunk, Math.max(0, total - (TS_PACKET - 1)), total);
                return ProcessResult.EMPTY;
            }
            usedPending = true;
        }
        int full = ((total - syncAt) / TS_PACKET) * TS_PACKET;
        int remFrom = syncAt + full;
        if (remFrom < total) {
            usedPending = true;
        }
        stashPending(chunk, remFrom, total);
        if (full == 0) {
            return ProcessResult.EMPTY;
        }
        int fixed = rewriteAligned(chunk, syncAt, full);
        byte[] out = new byte[full];
        System.arraycopy(chunk, syncAt, out, 0, full);
        return new ProcessResult(out, fixed, usedPending);
    }

    void install(Element tsdemux) {
        Pad sink = tsdemux.getStaticPad("sink");
        if (sink == null) {
            log.warn("HLS CC-fix: tsdemux has no sink pad");
            return;
        }
        if (!CAN_REPLACE) {
            log.warn("HLS CC-fix: gst_pad_probe_info_set_buffer unavailable");
        }
        sink.addProbe(PadProbeType.BUFFER, (pad, info) -> onBuffer(info));
        log.info("HLS CC-fix: installed on tsdemux sink (payload-aware continuity rewrite)");
    }

    private PadProbeReturn onBuffer(PadProbeInfo info) {
        Buffer buffer = info.getBuffer();
        if (buffer == null) {
            return PadProbeReturn.OK;
        }

        ByteBuffer mapped = buffer.map(false);
        if (mapped == null) {
            return PadProbeReturn.OK;
        }
        int inLen = mapped.remaining();
        byte[] input = new byte[inLen];
        try {
            mapped.get(input);
        } finally {
            buffer.unmap();
        }

        if (inLen == 0 && pendingLen == 0) {
            return PadProbeReturn.OK;
        }

        int pendingBefore = pendingLen;
        if (pendingBefore > 0) {
            System.arraycopy(pending, 0, pendingSnapshot, 0, pendingBefore);
        }
        System.arraycopy(lastCc, 0, lastCcSnapshot, 0, PID_COUNT);

        ProcessResult result = processWithPending(input);
        if (result.data.length == 0) {
            // Held incomplete tail in pending — do not forward a partial packet.
            return PadProbeReturn.DROP;
        }

        boolean mustReplace = result.fixed > 0
                || result.usedPending
                || result.data.length != inLen;
        if (!mustReplace) {
            return PadProbeReturn.OK;
        }
        if (!CAN_REPLACE || !replaceBuffer(info, buffer, result.data, 0, result.data.length)) {
            System.arraycopy(lastCcSnapshot, 0, lastCc, 0, PID_COUNT);
            pendingLen = pendingBefore;
            if (pendingBefore > 0) {
                System.arraycopy(pendingSnapshot, 0, pending, 0, pendingBefore);
            }
            return PadProbeReturn.OK;
        }
        if (result.fixed > 0) {
            noteRewrite(result.fixed);
        }
        return PadProbeReturn.OK;
    }

    private boolean replaceBuffer(PadProbeInfo info, Buffer buffer, byte[] data, int off, int len) {
        Buffer out = new Buffer(len);
        ByteBuffer outMap = out.map(true);
        if (outMap == null) {
            out.dispose();
            return false;
        }
        try {
            outMap.put(data, off, len);
        } finally {
            out.unmap();
        }
        out.setPresentationTimestamp(buffer.getPresentationTimestamp());
        out.setDecodeTimestamp(buffer.getDecodeTimestamp());
        out.setDuration(buffer.getDuration());
        if (!setProbeBuffer(info, out)) {
            out.dispose();
            return false;
        }
        out.disown();
        return true;
    }

    private void stashPending(byte[] data, int from, int to) {
        int rem = to - from;
        if (rem <= 0) {
            pendingLen = 0;
            return;
        }
        if (rem > pending.length) {
            from = to - pending.length;
            rem = pending.length;
        }
        System.arraycopy(data, from, pending, 0, rem);
        pendingLen = rem;
    }

    private byte[] scratch(int len) {
        byte[] buf = scratch.get();
        if (buf.length < len) {
            buf = new byte[Math.max(len, buf.length * 2)];
            scratch.set(buf);
        }
        return buf;
    }

    private static boolean setProbeBuffer(PadProbeInfo info, Buffer buffer) {
        if (!CAN_REPLACE) {
            return false;
        }
        try {
            GstPadProbeInfo nativeInfo = (GstPadProbeInfo) PROBE_INFO_FIELD.get(info);
            if (nativeInfo == null) {
                return false;
            }
            PROBE_API.gst_pad_probe_info_set_buffer(nativeInfo, Natives.getRawPointer(buffer));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void noteRewrite(int fixed) {
        int bufs = rewrittenBuffers.incrementAndGet();
        rewrittenPackets.addAndGet(fixed);
        if (bufs == 1 || bufs % 200 == 0) {
            log.info("HLS CC-fix: rewritten {} packet(s) across {} buffer(s)",
                    rewrittenPackets.get(), bufs);
        }
    }

    int rewrite(ByteBuffer data) {
        int pos = data.position();
        int len = data.remaining();
        if (data.hasArray() && !data.isReadOnly()) {
            return rewrite(data.array(), data.arrayOffset() + pos, len);
        }
        byte[] tmp = new byte[len];
        int mark = data.position();
        data.get(tmp);
        data.position(mark);
        int fixed = rewrite(tmp, 0, len);
        if (fixed > 0) {
            data.put(pos, tmp, 0, len);
        }
        return fixed;
    }

    int rewrite(byte[] data, int offset, int length) {
        int limit = offset + length;
        int syncAt = offset;
        if (length > 0 && (data[offset] & 0xFF) != 0x47) {
            syncAt = findSync(data, offset, limit);
            if (syncAt < 0) {
                return 0;
            }
        }
        int full = ((limit - syncAt) / TS_PACKET) * TS_PACKET;
        return rewriteAligned(data, syncAt, full);
    }

    int rewriteAligned(byte[] data, int offset, int length) {
        int fixed = 0;
        int end = offset + length;
        for (int i = offset; i + TS_PACKET <= end; i += TS_PACKET) {
            if ((data[i] & 0xFF) != 0x47) {
                break;
            }
            if (rewritePacket(data, i)) {
                fixed++;
            }
        }
        return fixed;
    }

    boolean rewritePacket(ByteBuffer data, int offset) {
        if (data.hasArray() && !data.isReadOnly()) {
            return rewritePacket(data.array(), data.arrayOffset() + offset);
        }
        byte[] one = new byte[TS_PACKET];
        data.position(offset);
        data.get(one);
        boolean changed = rewritePacket(one, 0);
        if (changed) {
            data.put(offset, one, 0, TS_PACKET);
        }
        return changed;
    }

    boolean rewritePacket(byte[] data, int offset) {
        int b3 = data[offset + 3] & 0xFF;
        int afc = (b3 >> 4) & 0x3;
        if (afc == 0) {
            return false;
        }
        int pid = ((data[offset + 1] & 0x1F) << 8) | (data[offset + 2] & 0xFF);
        if (pid >= PID_COUNT || pid == NULL_PID) {
            return false;
        }
        int packetCc = b3 & 0x0F;
        if (hasDiscontinuityIndicator(data, offset)) {
            if (hasRealPayload(data, offset)) {
                lastCc[pid] = packetCc;
            }
            return false;
        }
        int last = lastCc[pid];
        if (!hasRealPayload(data, offset)) {
            if (last != CC_UNSET && packetCc != last) {
                data[offset + 3] = (byte) ((b3 & 0xF0) | (last & 0x0F));
                return true;
            }
            return false;
        }
        if (last == CC_UNSET) {
            lastCc[pid] = packetCc;
            return false;
        }
        int expected = (last + 1) & 0x0F;
        lastCc[pid] = expected;
        if (packetCc == expected) {
            return false;
        }
        data[offset + 3] = (byte) ((b3 & 0xF0) | expected);
        return true;
    }

    static boolean hasDiscontinuityIndicator(byte[] data, int offset) {
        int afc = (data[offset + 3] & 0xFF) >> 4 & 0x3;
        if (afc != 2 && afc != 3) {
            return false;
        }
        int adaptLen = data[offset + 4] & 0xFF;
        if (adaptLen == 0) {
            return false;
        }
        return (data[offset + 5] & 0x80) != 0;
    }

    static boolean hasDiscontinuityIndicator(ByteBuffer data, int offset) {
        if (data.hasArray() && !data.isReadOnly()) {
            return hasDiscontinuityIndicator(data.array(), data.arrayOffset() + offset);
        }
        int afc = (data.get(offset + 3) & 0xFF) >> 4 & 0x3;
        if (afc != 2 && afc != 3) {
            return false;
        }
        int adaptLen = data.get(offset + 4) & 0xFF;
        if (adaptLen == 0) {
            return false;
        }
        return (data.get(offset + 5) & 0x80) != 0;
    }

    static boolean hasRealPayload(ByteBuffer data, int offset) {
        if (data.hasArray() && !data.isReadOnly()) {
            return hasRealPayload(data.array(), data.arrayOffset() + offset);
        }
        int afc = (data.get(offset + 3) & 0xFF) >> 4 & 0x3;
        if (afc == 1) {
            return true;
        }
        if (afc == 3) {
            return (data.get(offset + 4) & 0xFF) < ADAPT_LEN_NO_PAYLOAD;
        }
        return false;
    }

    static boolean hasRealPayload(byte[] data, int offset) {
        int afc = (data[offset + 3] & 0xFF) >> 4 & 0x3;
        if (afc == 1) {
            return true;
        }
        if (afc == 3) {
            return (data[offset + 4] & 0xFF) < ADAPT_LEN_NO_PAYLOAD;
        }
        return false;
    }

    private static Field probeInfoField() {
        try {
            Field field = PadProbeInfo.class.getDeclaredField("info");
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ProbeApi loadProbeApi() {
        try {
            return GstNative.load(ProbeApi.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int findSync(byte[] data, int from, int limit) {
        for (int i = from; i + TS_PACKET <= limit; i++) {
            if ((data[i] & 0xFF) != 0x47) {
                continue;
            }
            if (looksAligned(data, i, limit)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looksAligned(byte[] data, int offset, int limit) {
        int packets = 0;
        for (int i = offset; i + TS_PACKET <= limit && packets < 3; i += TS_PACKET) {
            if ((data[i] & 0xFF) != 0x47) {
                return false;
            }
            packets++;
        }
        int need = offset + TS_PACKET * 3 <= limit ? 3 : (offset + TS_PACKET * 2 <= limit ? 2 : 1);
        return packets >= need;
    }
}
