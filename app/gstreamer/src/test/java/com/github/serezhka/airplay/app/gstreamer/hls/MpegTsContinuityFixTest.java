package com.github.serezhka.airplay.app.gstreamer.hls;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpegTsContinuityFixTest {

    @Test
    void firstPacketLearnsCcWithoutRewrite() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        ByteBuffer first = payload(0x100, 7);
        ByteBuffer second = payload(0x100, 8);
        assertFalse(fix.rewritePacket(first, 0));
        assertFalse(fix.rewritePacket(second, 0));
        assertEquals(8, second.get(3) & 0x0F);
    }

    @Test
    void segmentBoundaryResetIsRewritten() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        assertFalse(fix.rewritePacket(payload(0x101, 7), 0));
        ByteBuffer reset = payload(0x101, 0);
        assertTrue(fix.rewritePacket(reset, 0));
        assertEquals(8, reset.get(3) & 0x0F);
    }

    @Test
    void emptyAdaptStuffingDoesNotAdvanceAndMirrorsLast() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        assertFalse(fix.rewritePacket(payload(0x102, 3), 0));
        ByteBuffer stuffing = emptyAdaptStuffing(0x102, 9);
        assertTrue(MpegTsContinuityFix.hasRealPayload(payload(0x102, 0), 0));
        assertFalse(MpegTsContinuityFix.hasRealPayload(stuffing, 0));
        assertTrue(fix.rewritePacket(stuffing, 0));
        assertEquals(3, stuffing.get(3) & 0x0F);
        ByteBuffer next = payload(0x102, 0);
        assertTrue(fix.rewritePacket(next, 0));
        assertEquals(4, next.get(3) & 0x0F);
    }

    @Test
    void adaptationOnlyKeepsLastCc() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        assertFalse(fix.rewritePacket(payload(0x103, 3), 0));
        ByteBuffer adaptOnly = packet(0x103, 0x2, 9, 1);
        assertTrue(fix.rewritePacket(adaptOnly, 0));
        assertEquals(3, adaptOnly.get(3) & 0x0F);
    }

    @Test
    void rewriteScansWholeBuffer() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        ByteBuffer buf = ByteBuffer.allocate(188 * 2);
        buf.put(payload(0x110, 1));
        buf.put(payload(0x110, 0));
        buf.flip();
        assertEquals(1, fix.rewrite(buf));
        assertEquals(2, buf.get(188 + 3) & 0x0F);
    }

    @Test
    void resetClearsLearnedState() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        assertFalse(fix.rewritePacket(payload(0x120, 14), 0));
        fix.reset();
        ByteBuffer again = payload(0x120, 0);
        assertFalse(fix.rewritePacket(again, 0));
        assertEquals(0, again.get(3) & 0x0F);
    }

    @Test
    void nullPidIsIgnored() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        assertFalse(fix.rewritePacket(payload(0x1FFF, 1), 0));
        assertFalse(fix.rewritePacket(payload(0x1FFF, 5), 0));
    }

    /**
     * Lab shape of a YouTube HLS cut: last real payload CC=3, then empty {@code afc=3}
     * stuffing with advancing CC 4..15 (tsdemux ignores those), then next fragment starts
     * at CC=0. Naive “increment every afc&1” rewrite leaves a demuxer-visible gap 3→0;
     * payload-aware rewrite keeps demuxer CC continuous.
     */
    @Test
    void youtubeStyleStuffingThenNextFragmentIsContinuousForDemuxer() {
        ByteBuffer stream = ByteBuffer.allocate(188 * 20);
        // Real payloads 0..3
        for (int cc = 0; cc <= 3; cc++) {
            stream.put(payload(0x100, cc));
        }
        // Empty stuffing as shipped by YouTube (afc=3, adapt_len=183), CC keeps climbing
        for (int cc = 4; cc <= 15; cc++) {
            stream.put(emptyAdaptStuffing(0x100, cc));
        }
        // Next fragment resets to 0
        stream.put(payload(0x100, 0));
        stream.put(payload(0x100, 1));
        stream.flip();

        assertFalse(demuxerPayloadCcContinuous(stream), "raw YouTube-shaped cut must gap for tsdemux");

        MpegTsContinuityFix rewriter = new MpegTsContinuityFix();
        assertTrue(rewriter.rewrite(stream) > 0);
        assertTrue(demuxerPayloadCcContinuous(stream), "after rewrite demuxer-visible CC must be continuous");

        // Stuffing mirrored last real CC (3); first packet of next fragment became 4
        assertEquals(3, stream.get((3 + 1) * 188 + 3) & 0x0F); // first stuffing
        assertEquals(3, stream.get((3 + 12) * 188 + 3) & 0x0F); // last stuffing (was 15)
        assertEquals(4, stream.get((3 + 13) * 188 + 3) & 0x0F); // next-fragment first payload
        assertEquals(5, stream.get((3 + 14) * 188 + 3) & 0x0F);
    }

    @Test
    void naiveAfcRewriteWouldNotFixDemuxerGap() {
        // Document why stuffing must not advance CC: if we advanced on empty afc=3,
        // next-fragment CC=0 would look continuous in the file but tsdemux still sees 3→0.
        List<Integer> demuxerView = new ArrayList<>();
        int lastPayloadCc = 3;
        demuxerView.add(lastPayloadCc);
        // stuffing 4..15 ignored by demuxer
        int nextFragmentCc = 0;
        demuxerView.add(nextFragmentCc);
        assertEquals(2, demuxerView.size());
        assertFalse(isContinuous(demuxerView));
    }

    @Test
    void hasRealPayloadRejectsFullAdaptationStuffing() {
        assertTrue(MpegTsContinuityFix.hasRealPayload(payload(0x100, 0), 0));
        assertTrue(MpegTsContinuityFix.hasRealPayload(packet(0x100, 0x3, 0, 10), 0));
        assertFalse(MpegTsContinuityFix.hasRealPayload(emptyAdaptStuffing(0x100, 0), 0));
        assertFalse(MpegTsContinuityFix.hasRealPayload(packet(0x100, 0x2, 0, 1), 0));
    }

    @Test
    void rewriteSkipsJunkBeforeSyncAndFixesAlignedPackets() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        byte[] junk = new byte[188 * 2 + 7];
        // 7 garbage bytes then two packets (CC 1 then reset 0 → expect 2)
        System.arraycopy(payload(0x130, 1).array(), 0, junk, 7, 188);
        System.arraycopy(payload(0x130, 0).array(), 0, junk, 7 + 188, 188);
        assertEquals(1, fix.rewrite(junk, 0, junk.length));
        assertEquals(2, junk[7 + 188 + 3] & 0x0F);
    }

    @Test
    void byteArrayHasRealPayloadMatchesByteBuffer() {
        ByteBuffer stuffing = emptyAdaptStuffing(0x140, 5);
        assertFalse(MpegTsContinuityFix.hasRealPayload(stuffing.array(), 0));
        assertTrue(MpegTsContinuityFix.hasRealPayload(payload(0x140, 1).array(), 0));
    }

    @Test
    void discontinuityIndicatorRelearnsWithoutRewrite() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        assertFalse(fix.rewritePacket(payload(0x170, 5), 0));
        ByteBuffer disc = packet(0x170, 0x3, 0, 10);
        disc.put(5, (byte) 0x80); // discontinuity_indicator
        assertTrue(MpegTsContinuityFix.hasDiscontinuityIndicator(disc.array(), 0));
        assertFalse(fix.rewritePacket(disc, 0));
        assertEquals(0, disc.get(3) & 0x0F);
        ByteBuffer next = payload(0x170, 1);
        assertFalse(fix.rewritePacket(next, 0));
        assertEquals(1, next.get(3) & 0x0F);
    }

    @Test
    void findSyncRequiresAlignedFollowOnPackets() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        byte[] junk = new byte[188 * 3];
        junk[10] = 0x47; // false sync — next +188 is not 0x47
        System.arraycopy(payload(0x171, 1).array(), 0, junk, 40, 188);
        System.arraycopy(payload(0x171, 2).array(), 0, junk, 40 + 188, 188);
        // rewrite() should latch onto the real aligned pair at 40, not byte 10
        assertEquals(0, fix.rewrite(junk, 0, junk.length));
        assertEquals(1, junk[40 + 3] & 0x0F);
        assertEquals(2, junk[40 + 188 + 3] & 0x0F);
    }

    @Test
    void continuousStreamNeedsNoRewriteAfterLearn() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        byte[] stream = new byte[188 * 64];
        for (int i = 0; i < 64; i++) {
            System.arraycopy(payload(0x150, i & 0x0F).array(), 0, stream, i * 188, 188);
        }
        assertEquals(0, fix.rewrite(stream, 0, stream.length));
        // Second pass still no-op (state already tracked)
        assertEquals(0, fix.rewrite(stream, 0, stream.length));
    }

    @Test
    void youtubeCutRewriteCompletesUnderBudget() {
        byte[] stream = new byte[188 * 4096];
        int off = 0;
        for (int seg = 0; seg < 16; seg++) {
            for (int cc = 0; cc < 16; cc++) {
                System.arraycopy(payload(0x100, cc).array(), 0, stream, off, 188);
                off += 188;
            }
            for (int cc = 0; cc < 12; cc++) {
                System.arraycopy(emptyAdaptStuffing(0x100, (cc + 4) & 0x0F).array(), 0, stream, off, 188);
                off += 188;
            }
            // next fragment resets
            System.arraycopy(payload(0x100, 0).array(), 0, stream, off, 188);
            off += 188;
        }
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        long t0 = System.nanoTime();
        int fixed = fix.rewrite(stream, 0, off);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(fixed > 0);
        assertTrue(ms < 250, "rewrite of ~" + (off / 188) + " packets took " + ms + "ms");
    }

    @Test
    void carryJoinsSplitPacketAcrossBuffers() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        byte[] a = payload(0x160, 3).array();
        byte[] b = payload(0x160, 0).array(); // reset — should become 4 after learn 3
        byte[] first = Arrays.copyOf(a, 100);
        byte[] second = new byte[a.length - 100 + b.length];
        System.arraycopy(a, 100, second, 0, a.length - 100);
        System.arraycopy(b, 0, second, a.length - 100, b.length);

        byte[] out1 = fix.processForTest(first);
        assertEquals(0, out1.length);
        assertTrue(fix.pendingLen() > 0);

        byte[] out2 = fix.processForTest(second);
        assertEquals(188 * 2, out2.length);
        assertEquals(0, fix.pendingLen());
        assertEquals(3, out2[3] & 0x0F);
        assertEquals(4, out2[188 + 3] & 0x0F);
    }

    @Test
    void carryPreservesContinuousCcWhenSplitOddly() {
        MpegTsContinuityFix fix = new MpegTsContinuityFix();
        byte[] stream = new byte[188 * 5];
        for (int i = 0; i < 5; i++) {
            System.arraycopy(payload(0x161, i).array(), 0, stream, i * 188, 188);
        }
        int[] cuts = {50, 200, 377, stream.length};
        int from = 0;
        byte[] assembled = new byte[0];
        for (int cut : cuts) {
            byte[] piece = Arrays.copyOfRange(stream, from, cut);
            byte[] out = fix.processForTest(piece);
            assembled = concat(assembled, out);
            from = cut;
        }
        assertEquals(stream.length, assembled.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(i, assembled[i * 188 + 3] & 0x0F);
        }
    }

    /** CC sequence of packets that {@code tsdemux} would actually handle. */
    private static boolean demuxerPayloadCcContinuous(ByteBuffer stream) {
        List<Integer> ccs = new ArrayList<>();
        ByteBuffer view = stream.duplicate();
        view.clear();
        for (int off = 0; off + 188 <= view.limit(); off += 188) {
            if ((view.get(off) & 0xFF) != 0x47) {
                continue;
            }
            if (MpegTsContinuityFix.hasRealPayload(view, off)) {
                ccs.add(view.get(off + 3) & 0x0F);
            }
        }
        return isContinuous(ccs);
    }

    private static boolean isContinuous(List<Integer> ccs) {
        for (int i = 1; i < ccs.size(); i++) {
            if (ccs.get(i) != ((ccs.get(i - 1) + 1) & 0x0F)) {
                return false;
            }
        }
        return !ccs.isEmpty();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static ByteBuffer payload(int pid, int cc) {
        return packet(pid, 0x1, cc, -1);
    }

    private static ByteBuffer emptyAdaptStuffing(int pid, int cc) {
        return packet(pid, 0x3, cc, 183);
    }

    private static ByteBuffer packet(int pid, int afc, int cc, int adaptLen) {
        ByteBuffer buf = ByteBuffer.allocate(188);
        buf.put(0, (byte) 0x47);
        buf.put(1, (byte) ((pid >> 8) & 0x1F));
        buf.put(2, (byte) (pid & 0xFF));
        buf.put(3, (byte) (((afc & 0x3) << 4) | (cc & 0x0F)));
        if (adaptLen >= 0 && (afc == 2 || afc == 3)) {
            buf.put(4, (byte) adaptLen);
        }
        return buf;
    }
}
