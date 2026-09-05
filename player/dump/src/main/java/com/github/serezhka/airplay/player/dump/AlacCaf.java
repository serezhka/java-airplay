package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class AlacCaf {

    static final int DATA_SIZE_OFFSET = 104;
    static final int PACKETS_OFFSET = 116;

    private AlacCaf() {
    }

    static void writePreamble(FileChannel channel, AudioStreamInfo info) throws IOException {
        int sampleRate = sampleRate(info);
        int channels = channels(info);
        int bitDepth = bitDepth(info);
        int framesPerPacket = info != null && info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 352;

        ByteBuffer header = ByteBuffer.allocate(PACKETS_OFFSET).order(ByteOrder.BIG_ENDIAN);
        header.put("caff".getBytes(StandardCharsets.US_ASCII));
        header.putShort((short) 1);
        header.putShort((short) 0);

        header.put("desc".getBytes(StandardCharsets.US_ASCII));
        header.putLong(32);
        header.putDouble(sampleRate);
        header.putInt(fourcc("alac"));
        header.putInt(0);
        header.putInt(0);
        header.putInt(framesPerPacket);
        header.putInt(channels);
        header.putInt(bitDepth);

        byte[] cookie = magicCookie(framesPerPacket, bitDepth, channels, sampleRate);
        header.put("kuki".getBytes(StandardCharsets.US_ASCII));
        header.putLong(cookie.length);
        header.put(cookie);

        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putLong(0);
        header.putInt(0);
        header.flip();
        channel.write(header);
    }

    static void finish(FileChannel channel, List<Integer> packetSizes, int framesPerPacket) throws IOException {
        long size = channel.size();
        if (size < PACKETS_OFFSET) {
            return;
        }
        long dataPayload = size - DATA_SIZE_OFFSET - 8;
        ByteBuffer sizeBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(dataPayload);
        sizeBuf.flip();
        channel.write(sizeBuf, DATA_SIZE_OFFSET);

        if (packetSizes == null || packetSizes.isEmpty()) {
            return;
        }
        byte[] encodedSizes = encodePacketSizes(packetSizes);
        int frames = framesPerPacket > 0 ? framesPerPacket : 352;
        ByteBuffer pakt = ByteBuffer.allocate(12 + 24 + encodedSizes.length).order(ByteOrder.BIG_ENDIAN);
        pakt.put("pakt".getBytes(StandardCharsets.US_ASCII));
        pakt.putLong(24L + encodedSizes.length);
        pakt.putLong(packetSizes.size());
        pakt.putLong((long) packetSizes.size() * frames);
        pakt.putInt(0);
        pakt.putInt(0);
        pakt.put(encodedSizes);
        pakt.flip();
        channel.write(pakt);
    }

    static byte[] encodePacketSizes(List<Integer> packetSizes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int packetSize : packetSizes) {
            writeVarint(out, Math.max(0, packetSize));
        }
        return out.toByteArray();
    }

    private static byte[] magicCookie(int frameLength, int bitDepth, int channels, int sampleRate) {
        ByteBuffer cookie = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
        cookie.putInt(36);
        cookie.put("alac".getBytes(StandardCharsets.US_ASCII));
        cookie.putInt(0);
        cookie.putInt(frameLength);
        cookie.put((byte) 0);
        cookie.put((byte) bitDepth);
        cookie.put((byte) 40);
        cookie.put((byte) 10);
        cookie.put((byte) 14);
        cookie.put((byte) channels);
        cookie.putShort((short) 0x00ff);
        cookie.putInt(0);
        cookie.putInt(0);
        cookie.putInt(sampleRate);
        return cookie.array();
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        int remaining = value;
        byte[] tmp = new byte[5];
        int n = 0;
        tmp[n++] = (byte) (remaining & 0x7f);
        remaining >>>= 7;
        while (remaining > 0) {
            tmp[n++] = (byte) ((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        for (int i = n - 1; i >= 0; i--) {
            out.write(tmp[i]);
        }
    }

    private static int sampleRate(AudioStreamInfo info) {
        String name = formatName(info);
        if (name.contains("48000")) {
            return 48000;
        }
        return 44100;
    }

    private static int channels(AudioStreamInfo info) {
        String name = formatName(info);
        return name.endsWith("_1") ? 1 : 2;
    }

    private static int bitDepth(AudioStreamInfo info) {
        String name = formatName(info);
        if (name.contains("_24_")) {
            return 24;
        }
        return 16;
    }

    private static String formatName(AudioStreamInfo info) {
        return info == null || info.getAudioFormat() == null ? "" : info.getAudioFormat().name();
    }

    private static int fourcc(String value) {
        return (value.charAt(0) << 24) | (value.charAt(1) << 16) | (value.charAt(2) << 8) | value.charAt(3);
    }
}
