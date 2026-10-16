package com.github.serezhka.airplay.protocol.mirror;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Builders for the 128-byte AirPlay mirror TCP header.
 * Layout constants live in {@link MirrorPacketLayout}.
 */
public final class MirrorPacketHeaders {

    /** Type-1 option field observed on sender codec packets. */
    public static final short CODEC_OPTION = 286;

    private MirrorPacketHeaders() {
    }

    /**
     * HKP / DataStream codec (type-1) header: marks a running stream and carries
     * float32 width/height at several offsets some receivers require.
     */
    public static byte[] codec(int nbytes, long ts, int width, int height) {
        byte[] header = new byte[MirrorPacketLayout.HEADER_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, nbytes);
        header[4] = (byte) MirrorPacketLayout.PAYLOAD_CODEC;
        header[6] = 0x16;
        header[7] = 0x01;
        buf.putLong(8, ts);
        buf.putFloat(16, width);
        buf.putFloat(20, height);
        buf.putFloat(40, width);
        buf.putFloat(44, height);
        buf.putFloat(56, width);
        buf.putFloat(60, height);
        return header;
    }

    /**
     * HKP / DataStream video (type-0) header. {@code nbytes} includes the Poly1305 tag
     * when the payload is sealed.
     */
    public static byte[] video(int nbytes, long ts, boolean keyframe) {
        byte[] header = new byte[MirrorPacketLayout.HEADER_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0, nbytes);
        header[4] = (byte) MirrorPacketLayout.PAYLOAD_VIDEO;
        header[5] = (byte) (keyframe ? 0x10 : 0x00);
        buf.putLong(8, ts);
        return header;
    }

    /**
     * Legacy FairPlay-style header (type-1 sets option {@link #CODEC_OPTION}
     * and float32 width/height at offset 16; type-0 keeps those zero).
     */
    public static byte[] legacy(int payloadLen, int payloadType, long pts) {
        return legacy(payloadLen, payloadType, pts, 0, 0);
    }

    public static byte[] legacy(int payloadLen, int payloadType, long pts, int width, int height) {
        ByteBuffer h = ByteBuffer.allocate(MirrorPacketLayout.HEADER_BYTES);
        h.order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(payloadLen);
        h.putShort((short) payloadType);
        h.putShort(payloadType == MirrorPacketLayout.PAYLOAD_CODEC ? CODEC_OPTION : (short) 0);
        h.putLong(pts);
        if (payloadType == MirrorPacketLayout.PAYLOAD_CODEC && width > 0 && height > 0) {
            h.putFloat(width);
            h.putFloat(height);
        }
        return h.array();
    }
}
