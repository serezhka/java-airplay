package com.github.serezhka.airplay.player.ffmpeg;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-test for FFmpeg HLS pause/resume/seek + PCM sink without a phone.
 * Needs {@code ffmpeg} and {@code ffplay} on PATH.
 */
class FfmpegHlsPipelineSmokeTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(90)
    void pauseResumeSeekAndAudioSinkStayAlive() throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg not available");
        Assumptions.assumeTrue(ffplayAvailable(), "ffplay not available");

        Path media = temp.resolve("smoke.ts");
        generateSmokeTs(media);
        Assumptions.assumeTrue(Files.size(media) > 1000, "failed to generate smoke.ts");

        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        String uri = media.toUri().toString();

        FfmpegHlsPipeline hls = new FfmpegHlsPipeline();
        try {
            hls.start(uri, 1.0);
            awaitPosition(hls, 0.4, 12_000);
            assertTrue(hls.audioSinkAlive(), "ffplay PCM sink died early (check -ch_layout / FFmpeg 8)");

            double beforePause = hls.currentPositionSeconds();
            hls.pause();
            Thread.sleep(800);
            double duringPause = hls.currentPositionSeconds();
            assertTrue(Math.abs(duringPause - beforePause) < 0.5,
                    "position should stay put while paused: " + beforePause + " -> " + duringPause);

            hls.resume();
            awaitAdvance(hls, duringPause + 0.3, 12_000);
            assertTrue(hls.audioSinkAlive(), "ffplay PCM sink died after resume");

            double beforeSeek = hls.currentPositionSeconds();
            double target = Math.max(0.5, beforeSeek + 1.0);
            hls.seek(target);
            awaitNear(hls, target, 2.0, 12_000);
            awaitAdvance(hls, target + 0.15, 12_000);
            assertTrue(hls.audioSinkAlive(), "ffplay PCM sink died after seek");
        } finally {
            hls.stop();
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    private static boolean ffmpegAvailable() {
        return toolAvailable("ffmpeg");
    }

    private static boolean ffplayAvailable() {
        return toolAvailable("ffplay");
    }

    private static boolean toolAvailable(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "-version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void generateSmokeTs(Path out) throws Exception {
        Process p = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=6:size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-tune", "zerolatency",
                "-c:a", "aac", "-ac", "2", "-ar", "44100",
                "-shortest", "-f", "mpegts", out.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        Assumptions.assumeTrue(finished && p.exitValue() == 0,
                "ffmpeg failed generating smoke.ts: " + output);
    }

    private static void awaitPosition(FfmpegHlsPipeline hls, double min, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hls.currentPositionSeconds() >= min) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(false, "position never reached " + min + ", last=" + hls.currentPositionSeconds());
    }

    private static void awaitAdvance(FfmpegHlsPipeline hls, double min, long timeoutMs) throws InterruptedException {
        awaitPosition(hls, min, timeoutMs);
    }

    private static void awaitNear(FfmpegHlsPipeline hls, double target, double slack, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            double pos = hls.currentPositionSeconds();
            if (Math.abs(pos - target) <= slack || pos >= target - 0.1) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(false, "seek did not land near " + target + ", last=" + hls.currentPositionSeconds());
    }
}
