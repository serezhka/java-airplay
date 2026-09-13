package com.github.serezhka.airplay.player.harness;

import com.sun.management.OperatingSystemMXBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in time-series metrics for harness/bench. Off by default (no hot-path cost in production).
 * Enable with {@code -Dairplay.harness.metrics=true}.
 *
 * <p>Primary signal for player lag is {@code onVideo} write latency (pipe / sink backpressure).
 */
public final class PlaybackMetrics implements AutoCloseable {

    private static final long SLOW_WRITE_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    private static final long STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(66); // ~2 frames @30fps
    private static final int LATENCY_WINDOW = 1000;

    private final String scenario;
    private final String player;
    private final Instant startedAt = Instant.now();
    private final long startNano = System.nanoTime();
    private final ScheduledExecutorService sampler;
    private final ConcurrentLinkedQueue<Long> recentWriteNanos = new ConcurrentLinkedQueue<>();
    private final List<Sample> samples = new ArrayList<>();
    private final Object samplesLock = new Object();

    private final LongAdder framesOk = new LongAdder();
    private final LongAdder framesFail = new LongAdder();
    private final LongAdder bytesPushed = new LongAdder();
    private final LongAdder slowWrites = new LongAdder();
    private final LongAdder stalls = new LongAdder();
    private final AtomicLong rssHighWaterBytes = new AtomicLong();
    private final AtomicLong writeLatencyHighWaterNanos = new AtomicLong();

    private volatile Long childPid;
    private volatile long lastFrameNano = -1;
    private volatile long prevJvmCpuTimeNanos = -1;
    private volatile long prevChildCpuTimeNanos = -1;
    private volatile long prevSampleNano = -1;
    private volatile long prevGcTimeMs = -1;
    private final MemoryMXBean memoryMx = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean osMx;

    private PlaybackMetrics(String scenario, String player) {
        this.scenario = scenario;
        this.player = player;
        var os = ManagementFactory.getOperatingSystemMXBean();
        this.osMx = os instanceof OperatingSystemMXBean sunOs ? sunOs : null;
        if (enabled()) {
            sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "playback-metrics");
                t.setDaemon(true);
                return t;
            });
            sampler.scheduleAtFixedRate(this::sampleSafe, 0, 1, TimeUnit.SECONDS);
        } else {
            sampler = null;
        }
    }

    public static PlaybackMetrics start(String scenario) {
        return start(scenario, "unknown");
    }

    public static PlaybackMetrics start(String scenario, String player) {
        return new PlaybackMetrics(scenario, player);
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("airplay.harness.metrics", "false"));
    }

    public static int benchSeconds() {
        return Integer.parseInt(System.getProperty("airplay.harness.bench.seconds", "300"));
    }

    public static Path defaultReportDir() {
        String override = System.getProperty("airplay.harness.reportDir", "").trim();
        if (!override.isEmpty()) {
            return Path.of(override);
        }
        return Path.of("build", "reports", "playback-metrics");
    }

    public void trackChildPid(long pid) {
        if (pid > 0) {
            this.childPid = pid;
        }
    }

    /** Record one {@code onVideo} attempt. Safe no-op when metrics are disabled. */
    public void recordVideoWrite(long durationNanos, int bytes, boolean ok) {
        if (!enabled()) {
            return;
        }
        if (ok) {
            framesOk.increment();
            bytesPushed.add(bytes);
        } else {
            framesFail.increment();
        }
        writeLatencyHighWaterNanos.accumulateAndGet(durationNanos, Math::max);
        if (durationNanos >= SLOW_WRITE_NANOS) {
            slowWrites.increment();
        }
        long now = System.nanoTime();
        long prev = lastFrameNano;
        lastFrameNano = now;
        if (prev > 0 && (now - prev) >= STALL_NANOS) {
            stalls.increment();
        }
        recentWriteNanos.add(durationNanos);
        while (recentWriteNanos.size() > LATENCY_WINDOW) {
            recentWriteNanos.poll();
        }
    }

    public void finish(Path reportDir) throws IOException {
        if (!enabled()) {
            return;
        }
        if (sampler != null) {
            sampler.shutdown();
            try {
                sampler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sampleSafe();

        Duration wall = Duration.between(startedAt, Instant.now());
        Files.createDirectories(reportDir);
        String safe = scenario.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path json = reportDir.resolve(safe + ".json");
        Path html = reportDir.resolve(safe + ".html");
        String jsonBody = toJson(wall);
        Files.writeString(json, jsonBody, StandardCharsets.UTF_8);
        Files.writeString(html, BenchReportHtml.render(jsonBody), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }

    private void sampleSafe() {
        try {
            sample();
        } catch (Exception ignored) {
            // never fail the bench because of metrics
        }
    }

    private void sample() {
        long nowNano = System.nanoTime();
        double tSec = (nowNano - startNano) / 1_000_000_000.0;
        long jvmRss = processRssBytes(ProcessHandle.current().pid());
        Long child = childPid;
        long childRss = child != null ? processRssBytes(child) : 0L;
        rssHighWaterBytes.accumulateAndGet(Math.max(jvmRss, childRss), Math::max);

        double jvmCpu = processCpuPercent(ProcessHandle.current().pid(), true, nowNano);
        double childCpu = child != null ? processCpuPercent(child, false, nowNano) : 0.0;

        long gcMs = totalGcTimeMs();
        long gcDelta = prevGcTimeMs >= 0 ? Math.max(0, gcMs - prevGcTimeMs) : 0;
        prevGcTimeMs = gcMs;

        Percentiles p = percentiles(snapshotLatencies());
        long ok = framesOk.sum();
        long fail = framesFail.sum();
        long bytes = bytesPushed.sum();
        double fps = 0;
        if (prevSampleNano > 0) {
            double dt = (nowNano - prevSampleNano) / 1_000_000_000.0;
            // Approximate instantaneous fps from cumulative counters via sample deltas stored in series
            fps = samplesFpsEstimate(ok, dt);
        }
        prevSampleNano = nowNano;

        Sample s = new Sample(
                tSec,
                jvmRss,
                childRss,
                memoryMx.getHeapMemoryUsage().getUsed(),
                jvmCpu,
                childCpu,
                threadMx.getThreadCount(),
                gcMs,
                gcDelta,
                ok,
                fail,
                bytes,
                p.p50Nanos() / 1_000_000.0,
                p.p95Nanos() / 1_000_000.0,
                p.p99Nanos() / 1_000_000.0,
                writeLatencyHighWaterNanos.get() / 1_000_000.0,
                slowWrites.sum(),
                stalls.sum(),
                fps
        );
        synchronized (samplesLock) {
            // fix fps using previous sample frame count
            if (!samples.isEmpty()) {
                Sample prev = samples.get(samples.size() - 1);
                double dt = Math.max(0.001, s.tSec() - prev.tSec());
                double instFps = (s.framesOk() - prev.framesOk()) / dt;
                s = s.withFps(instFps);
            }
            samples.add(s);
        }
    }

    private double samplesFpsEstimate(long ok, double dt) {
        synchronized (samplesLock) {
            if (samples.isEmpty() || dt <= 0) {
                return 0;
            }
            long prevOk = samples.get(samples.size() - 1).framesOk();
            return (ok - prevOk) / dt;
        }
    }

    private long[] snapshotLatencies() {
        Long[] boxed = recentWriteNanos.toArray(Long[]::new);
        long[] arr = new long[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            arr[i] = boxed[i];
        }
        return arr;
    }

    private static Percentiles percentiles(long[] values) {
        if (values.length == 0) {
            return new Percentiles(0, 0, 0);
        }
        Arrays.sort(values);
        return new Percentiles(
                percentile(values, 0.50),
                percentile(values, 0.95),
                percentile(values, 0.99)
        );
    }

    private static long percentile(long[] sorted, double p) {
        int idx = Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(p * sorted.length) - 1));
        return sorted[idx];
    }

    private long totalGcTimeMs() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) {
                sum += t;
            }
        }
        return sum;
    }

    private double processCpuPercent(long pid, boolean jvm, long nowNano) {
        if (jvm && osMx != null) {
            double load = osMx.getProcessCpuLoad();
            if (load >= 0) {
                return load * 100.0;
            }
        }
        long cpuNanos = processCpuTimeNanos(pid);
        if (cpuNanos < 0) {
            return 0;
        }
        long prev = jvm ? prevJvmCpuTimeNanos : prevChildCpuTimeNanos;
        long prevT = prevSampleNano;
        if (jvm) {
            prevJvmCpuTimeNanos = cpuNanos;
        } else {
            prevChildCpuTimeNanos = cpuNanos;
        }
        if (prev < 0 || prevT < 0) {
            return 0;
        }
        long wall = nowNano - prevT;
        if (wall <= 0) {
            return 0;
        }
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.min(100.0 * cores, (cpuNanos - prev) * 100.0 / wall);
    }

    private static long processCpuTimeNanos(long pid) {
        Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (Files.isRegularFile(stat)) {
            try {
                String line = Files.readString(stat);
                int idx = line.lastIndexOf(')');
                if (idx < 0) {
                    return -1;
                }
                String[] parts = line.substring(idx + 1).trim().split("\\s+");
                // utime + stime are fields 14 and 15 in /proc/pid/stat (1-based), index 11-12 after state
                long utime = Long.parseLong(parts[11]);
                long stime = Long.parseLong(parts[12]);
                long ticks = utime + stime;
                long hz = 100; // common Linux USER_HZ
                return ticks * (1_000_000_000L / hz);
            } catch (Exception e) {
                return -1;
            }
        }
        // macOS / others: ps %cpu is not cumulative; return -1 and rely on JVM MXBean for self
        return -1;
    }

    static long processRssBytes(long pid) {
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
        return psRssBytes(pid);
    }

    private static long psRssBytes(long pid) {
        try {
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(pid))
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                p.waitFor(2, TimeUnit.SECONDS);
                if (line == null || line.isBlank()) {
                    return 0L;
                }
                return Long.parseLong(line.trim()) * 1024L;
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private String toJson(Duration wall) {
        Percentiles end = percentiles(snapshotLatencies());
        StringBuilder sb = new StringBuilder(16_384);
        sb.append('{');
        field(sb, "scenario", scenario, true);
        field(sb, "player", player, true);
        field(sb, "startedAt", startedAt.toString(), true);
        sb.append("\"wallMillis\":").append(wall.toMillis()).append(',');
        sb.append("\"targetFps\":30,");
        sb.append("\"framesOk\":").append(framesOk.sum()).append(',');
        sb.append("\"framesFail\":").append(framesFail.sum()).append(',');
        sb.append("\"bytesPushed\":").append(bytesPushed.sum()).append(',');
        sb.append("\"slowWrites\":").append(slowWrites.sum()).append(',');
        sb.append("\"stalls\":").append(stalls.sum()).append(',');
        sb.append("\"rssHighWaterBytes\":").append(rssHighWaterBytes.get()).append(',');
        sb.append("\"heapUsedBytes\":").append(memoryMx.getHeapMemoryUsage().getUsed()).append(',');
        sb.append("\"writeLatencyMs\":{");
        sb.append("\"p50\":").append(fmt(end.p50Nanos() / 1_000_000.0)).append(',');
        sb.append("\"p95\":").append(fmt(end.p95Nanos() / 1_000_000.0)).append(',');
        sb.append("\"p99\":").append(fmt(end.p99Nanos() / 1_000_000.0)).append(',');
        sb.append("\"max\":").append(fmt(writeLatencyHighWaterNanos.get() / 1_000_000.0));
        sb.append("},");
        sb.append("\"samples\":[");
        synchronized (samplesLock) {
            for (int i = 0; i < samples.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                samples.get(i).appendJson(sb);
            }
        }
        sb.append("]}");
        sb.append('\n');
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value, boolean more) {
        sb.append('"').append(name).append("\":").append(jsonString(value));
        if (more) {
            sb.append(',');
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Percentiles(long p50Nanos, long p95Nanos, long p99Nanos) {
    }

    private record Sample(
            double tSec,
            long jvmRssBytes,
            long childRssBytes,
            long heapUsedBytes,
            double jvmCpuPercent,
            double childCpuPercent,
            int threads,
            long gcTimeMs,
            long gcDeltaMs,
            long framesOk,
            long framesFail,
            long bytesPushed,
            double latencyP50Ms,
            double latencyP95Ms,
            double latencyP99Ms,
            double latencyMaxMs,
            long slowWrites,
            long stalls,
            double fps
    ) {
        Sample withFps(double newFps) {
            return new Sample(tSec, jvmRssBytes, childRssBytes, heapUsedBytes, jvmCpuPercent, childCpuPercent,
                    threads, gcTimeMs, gcDeltaMs, framesOk, framesFail, bytesPushed,
                    latencyP50Ms, latencyP95Ms, latencyP99Ms, latencyMaxMs, slowWrites, stalls, newFps);
        }

        void appendJson(StringBuilder sb) {
            sb.append('{');
            sb.append("\"t\":").append(fmt(tSec)).append(',');
            sb.append("\"jvmRssBytes\":").append(jvmRssBytes).append(',');
            sb.append("\"childRssBytes\":").append(childRssBytes).append(',');
            sb.append("\"heapUsedBytes\":").append(heapUsedBytes).append(',');
            sb.append("\"jvmCpuPercent\":").append(fmt(jvmCpuPercent)).append(',');
            sb.append("\"childCpuPercent\":").append(fmt(childCpuPercent)).append(',');
            sb.append("\"threads\":").append(threads).append(',');
            sb.append("\"gcTimeMs\":").append(gcTimeMs).append(',');
            sb.append("\"gcDeltaMs\":").append(gcDeltaMs).append(',');
            sb.append("\"framesOk\":").append(framesOk).append(',');
            sb.append("\"framesFail\":").append(framesFail).append(',');
            sb.append("\"bytesPushed\":").append(bytesPushed).append(',');
            sb.append("\"latencyP50Ms\":").append(fmt(latencyP50Ms)).append(',');
            sb.append("\"latencyP95Ms\":").append(fmt(latencyP95Ms)).append(',');
            sb.append("\"latencyP99Ms\":").append(fmt(latencyP99Ms)).append(',');
            sb.append("\"latencyMaxMs\":").append(fmt(latencyMaxMs)).append(',');
            sb.append("\"slowWrites\":").append(slowWrites).append(',');
            sb.append("\"stalls\":").append(stalls).append(',');
            sb.append("\"fps\":").append(fmt(fps));
            sb.append('}');
        }
    }
}
