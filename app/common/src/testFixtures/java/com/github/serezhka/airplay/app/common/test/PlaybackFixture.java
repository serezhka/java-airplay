package com.github.serezhka.airplay.app.common.test;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Shared Annex-B / AAC fixtures and feed helpers for player integration tests.
 */
public final class PlaybackFixture {

    private static final int FRAME_COUNT = 60;
    private static final long FRAME_DELAY_MILLIS = 33;

    public static byte[] h264() {
        return resourceBytes("/test-pattern.h264");
    }

    /** Stereo AAC-LC ADTS sine at 44.1 kHz ({@code fixtures/audio/sine-44100-aac-lc.adts}). */
    public static byte[] aacLcAdts() {
        return resourceBytes("/fixtures/audio/sine-44100-aac-lc.adts");
    }

    public static AudioStreamInfo aacLcStreamInfo() {
        return new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_LC_44100_2)
                .samplesPerFrame(1024)
                .build();
    }

    public static void play(Playback player) throws InterruptedException {
        playVideo(player, FRAME_COUNT);
    }

    public static void playVideo(Playback player, int frameCount) throws InterruptedException {
        byte[] video = h264();
        int chunkSize = Math.max(1, (video.length + frameCount - 1) / frameCount);
        for (int offset = 0, frames = 0; offset < video.length && frames < frameCount; offset += chunkSize, frames++) {
            int length = Math.min(chunkSize, video.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(video, offset, chunk, 0, length);
            player.onVideo(chunk);
            Thread.sleep(FRAME_DELAY_MILLIS);
        }
    }

    /** Push ADTS frames in ~20 ms chunks (rough AAC-LC frame pacing). */
    public static void playAacLc(Playback player) throws InterruptedException {
        byte[] audio = aacLcAdts();
        int chunk = Math.min(256, Math.max(64, audio.length / 40));
        for (int offset = 0; offset < audio.length; offset += chunk) {
            int length = Math.min(chunk, audio.length - offset);
            player.onAudio(audio, offset, length);
            Thread.sleep(20);
        }
    }

    public static void startMirror(Playback player) {
        player.onVideoFormat(new VideoStreamInfo("playback-test"));
    }

    public static void stopMirror(Playback player) {
        player.onVideoSrcDisconnect();
    }

    /**
     * Short MPEG-TS + media playlist via {@code ffmpeg} lavfi (needs ffmpeg on PATH).
     * Segment entry uses an absolute {@code file:} URI so Windows/CI runners resolve it.
     *
     * @return absolute {@code file:} URI of the {@code .m3u8}
     */
    public static String generateHlsPlaylist(Path dir, int seconds) throws Exception {
        Files.createDirectories(dir);
        Path media = dir.resolve("smoke.ts").toAbsolutePath().normalize();
        Path playlist = dir.resolve("mediadata.m3u8").toAbsolutePath().normalize();
        Process p = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + seconds + ":size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-tune", "zerolatency",
                "-c:a", "aac", "-ac", "2", "-ar", "44100",
                "-shortest", "-f", "mpegts", media.toString()
        ).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        if (!finished || p.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg failed generating HLS fixture: " + output);
        }
        // Relative segment name (resolved against the playlist directory).
        Files.writeString(playlist, """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:%d
                #EXTINF:%d.0,
                smoke.ts
                #EXT-X-ENDLIST
                """.formatted(seconds + 1, seconds));
        return playlist.toUri().toString();
    }

    public static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void awaitTrue(BooleanSupplier condition, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(message);
    }

    public static void awaitPosition(DoubleSupplier position, double min, long timeoutMs)
            throws InterruptedException {
        awaitTrue(() -> position.getAsDouble() >= min, timeoutMs,
                "position never reached " + min + ", last=" + position.getAsDouble());
    }

    public static void awaitNear(DoubleSupplier position, double target, double slack, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            double pos = position.getAsDouble();
            if (Math.abs(pos - target) <= slack || pos >= target - 0.1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("seek did not land near " + target + ", last=" + position.getAsDouble());
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream input = PlaybackFixture.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(input, "Missing fixture " + path).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture " + path, e);
        }
    }

    private PlaybackFixture() {
    }
}
