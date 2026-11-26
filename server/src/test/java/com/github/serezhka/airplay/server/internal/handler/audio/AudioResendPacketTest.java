package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void replyTypeIgnoresMarkerBit() {
        assertTrue(AudioResendPacket.isReplyType(0x56));
        assertTrue(AudioResendPacket.isReplyType(0xD6));
        assertFalse(AudioResendPacket.isReplyType(0x54));
        assertFalse(AudioResendPacket.isReplyType(0xD5));
    }

    @Test
    void parseReplyExtractsEmbeddedRtp() {
        byte[] wire = new byte[4 + 12 + 2];
        wire[0] = (byte) 0x80;
        wire[1] = (byte) 0xD6;
        // embedded RTP
        wire[4] = (byte) 0x80;
        wire[5] = 0x60;
        wire[6] = 0x12;
        wire[7] = 0x34;
        wire[16] = 0x0A;
        wire[17] = 0x0B;

        AudioPacket packet = AudioResendPacket.parseReply(Unpooled.wrappedBuffer(wire));
        assertNotNull(packet);
        assertEquals(0x1234, packet.getSequenceNumber());
        assertEquals(2, packet.getEncodedAudioSize());
        assertEquals(0x0A, packet.getEncodedAudio()[0] & 0xFF);
        assertEquals(0x0B, packet.getEncodedAudio()[1] & 0xFF);
    }

    @Test
    void parseReplyRejectsSyncPacket() {
        byte[] sync = {(byte) 0x80, 0x54, 0, 0, 1, 2, 3, 4};
        assertNull(AudioResendPacket.parseReply(Unpooled.wrappedBuffer(sync)));
    }
}
