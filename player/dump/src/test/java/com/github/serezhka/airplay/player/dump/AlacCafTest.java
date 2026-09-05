package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlacCafTest {

    @TempDir
    Path tempDir;

    @Test
    void writesPlayableCafWithMagicCookieAndPacketTable() throws Exception {
        Path file = tempDir.resolve("audio.caf");
        AudioStreamInfo info = new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.ALAC)
                .audioFormat(AudioStreamInfo.AudioFormat.ALAC_44100_16_2)
                .samplesPerFrame(352)
                .build();
        byte[] packetA = {1, 2, 3};
        byte[] packetB = {4, 5};

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            AlacCaf.writePreamble(channel, info);
            channel.write(ByteBuffer.wrap(packetA));
            channel.write(ByteBuffer.wrap(packetB));
            AlacCaf.finish(channel, List.of(packetA.length, packetB.length), 352);
        }

        byte[] dumped = Files.readAllBytes(file);
        assertEquals("caff", new String(dumped, 0, 4));
        ByteBuffer header = ByteBuffer.wrap(dumped).order(ByteOrder.BIG_ENDIAN);
        assertEquals(4 + packetA.length + packetB.length, header.getLong(AlacCaf.DATA_SIZE_OFFSET));
        assertArrayEquals(packetA, java.util.Arrays.copyOfRange(dumped, AlacCaf.PACKETS_OFFSET, AlacCaf.PACKETS_OFFSET + 3));
        assertArrayEquals(packetB, java.util.Arrays.copyOfRange(dumped, AlacCaf.PACKETS_OFFSET + 3, AlacCaf.PACKETS_OFFSET + 5));

        int paktOffset = AlacCaf.PACKETS_OFFSET + packetA.length + packetB.length;
        assertEquals("pakt", new String(dumped, paktOffset, 4));
        ByteBuffer pakt = ByteBuffer.wrap(dumped, paktOffset, dumped.length - paktOffset).order(ByteOrder.BIG_ENDIAN);
        pakt.getInt();
        long chunkSize = pakt.getLong();
        assertEquals(2, pakt.getLong());
        assertEquals(704, pakt.getLong());
        assertEquals(0, pakt.getInt());
        assertEquals(0, pakt.getInt());
        byte[] encoded = new byte[(int) (chunkSize - 24)];
        pakt.get(encoded);
        assertArrayEquals(AlacCaf.encodePacketSizes(List.of(3, 2)), encoded);
        assertTrue(dumped.length > AlacCaf.PACKETS_OFFSET);
    }

    @Test
    void encodesCafVariableLengthPacketSizes() {
        assertArrayEquals(new byte[]{0x60}, AlacCaf.encodePacketSizes(List.of(96)));
        assertArrayEquals(new byte[]{(byte) 0x82, 0x60}, AlacCaf.encodePacketSizes(List.of(352)));
    }
}
