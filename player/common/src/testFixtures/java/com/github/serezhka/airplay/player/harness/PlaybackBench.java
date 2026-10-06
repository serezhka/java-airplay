package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.player.test.PlaybackFixture;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared multi-minute bench. Each player module supplies its own {@link Playback}.
 */
public final class PlaybackBench {

    private PlaybackBench() {
    }

    public static void run(String playerName, Playback player, LongSupplier childPid) throws Exception {
        System.setProperty("airplay.harness.metrics", "true");
        try {
            int seconds = PlaybackMetrics.benchSeconds();
            assumeTrue(seconds > 0);

            Path reportDir = PlaybackMetrics.defaultReportDir();
            Files.createDirectories(reportDir);
            String scenario = "bench-" + playerName + "-" + seconds + "s";

            try (PlaybackMetrics metrics = PlaybackMetrics.start(scenario, playerName)) {
                InstrumentedConsumer consumer = new InstrumentedConsumer(player, metrics);
                try {
                    consumer.onVideoFormat(new VideoStreamInfo("bench-" + playerName));
                    if (childPid != null) {
                        metrics.trackChildPid(childPid.getAsLong());
                    }
                    feedFor(consumer, seconds);
                } finally {
                    consumer.onVideoSrcDisconnect();
                }
                metrics.finish(reportDir);

                long frames = readFramesOk(reportDir.resolve(safeName(scenario) + ".json"));
                assertTrue(frames > seconds * 10L, "expected sustained frame push, got " + frames);
                assertTrue(Files.isRegularFile(reportDir.resolve(safeName(scenario) + ".html")));
            }
        } finally {
            System.clearProperty("airplay.harness.metrics");
        }
    }

    public static boolean onPath(String binary) {
        try {
            Process process = new ProcessBuilder(binary, "-version").redirectErrorStream(true).start();
            return process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static void feedFor(Playback consumer, int seconds) throws InterruptedException {
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
}
