package com.github.serezhka.airplay.client.video;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Helpers for AirPlay mirror video framing (Annex-B ↔ AVCC, type-1 codec config). */
public final class MirrorVideoFraming {

    private MirrorVideoFraming() {
    }

    public record Nal(byte[] data) {
        public int type() {
            return data.length == 0 ? -1 : data[0] & 0x1f;
        }
    }

    public static List<Nal> splitAnnexB(byte[] annexB) {
        List<Nal> out = new ArrayList<>();
        int i = 0;
        while (i + 3 <= annexB.length) {
            int start;
            if (i + 4 <= annexB.length
                    && annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1) {
                start = i + 4;
            } else if (annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1) {
                start = i + 3;
            } else {
                i++;
                continue;
            }
            int j = start;
            while (j + 3 <= annexB.length) {
                if (j + 4 <= annexB.length
                        && annexB[j] == 0 && annexB[j + 1] == 0 && annexB[j + 2] == 0 && annexB[j + 3] == 1) {
                    break;
                }
                if (annexB[j] == 0 && annexB[j + 1] == 0 && annexB[j + 2] == 1) {
                    break;
                }
                j++;
            }
            byte[] nal = new byte[j - start];
            System.arraycopy(annexB, start, nal, 0, nal.length);
            if (nal.length > 0) {
                out.add(new Nal(nal));
            }
            i = j;
        }
        return out;
    }

    /** Length-prefixed AVCC payload for type-0 video packets. */
    public static byte[] toAvcc(List<Nal> nals) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (Nal nal : nals) {
            bos.write((nal.data.length >>> 24) & 0xff);
            bos.write((nal.data.length >>> 16) & 0xff);
            bos.write((nal.data.length >>> 8) & 0xff);
            bos.write(nal.data.length & 0xff);
            bos.writeBytes(nal.data);
        }
        return bos.toByteArray();
    }

    /**
     * Type-1 codec-data payload (AVCDecoderConfigurationRecord-ish) consumed by receivers
     * that skip the first 6 bytes then read spsLen/ppsLen.
     */
    public static byte[] buildType1CodecData(byte[] sps, byte[] pps) {
        ByteBuffer buf = ByteBuffer.allocate(6 + 2 + sps.length + 1 + 2 + pps.length);
        buf.put((byte) 1);
        if (sps.length >= 4) {
            buf.put(sps[1]);
            buf.put(sps[2]);
            buf.put(sps[3]);
        } else {
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.put((byte) 0);
        }
        buf.put((byte) 0xff);
        buf.put((byte) 0xe1);
        buf.putShort((short) sps.length);
        buf.put(sps);
        buf.put((byte) 1);
        buf.putShort((short) pps.length);
        buf.put(pps);
        return buf.array();
    }

    /** 128-byte AirPlay mirror packet header. */
    public static byte[] header(int payloadLen, int payloadType, long pts) {
        ByteBuffer h = ByteBuffer.allocate(128);
        h.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.putInt(payloadLen);
        h.putShort((short) payloadType);
        h.putShort((short) 0);
        h.putLong(pts);
        return h.array();
    }
}
