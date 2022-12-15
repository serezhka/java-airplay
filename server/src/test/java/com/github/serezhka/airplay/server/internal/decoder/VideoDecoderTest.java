package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.server.internal.packet.VideoPacket;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoDecoderTest {

    private final VideoDecoder decoder = new VideoDecoder();

    @Test
    void testDecodeVideoPacketType0Success() throws Exception {
        Path resource = Paths.get(VideoDecoderTest.class.getResource("/video_packet_type_0").toURI());
        byte[] bytes = Files.readAllBytes(resource);

        List<Object> result = new ArrayList<>();
        decoder.decode(null, Unpooled.wrappedBuffer(bytes), result);

        assertEquals(1, result.size());
        VideoPacket packet = (VideoPacket) result.get(0);
        assertEquals(0, packet.getPayloadType());
        assertEquals(3593, packet.getPayloadSize());
    }

    @Test
    void testDecodeVideoPacketType1Success() throws Exception {
        Path resource = Paths.get(VideoDecoderTest.class.getResource("/video_packet_type_1").toURI());
        byte[] bytes = Files.readAllBytes(resource);

        List<Object> result = new ArrayList<>();
        decoder.decode(null, Unpooled.wrappedBuffer(bytes), result);

        assertEquals(1, result.size());
        VideoPacket packet = (VideoPacket) result.get(0);
        assertEquals(1, packet.getPayloadType());
        assertEquals(36, packet.getPayloadSize());
    }

    @Test
    void testDecodeVideoPacketType5Skipped() throws Exception {
        Path resource = Paths.get(VideoDecoderTest.class.getResource("/video_packet_type_5").toURI());
        byte[] bytes = Files.readAllBytes(resource);

        List<Object> result = new ArrayList<>();
        decoder.decode(null, Unpooled.wrappedBuffer(bytes), result);

        assertTrue(result.isEmpty());
    }
}