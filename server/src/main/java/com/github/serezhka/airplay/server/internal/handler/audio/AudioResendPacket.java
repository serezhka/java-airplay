package com.github.serezhka.airplay.server.internal.handler.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Classic AirPlay audio retransmission request (8 bytes on the control UDP port).
 * Wire: {@code 80 D5 | reqSeq | firstMissing | count}.
 */
public final class AudioResendPacket {

    private AudioResendPacket() {
    }

    public static byte[] encode(int firstMissingSeq, int count) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x80);
        buf.put((byte) 0xD5);
        buf.putShort((short) 1);
        buf.putShort((short) (firstMissingSeq & 0xFFFF));
        buf.putShort((short) (count & 0xFFFF));
        return buf.array();
    }
}
