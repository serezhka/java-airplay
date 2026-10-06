package com.github.serezhka.airplay.player.ffmpeg;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            long pid = hls.playerPid();
            hls.pause();
            Thread.sleep(800);
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                assertEquals(pid, hls.playerPid(), "pause must freeze ffplay, not close the window");
            }
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
            awaitAudioAlive(hls, 8_000);
            assertTrue(hls.audioSinkAlive(), "ffplay PCM sink died after seek");
        } finally {
            hls.stop();
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    /**
     * YouTube scrub (session 20260926-144311): {@code rate=0} then {@code /scrub} 1.84s later.
     * The hold must keep the same ffplay pid; the seek then reopens at the new position.
     */
    @Test
    @Timeout(90)
    void scrubAfterLongHoldKeepsPictureThenSeeks() throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg not available");
        Assumptions.assumeTrue(ffplayAvailable(), "ffplay not available");
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "SIGSTOP hold is Unix-only");

        Path media = temp.resolve("scrub.ts");
        generateSmokeTs(media, 12);
        Assumptions.assumeTrue(Files.size(media) > 1000, "failed to generate scrub.ts");

        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        FfmpegHlsPipeline hls = new FfmpegHlsPipeline();
        try {
            hls.start(media.toUri().toString(), 1.0);
            hls.noteMediaDuration(12);
            awaitPosition(hls, 0.4, 12_000);
            long pid = hls.playerPid();
            assertTrue(pid > 0, "ffplay should be alive once the clock has latched, pid=" + pid);

            hls.pause();
            Thread.sleep(2_000);
            assertEquals(pid, hls.playerPid(), "ffplay window closed during the scrub hold");
            double held = hls.currentPositionSeconds();
            assertTrue(held < 3.0, "position ran during hold: " + held);

            java.util.concurrent.atomic.AtomicBoolean sawHeldFrame = new java.util.concurrent.atomic.AtomicBoolean();
            Thread watch = new Thread(() -> {
                long end = System.currentTimeMillis() + 4_000;
                while (System.currentTimeMillis() < end) {
                    if (hls.heldFramePid() > 0) {
                        sawHeldFrame.set(true);
                        return;
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            watch.start();
            hls.seek(8.0);
            watch.join(4_000);
            assertTrue(sawHeldFrame.get(), "seek closed the previous ffplay before the replacement had a picture");
            awaitNear(hls, 8.0, 1.5, 12_000);
            awaitAudioAlive(hls, 8_000);
            assertTrue(hls.audioSinkAlive(), "ffplay died after seek");
            awaitAdvance(hls, 8.2, 8_000);
        } finally {
            hls.stop();
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    @Test
    @Timeout(90)
    void extensionlessSegmentUrlsPlayWithPickyOff() throws Exception {
        Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg not available");
        Assumptions.assumeTrue(ffplayAvailable(), "ffplay not available");

        Path media = temp.resolve("seg.bin");
        generateSmokeTs(media);
        Assumptions.assumeTrue(Files.size(media) > 1000, "failed to generate seg.bin");

        Path playlist = temp.resolve("pl.m3u8");
        Files.writeString(playlist, """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:7
                #EXTINF:6.0,
                seg.bin
                #EXT-X-ENDLIST
                """);

        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        FfmpegHlsPipeline hls = new FfmpegHlsPipeline();
        try {
            hls.start(playlist.toUri().toString(), 1.0);
            awaitPosition(hls, 0.3, 15_000);
            assertTrue(hls.audioSinkAlive(),
                    "ffplay must play extensionless HLS segments (-extension_picky 0)");
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
        generateSmokeTs(out, 6);
    }

    private static void generateSmokeTs(Path out, int seconds) throws Exception {
        Process p = new ProcessBuilder(
                "ffmpeg", "-y",
                "-f", "lavfi", "-i", "testsrc=duration=" + seconds + ":size=320x240:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-tune", "zerolatency",
                "-c:a", "aac", "-ac", "2", "-ar", "44100",
                "-shortest", "-f", "mpegts", out.toAbsolutePath().toString()
        ).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        Assumptions.assumeTrue(finished && p.exitValue() == 0,
                "ffmpeg failed generating smoke.ts: " + output);
    }

    private static void awaitAudioAlive(FfmpegHlsPipeline hls, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hls.audioSinkAlive()) {
                return;
            }
            Thread.sleep(100);
        }
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
