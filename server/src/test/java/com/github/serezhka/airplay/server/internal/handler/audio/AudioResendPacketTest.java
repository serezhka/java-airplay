package com.github.serezhka.airplay.server.internal.handler.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioResendPacketTest {

    @Test
    void encodesClassicEightByteRequest() {
        byte[] wire = AudioResendPacket.encode(0x1234, 3);
        assertEquals(8, wire.length);
        assertArrayEquals(new byte[]{
                (byte) 0x80, (byte) 0xD5,
                0x00, 0x01,
                0x12, 0x34,
                0x00, 0x03
        }, wire);
    }

    @Test
    void encodesUint16WrapSeq() {
        byte[] wire = AudioResendPacket.encode(65535, 1);
        assertEquals((byte) 0xFF, wire[4]);
        assertEquals((byte) 0xFF, wire[5]);
        assertEquals(0x00, wire[6]);
        assertEquals(0x01, wire[7]);
    }
}
