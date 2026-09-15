package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.ffmpeg.FFmpegPlayer;
import com.github.serezhka.airplay.player.gstreamer.GstPlayer;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import com.github.serezhka.airplay.player.vlc.VlcPlayer;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("bench")
class BenchPlaybackTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void fiveMinutePlayerBench() throws Exception {
        System.setProperty("airplay.harness.metrics", "true");
        String player = System.getProperty("airplay.harness.bench.player", "ffmpeg").trim().toLowerCase(Locale.ROOT);
        int seconds = PlaybackMetrics.benchSeconds();
        assumeTrue(seconds > 0);

        Path reportDir = PlaybackMetrics.defaultReportDir();
        Files.createDirectories(reportDir);
        String scenario = "bench-" + player + "-" + seconds + "s";

        try (PlaybackMetrics metrics = PlaybackMetrics.start(scenario, player)) {
            AirPlayConsumer raw = createPlayer(player);
            InstrumentedConsumer consumer = new InstrumentedConsumer(raw, metrics);
            try {
                consumer.onVideoFormat(new VideoStreamInfo("bench-" + player));
                if (raw instanceof FFmpegPlayer ffmpeg) {
                    metrics.trackChildPid(ffmpeg.videoProcessPid());
                } else if (raw instanceof GstLaunchPlayer gst) {
                    metrics.trackChildPid(gst.pid());
                }
                feedFor(consumer, seconds);
            } finally {
                consumer.onVideoSrcDisconnect();
            }
            metrics.finish(reportDir);

            long frames = readFramesOk(reportDir.resolve(safeName(scenario) + ".json"));
            assertTrue(frames > seconds * 10L, "expected sustained frame push, got " + frames);
            assertTrue(Files.isRegularFile(reportDir.resolve(safeName(scenario) + ".html")));
        } finally {
            System.clearProperty("airplay.harness.metrics");
        }
    }

    private static AirPlayConsumer createPlayer(String player) {
        return switch (player) {
            case "ffmpeg" -> {
                assumeTrue(BenchPlaybackTest.onPath("ffplay"), "ffplay not on PATH");
                yield new FFmpegPlayer(30);
            }
            case "gstreamer" -> {
                assumeTrue(onPath("gst-launch-1.0") || gstreamerLikelyAvailable(), "GStreamer not available");
                // Long JNI benches SIGSEGV on Linux Actions; CLI path is what CI/bench uses.
                if (useGstCli()) {
                    assumeTrue(onPath("gst-launch-1.0"), "gst-launch-1.0 not on PATH");
                    yield new GstLaunchPlayer();
                }
                yield new GstPlayer();
            }
            case "vlc" -> {
                assumeTrue(vlcLikelyAvailable(), "VLC native libraries not available");
                yield new VlcPlayer();
            }
            case "recording", "none" -> new RecordingConsumer();
            default -> throw new IllegalArgumentException("Unknown bench player: " + player
                    + " (use ffmpeg|gstreamer|vlc|recording)");
        };
    }

    private static void feedFor(AirPlayConsumer consumer, int seconds) throws InterruptedException {
        byte[] frame = PlaybackFixture.h264();
        long frameDelayMs = 33L;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int offset = 0;
        int chunk = Math.max(1, frame.length / 60);
        while (System.nanoTime() < deadline) {
            if (offset >= frame.length) {
                offset = 0;
            }
            int len = Math.min(chunk, frame.length - offset);
            byte[] piece = new byte[len];
            System.arraycopy(frame, offset, piece, 0, len);
            offset += len;
            consumer.onVideo(piece);
            Thread.sleep(frameDelayMs);
        }
    }

    private static long readFramesOk(Path json) throws Exception {
        String body = Files.readString(json);
        int idx = body.indexOf("\"framesOk\":");
        if (idx < 0) {
            return 0;
        }
        int start = idx + "\"framesOk\":".length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Long.parseLong(body.substring(start, end));
    }

    private static String safeName(String scenario) {
        return scenario.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static boolean useGstCli() {
        if (Boolean.parseBoolean(System.getProperty("airplay.gst.cli", "false"))) {
            return true;
        }
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_GST_CLI", "false"))) {
            return true;
        }
        // Default for CI benches: avoid gst1-java long-run crashes.
        return System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null;
    }

    static boolean onPath(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
            return p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean gstreamerLikelyAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Files.isDirectory(Path.of("/opt/homebrew/lib")) || Files.isDirectory(Path.of("/usr/local/lib"));
        }
        return onPath("gst-launch-1.0");
    }

    static boolean vlcLikelyAvailable() {
        return Files.isRegularFile(Path.of("/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib"))
                || Files.isRegularFile(Path.of("/usr/lib/x86_64-linux-gnu/libvlc.so.5"))
                || Files.isRegularFile(Path.of("/usr/lib/aarch64-linux-gnu/libvlc.so.5"))
                || Files.isRegularFile(Path.of("/usr/lib/libvlc.so.5"))
                || onPath("vlc")
                || onPath("cvlc");
    }
}
