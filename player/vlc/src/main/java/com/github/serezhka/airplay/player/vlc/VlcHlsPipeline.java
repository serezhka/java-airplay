package com.github.serezhka.airplay.player.vlc;

import com.github.serezhka.airplay.lib.AppLogs;
import com.github.serezhka.airplay.lib.HlsLifecycle;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HLS URI playback via system {@code cvlc}/{@code vlc}.
 * Semantics mirror {@code GstHlsPipeline} (position, pause, seek, volume, EOS).
 */
@Slf4j
final class VlcHlsPipeline {

    private static final long EOS_DEBOUNCE_NS = 1_500_000_000L;
    private static final long MIN_PLAY_BEFORE_EOS_NS = 2_000_000_000L;
    private static final int MAX_EARLY_EXIT_RETRIES = 4;
    private static final double ABSURD_SEEK_SECONDS = 86_400.0 * 7;

    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean userStopped = new AtomicBoolean();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicReference<Double> pendingSeekSeconds = new AtomicReference<>();
    private final Object processLock = new Object();

    private volatile String uri;
    private volatile double volumeLinear = 1.0;
    private volatile double playlistDurationSeconds;
    private volatile double positionBaseSeconds;
    private volatile long positionAnchorNanos;
    private volatile long lastEndedNotifyNanos;
    private volatile Thread playbackThread;
    private volatile Process vlcProcess;

    void start(String playlistUri, double volume) {
        start(playlistUri, volume, 0);
    }

    void start(String playlistUri, double volume, double initialSeekSeconds) {
        stop();
        volumeLinear = clampVolume(volume);
        paused.set(false);
        ended.set(false);
        userStopped.set(false);
        stopRequested.set(false);
        playlistDurationSeconds = 0;
        double seek = initialSeekSeconds > 0.05 && initialSeekSeconds < ABSURD_SEEK_SECONDS
                ? initialSeekSeconds : 0;
        positionBaseSeconds = seek;
        positionAnchorNanos = System.nanoTime();
        lastEndedNotifyNanos = 0;
        pendingSeekSeconds.set(seek > 0.05 ? seek : null);

        long myEpoch = epoch.get();
        uri = withCacheBuster(playlistUri, myEpoch);

        Thread thread = new Thread(() -> runPlayback(myEpoch), "vlc-hls");
        thread.setDaemon(true);
        playbackThread = thread;
        thread.start();
        log.info("HLS pipeline started (vlc) uri={} ss={}", uri,
                seek > 0.05 ? String.format(Locale.US, "%.3f", seek) : "0");
    }

    void stop() {
        epoch.incrementAndGet();
        userStopped.set(true);
        stopRequested.set(true);
        paused.set(false);
        ended.set(false);
        pendingSeekSeconds.set(null);
        destroyVlc();
        Thread thread = playbackThread;
        playbackThread = null;
        uri = null;
        playlistDurationSeconds = 0;
        positionBaseSeconds = 0;
        positionAnchorNanos = 0;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void pause() {
        if (!isActive() || ended.get()) {
            return;
        }
        positionBaseSeconds = currentPositionSeconds();
        paused.set(true);
        destroyVlc();
        log.info("HLS paused at {}s", positionBaseSeconds);
    }

    void resume() {
        if (!isActive()) {
            return;
        }
        if (ended.get()) {
            log.info("HLS resume ignored at EOS ({}s); waiting for next playlist", positionBaseSeconds);
            return;
        }
        paused.set(false);
        positionAnchorNanos = System.nanoTime();
        pendingSeekSeconds.set(positionBaseSeconds);
        log.info("HLS resumed from {}s", positionBaseSeconds);
    }

    void seek(double positionSeconds) {
        if (positionSeconds < 0 || Double.isNaN(positionSeconds) || Double.isInfinite(positionSeconds)) {
            return;
        }
        if (positionSeconds > ABSURD_SEEK_SECONDS) {
            log.warn("Ignoring absurd HLS seek to {}s", positionSeconds);
            return;
        }
        ended.set(false);
        paused.set(false);
        positionBaseSeconds = positionSeconds;
        positionAnchorNanos = System.nanoTime();
        pendingSeekSeconds.set(positionSeconds);
        Process running;
        synchronized (processLock) {
            running = vlcProcess;
        }
        if (running == null || !running.isAlive()) {
            log.info("HLS seek queued to {}s", positionSeconds);
            return;
        }
        destroyVlc();
        log.info("HLS seek requested to {}s", positionSeconds);
    }

    void noteMediaDuration(double seconds) {
        if (seconds > playlistDurationSeconds) {
            playlistDurationSeconds = seconds;
        }
    }

    void setVolume(double volume) {
        double next = clampVolume(volume);
        if (Math.abs(next - volumeLinear) < 0.01) {
            return;
        }
        volumeLinear = next;
        if (isActive() && !paused.get() && !ended.get()) {
            pendingSeekSeconds.set(currentPositionSeconds());
            destroyVlc();
        }
    }

    boolean isActive() {
        return uri != null || (playbackThread != null && playbackThread.isAlive());
    }

    boolean isPaused() {
        return paused.get() || ended.get();
    }

    boolean isEnded() {
        return ended.get();
    }

    double currentPositionSeconds() {
        if (ended.get()) {
            double dur = durationSeconds();
            return dur > 0 ? dur : positionBaseSeconds;
        }
        if (paused.get() || positionAnchorNanos == 0) {
            return positionBaseSeconds;
        }
        Process p = vlcProcess;
        if (p == null || !p.isAlive()) {
            return positionBaseSeconds;
        }
        double elapsed = (System.nanoTime() - positionAnchorNanos) / 1_000_000_000.0;
        double wall = Math.max(0, positionBaseSeconds + elapsed);
        double playlist = playlistDurationSeconds;
        return playlist > 0 ? Math.min(wall, playlist) : wall;
    }

    double durationSeconds() {
        return playlistDurationSeconds > 0 ? playlistDurationSeconds : 0;
    }

    private void runPlayback(long myEpoch) {
        int earlyExitRetries = 0;
        while (epoch.get() == myEpoch && !stopRequested.get() && !userStopped.get()) {
            if (paused.get()) {
                sleepQuiet(40);
                continue;
            }
            String playlistUri = uri;
            if (playlistUri == null) {
                break;
            }
            Double seek = pendingSeekSeconds.getAndSet(null);
            double startAt = seek != null && seek > 0.05 ? seek : positionBaseSeconds;
            if (startAt > 0.05) {
                positionBaseSeconds = startAt;
                positionAnchorNanos = System.nanoTime();
            } else {
                positionBaseSeconds = 0;
                positionAnchorNanos = System.nanoTime();
            }

            Process process;
            long startedAtNanos;
            try {
                process = startVlc(playlistUri, startAt, volumeLinear);
                startedAtNanos = System.nanoTime();
            } catch (Exception e) {
                log.warn("HLS vlc start failed, retrying: {}", e.toString());
                sleepQuiet(300);
                continue;
            }
            synchronized (processLock) {
                if (epoch.get() != myEpoch || stopRequested.get() || userStopped.get() || paused.get()) {
                    process.destroyForcibly();
                    continue;
                }
                vlcProcess = process;
            }
            log.info("HLS vlc started pid={} ss={} volume={}", process.pid(),
                    startAt > 0.05 ? String.format(Locale.US, "%.3f", startAt) : "0",
                    Math.round(volumeLinear * 100));

            try {
                while (epoch.get() == myEpoch && !stopRequested.get() && !userStopped.get()
                        && !paused.get() && pendingSeekSeconds.get() == null && process.isAlive()) {
                    long livedNs = System.nanoTime() - startedAtNanos;
                    if (livedNs >= MIN_PLAY_BEFORE_EOS_NS) {
                        earlyExitRetries = 0;
                    }
                    double dur = durationSeconds();
                    if (livedNs >= MIN_PLAY_BEFORE_EOS_NS
                            && dur > 0 && currentPositionSeconds() >= dur - 0.05) {
                        markEndedAndRefresh("ENDLIST", myEpoch);
                        destroyVlc();
                        return;
                    }
                    sleepQuiet(50);
                }
                if (pendingSeekSeconds.get() != null || paused.get()
                        || stopRequested.get() || userStopped.get() || epoch.get() != myEpoch) {
                    destroyVlc();
                    continue;
                }
                int code = process.waitFor();
                long livedNs = System.nanoTime() - startedAtNanos;
                if (epoch.get() != myEpoch || userStopped.get() || stopRequested.get() || paused.get()) {
                    continue;
                }
                if (livedNs < MIN_PLAY_BEFORE_EOS_NS && earlyExitRetries < MAX_EARLY_EXIT_RETRIES) {
                    earlyExitRetries++;
                    if (startAt > 0.05) {
                        pendingSeekSeconds.compareAndSet(null, startAt);
                    }
                    log.warn("HLS vlc exited early code={} after {}ms (ss={}); retry {}/{}",
                            code, livedNs / 1_000_000L,
                            startAt > 0.05 ? String.format(Locale.US, "%.3f", startAt) : "0",
                            earlyExitRetries, MAX_EARLY_EXIT_RETRIES);
                    sleepQuiet(250);
                    continue;
                }
                log.info("HLS vlc exited code={} after {}ms", code, livedNs / 1_000_000L);
                markEndedAndRefresh("EOS", myEpoch);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyVlc();
                break;
            }
        }
    }

    private Process startVlc(String playlistUri, double startAt, double volume) throws Exception {
        boolean headless = Boolean.parseBoolean(System.getProperty("airplay.vlc.hls.headless", "false"))
                || Boolean.parseBoolean(System.getProperty("airplay.vlc.headless", "false"));
        String binary = resolveBinary();
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        if (headless) {
            cmd.add("--intf");
            cmd.add("dummy");
            cmd.add("--vout");
            cmd.add("dummy");
            cmd.add("--aout");
            cmd.add("dummy");
        } else {
            cmd.add("--fullscreen");
            cmd.add("--no-video-title-show");
        }
        cmd.add("--play-and-exit");
        cmd.add("--no-interact");
        // Linear volume ≈ VLC gain (1.0 = default).
        cmd.add("--gain");
        cmd.add(String.format(Locale.US, "%.3f", Math.max(0.0, Math.min(8.0, volume))));
        if (startAt > 0.05) {
            cmd.add("--start-time");
            cmd.add(String.format(Locale.US, "%.3f", startAt));
        }
        cmd.add(playlistUri);
        cmd.add("vlc://quit");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        AppLogs.configureProcessLogging(pb, "vlc");
        // Ensure X11/Pulse reach cvlc when the JVM was started with DISPLAY=:10.
        Map<String, String> env = pb.environment();
        for (String key : List.of("DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR", "PULSE_SERVER", "DBUS_SESSION_BUS_ADDRESS")) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                env.put(key, value);
            }
        }
        Process process = pb.start();
        sleepQuiet(200);
        if (!process.isAlive()) {
            process.destroyForcibly();
            throw new IllegalStateException(binary + " exited immediately for " + playlistUri);
        }
        return process;
    }

    private static String resolveBinary() {
        for (String candidate : List.of("cvlc", "vlc")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                boolean finished = p.waitFor(3, TimeUnit.SECONDS);
                if (finished || p.isAlive()) {
                    p.destroyForcibly();
                    return candidate;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IllegalStateException("Neither cvlc nor vlc found on PATH");
    }

    private void destroyVlc() {
        Process process;
        synchronized (processLock) {
            process = vlcProcess;
            vlcProcess = null;
        }
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void markEndedAndRefresh(String reason, long myEpoch) {
        if (epoch.get() != myEpoch || userStopped.get() || uri == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastEndedNotifyNanos < EOS_DEBOUNCE_NS) {
            log.debug("Skipping duplicate HLS end notify ({})", reason);
            return;
        }
        lastEndedNotifyNanos = now;
        ended.set(true);
        double dur = durationSeconds();
        if (dur > 0) {
            positionBaseSeconds = dur;
        }
        log.info("HLS ended ({}), requesting playlist refresh for {}", reason, uri);
        HlsLifecycle.notifyEnded();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double clampVolume(double volumeLinear) {
        return Math.max(0.0, Math.min(1.0, volumeLinear));
    }

    private static String withCacheBuster(String playlistUri, long epoch) {
        if (playlistUri == null || playlistUri.isBlank()) {
            return playlistUri;
        }
        String lower = playlistUri.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return playlistUri;
        }
        String sep = playlistUri.contains("?") ? "&" : "?";
        return playlistUri + sep + "_airplay_epoch=" + epoch;
    }
}
