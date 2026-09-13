package com.github.serezhka.airplay.player.test;

import com.github.serezhka.airplay.server.AirPlayConsumer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class PlaybackFixture {

    private static final int FRAME_COUNT = 60;
    private static final long FRAME_DELAY_MILLIS = 33;

    public static byte[] h264() {
        try (InputStream input = PlaybackFixture.class.getResourceAsStream("/test-pattern.h264")) {
            return Objects.requireNonNull(input, "Missing test-pattern.h264 fixture").readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read H264 playback fixture", e);
        }
    }

    public static void play(AirPlayConsumer player) throws InterruptedException {
        byte[] video = h264();
        int chunkSize = Math.max(1, (video.length + FRAME_COUNT - 1) / FRAME_COUNT);
        for (int offset = 0; offset < video.length; offset += chunkSize) {
            int length = Math.min(chunkSize, video.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(video, offset, chunk, 0, length);
            player.onVideo(chunk);
            Thread.sleep(FRAME_DELAY_MILLIS);
        }
    }

    private PlaybackFixture() {
    }
}
