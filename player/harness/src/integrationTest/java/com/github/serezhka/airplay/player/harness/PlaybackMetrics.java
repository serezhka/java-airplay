package com.github.serezhka.airplay.player.harness;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in metrics for integration/bench runs. Off by default so production players stay untouched.
 * Enable with {@code -Dairplay.harness.metrics=true} or {@link #enabled()}.
 */
public final class PlaybackMetrics implements AutoCloseable {

    private final String scenario;
    private final Instant startedAt = Instant.now();
    private final AtomicLong rssHighWaterBytes = new AtomicLong();
    private final ScheduledExecutorService sampler;
    private volatile Long childPid;

    private PlaybackMetrics(String scenario) {
        this.scenario = scenario;
        if (enabled()) {
            sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "playback-metrics");
                t.setDaemon(true);
                return t;
            });
            sampler.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
        } else {
            sampler = null;
        }
    }

    public static PlaybackMetrics start(String scenario) {
        return new PlaybackMetrics(scenario);
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("airplay.harness.metrics", "false"));
    }

    public void trackChildPid(long pid) {
        this.childPid = pid;
    }

    public void finish(Path reportDir) throws IOException {
        Duration wall = Duration.between(startedAt, Instant.now());
        if (!enabled()) {
            return;
        }
        Files.createDirectories(reportDir);
        String json = String.format(Locale.ROOT,
                "{\"scenario\":%s,\"wallMillis\":%d,\"rssHighWaterBytes\":%d,\"heapUsedBytes\":%d}%n",
                jsonString(scenario),
                wall.toMillis(),
                rssHighWaterBytes.get(),
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        Path out = reportDir.resolve(scenario.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }

    private void sample() {
        long rss = childPid != null ? processRssBytes(childPid) : jvmRssBytes();
        rssHighWaterBytes.accumulateAndGet(rss, Math::max);
    }

    private static long jvmRssBytes() {
        OptionalLong pid = ProcessHandle.current().pid() > 0
                ? OptionalLong.of(ProcessHandle.current().pid())
                : OptionalLong.empty();
        return pid.isPresent() ? processRssBytes(pid.getAsLong()) : 0L;
    }

    private static long processRssBytes(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        if (Files.isRegularFile(status)) {
            try {
                for (String line : Files.readAllLines(status)) {
                    if (line.startsWith("VmRSS:")) {
                        String[] parts = line.trim().split("\\s+");
                        return Long.parseLong(parts[1]) * 1024L;
                    }
                }
            } catch (Exception ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
