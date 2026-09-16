package com.github.serezhka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.glib.GLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Partial self-test for HLS pause/resume/seek without a phone.
 * Needs GStreamer on PATH (or configured via {@link GstPlayerUtils}).
 */
class GstHlsPipelineSmokeTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void initGst() {
        Assumptions.assumeTrue(gstLaunchAvailable(), "gst-launch-1.0 not available");
        GstPlayerUtils.configurePaths();
        GLib.setEnv("GST_DEBUG_NO_COLOR", "1", true);
        GLib.setEnv("GST_DEBUG", "1", true);
        if (!Gst.isInitialized()) {
            Gst.init(Version.of(1, 10), "GstHlsPipelineSmokeTest");
        }
    }

    @Test
    @Timeout(90)
    void pauseResumeSeekAdvancesPipelineClock() throws Exception {
        Assumptions.assumeTrue(gstLaunchAvailable(), "gst-launch-1.0 not available");

        Path media = temp.resolve("smoke.ts");
        generateSmokeTs(media);
        Assumptions.assumeTrue(Files.size(media) > 1000, "failed to generate smoke.ts");

        System.setProperty("airplay.gst.hls.headless", "true");
        String uri = media.toUri().toString();

        GstHlsPipeline hls = new GstHlsPipeline();
        try {
            hls.start(uri, 1.0);
            awaitPosition(hls, 0.4, 8_000);

            double beforePause = hls.pipelinePositionSeconds();
            hls.pause();
            Thread.sleep(800);
            double duringPause = hls.pipelinePositionSeconds();
            assertTrue(Math.abs(duringPause - beforePause) < 0.35,
                    "position should stay put while paused: " + beforePause + " -> " + duringPause);

            hls.resume();
            awaitAdvance(hls, duringPause + 0.3, 8_000);

            double beforeSeek = hls.pipelinePositionSeconds();
            double target = Math.max(0.5, beforeSeek + 1.5);
            hls.seek(target);
            awaitNear(hls, target, 1.5, 10_000);
            awaitAdvance(hls, target + 0.2, 8_000);
        } finally {
            hls.stop();
            System.clearProperty("airplay.gst.hls.headless");
        }
    }

    private static boolean gstLaunchAvailable() {
        try {
            GstPlayerUtils.configurePaths();
            Process p = new ProcessBuilder(gstLaunchCommand(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String gstLaunchCommand() {
        String fromEnv = System.getenv("GSTREAMER_1_0_ROOT_MSVC_X86_64");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path candidate = Path.of(fromEnv, "bin", "gst-launch-1.0.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "gst-launch-1.0";
    }

    private static void generateSmokeTs(Path out) throws Exception {
        String location = out.toAbsolutePath().toString().replace('\\', '/');
        List<String> cmd = new ArrayList<>();
        cmd.add(gstLaunchCommand());
        cmd.add("-e");
        cmd.add("videotestsrc");
        cmd.add("num-buffers=180");
        cmd.add("is-live=false");
        cmd.add("!");
        cmd.add("video/x-raw,framerate=30/1,width=320,height=240");
        cmd.add("!");
        cmd.add("videoconvert");
        cmd.add("!");
        cmd.add("x264enc");
        cmd.add("tune=zerolatency");
        cmd.add("!");
        cmd.add("h264parse");
        cmd.add("!");
        cmd.add("mpegtsmux");
        cmd.add("!");
        cmd.add("filesink");
        cmd.add("location=" + location);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(45, TimeUnit.SECONDS);
        Assumptions.assumeTrue(finished && p.exitValue() == 0,
                "gst-launch failed generating smoke.ts: " + output);
    }

    private static void awaitPosition(GstHlsPipeline hls, double min, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hls.pipelinePositionSeconds() >= min) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(false, "position never reached " + min + ", last=" + hls.pipelinePositionSeconds());
    }

    private static void awaitAdvance(GstHlsPipeline hls, double min, long timeoutMs) throws InterruptedException {
        awaitPosition(hls, min, timeoutMs);
    }

    private static void awaitNear(GstHlsPipeline hls, double target, double slack, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            double pos = hls.pipelinePositionSeconds();
            if (Math.abs(pos - target) <= slack || pos >= target - 0.1) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(false, "seek did not land near " + target + ", last=" + hls.pipelinePositionSeconds());
    }
}
