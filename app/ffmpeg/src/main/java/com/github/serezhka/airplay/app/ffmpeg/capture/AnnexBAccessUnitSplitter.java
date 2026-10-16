package com.github.serezhka.airplay.app.ffmpeg.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Accumulates Annex-B byte-stream and emits one access unit at a time.
 * An AU ends when the next NAL is AUD (type 9) or SPS (type 7) after at least one VCL NAL,
 * or when an AUD starts a new unit after prior content.
 */
final class AnnexBAccessUnitSplitter {

    private final Consumer<byte[]> onAu;
    private final byte[] buf = new byte[1 << 20];
    private int len;

    AnnexBAccessUnitSplitter(Consumer<byte[]> onAu) {
        this.onAu = onAu;
    }

    void accept(byte[] chunk, int offset, int length) {
        ensureCapacity(len + length);
        System.arraycopy(chunk, offset, buf, len, length);
        len += length;
        drain();
    }

    void flush() {
        if (len > 0) {
            byte[] au = new byte[len];
            System.arraycopy(buf, 0, au, 0, len);
            len = 0;
            onAu.accept(au);
        }
    }

    private void drain() {
        List<Integer> starts = findStartCodes(buf, len);
        if (starts.size() < 2) {
            return;
        }
        int emitUntil = -1;
        boolean sawVcl = false;
        for (int i = 0; i < starts.size(); i++) {
            int nalStart = starts.get(i) + startCodeLen(buf, starts.get(i));
            if (nalStart >= len) {
                break;
            }
            int nalType = buf[nalStart] & 0x1F;
            if (i > 0 && sawVcl && (nalType == 9 || nalType == 7)) {
                emitUntil = starts.get(i);
                break;
            }
            if (nalType == 1 || nalType == 5) {
                sawVcl = true;
            }
        }
        if (emitUntil < 0) {
            return;
        }
        byte[] au = new byte[emitUntil];
        System.arraycopy(buf, 0, au, 0, emitUntil);
        System.arraycopy(buf, emitUntil, buf, 0, len - emitUntil);
        len -= emitUntil;
        onAu.accept(au);
        drain();
    }

    private void ensureCapacity(int need) {
        if (need <= buf.length) {
            return;
        }
        throw new IllegalStateException("Annex-B buffer overflow (" + need + " bytes)");
    }

    private static List<Integer> findStartCodes(byte[] data, int length) {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        while (i + 3 < length) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    out.add(i);
                    i += 3;
                    continue;
                }
                if (i + 3 < length && data[i + 2] == 0 && data[i + 3] == 1) {
                    out.add(i);
                    i += 4;
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    private static int startCodeLen(byte[] data, int at) {
        if (at + 3 < data.length && data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 0 && data[at + 3] == 1) {
            return 4;
        }
        return 3;
    }
}
