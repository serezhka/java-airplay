package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioDecoderTest {

    private final AudioDecoder decoder = new AudioDecoder();

    @Test
    void testDecodeAudioPacketType96Success() throws Exception {
        Path resource = Paths.get(VideoDecoderTest.class.getResource("/audio_packet_type_96").toURI());
        byte[] bytes = Files.readAllBytes(resource);

        List<Object> result = new ArrayList<>();
        decoder.decode(null, Unpooled.wrappedBuffer(bytes), result);

        assertEquals(1, result.size());
        AudioPacket packet = (AudioPacket) result.get(0);
        assertEquals(96, packet.getType());
        assertEquals(128, packet.getFlag());
        assertEquals(54528, packet.getSsrc());
        assertEquals(54905, packet.getSequenceNumber());
        assertEquals(-1937058542, packet.getTimestamp()); // FIXME
        assertEquals(1920, packet.getEncodedAudioSize());
    }
}