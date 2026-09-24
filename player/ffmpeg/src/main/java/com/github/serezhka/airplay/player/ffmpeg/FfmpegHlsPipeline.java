package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.player.support.NativeProcessLog;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HLS playback via system {@code ffplay} (native SDL video + Pulse audio).
 * Semantics mirror {@code GstHlsPipeline} for AirPlay (position, pause, seek, volume, EOS).
 */
@Slf4j
final class FfmpegHlsPipeline {

    private static final long EOS_DEBOUNCE_NS = 1_500_000_000L;
    /** Exits faster than this after start are treated as open failures, not real EOS. */
    private static final long MIN_PLAY_BEFORE_EOS_NS = 2_000_000_000L;
    private static final int MAX_EARLY_EXIT_RETRIES = 4;
    private static final double ABSURD_SEEK_SECONDS = 86_400.0 * 7;

    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean userStopped = new AtomicBoolean();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicReference<Double> pendingSeekSeconds = new AtomicReference<>();
    private volatile Runnable onEnded = () -> {};
    private final Object processLock = new Object();

    private volatile String uri;
    private volatile double volumeLinear = 1.0;
    private volatile double playlistDurationSeconds;
    private volatile double positionBaseSeconds;
    private volatile long positionAnchorNanos;
    private volatile long lastEndedNotifyNanos;
    private volatile Thread playbackThread;
    private volatile Process ffplayProcess;

    void start(String playlistUri, double volume) {
        start(playlistUri, volume, 0);
    }

    /**
     * @param initialSeekSeconds position to open at (avoids start-then-seek race that kills the first ffplay)
     */
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
        // Bust HTTP cache so ffplay re-fetches playlists after ad→ad / ad→content.
        uri = withCacheBuster(playlistUri, myEpoch);

        Thread thread = new Thread(() -> runPlayback(myEpoch), "ffmpeg-hls");
        thread.setDaemon(true);
        playbackThread = thread;
        thread.start();
        log.info("HLS pipeline started (ffplay) uri={} ss={}", uri,
                seek > 0.05 ? String.format(Locale.US, "%.3f", seek) : "0");
    }

    void stop() {
        epoch.incrementAndGet();
        userStopped.set(true);
        stopRequested.set(true);
        paused.set(false);
        ended.set(false);
        pendingSeekSeconds.set(null);
        destroyFfplay();
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
            if (thread.isAlive()) {
                log.warn("HLS thread still alive after join; epoch={} will ignore its updates", epoch.get());
            }
        }
    }

    void pause() {
        if (!isActive() || ended.get()) {
            return;
        }
        positionBaseSeconds = currentPositionSeconds();
        paused.set(true);
        // Stop ffplay so Pulse does not keep playing; resume reopens with -ss.
        destroyFfplay();
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
        // Reopen at the frozen position.
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
            running = ffplayProcess;
        }
        if (running == null || !running.isAlive()) {
            // First open (or between destroy and reopen) — runPlayback picks up pendingSeek.
            log.info("HLS seek queued to {}s", positionSeconds);
            return;
        }
        destroyFfplay(); // wake waitFor so the loop reopens with -ss
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
        // ffplay volume is start-only; reopen at current position.
        if (isActive() && !paused.get() && !ended.get()) {
            pendingSeekSeconds.set(currentPositionSeconds());
            destroyFfplay();
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
        Process p = ffplayProcess;
        if (p == null || !p.isAlive()) {
            // Freeze while ffplay is down (seek/reopen/retry) — do not free-run the scrubber.
            return positionBaseSeconds;
        }
        double elapsed = (System.nanoTime() - positionAnchorNanos) / 1_000_000_000.0;
        double wall = Math.max(0, positionBaseSeconds + elapsed);
        double playlist = playlistDurationSeconds;
        return playlist > 0 ? Math.min(wall, playlist) : wall;
    }

    double durationSeconds() {
        if (playlistDurationSeconds > 0) {
            return playlistDurationSeconds;
        }
        return 0;
    }

    /** Smoke tests: ffplay child still alive (A/V in one process). */
    boolean audioSinkAlive() {
        Process p = ffplayProcess;
        return p != null && p.isAlive();
    }

    /** Kept for unit tests that simulate demux PTS resets — reported position is wall-clock only. */
    void noteDemuxTimestampMicros(long timestampMicros) {
        // no-op
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
                process = startFfplay(playlistUri, startAt, volumeLinear);
                startedAtNanos = System.nanoTime();
            } catch (Exception e) {
                log.warn("HLS ffplay start failed, retrying: {}", e.toString());
                sleepQuiet(300);
                continue;
            }
            synchronized (processLock) {
                if (epoch.get() != myEpoch || stopRequested.get() || userStopped.get() || paused.get()) {
                    process.destroyForcibly();
                    continue;
                }
                ffplayProcess = process;
            }
            log.info("HLS ffplay started pid={} ss={} volume={}", process.pid(),
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
                    // Only trust wall-clock ENDLIST after ffplay has actually been playing a bit;
                    // otherwise a stale duration + failed open looks like EOS at t=0.
                    if (livedNs >= MIN_PLAY_BEFORE_EOS_NS
                            && dur > 0 && currentPositionSeconds() >= dur - 0.05) {
                        markEndedAndRefresh("ENDLIST", myEpoch);
                        destroyFfplay();
                        return;
                    }
                    sleepQuiet(50);
                }
                if (pendingSeekSeconds.get() != null || paused.get()
                        || stopRequested.get() || userStopped.get() || epoch.get() != myEpoch) {
                    destroyFfplay();
                    continue;
                }
                // Process exited on its own.
                int code = process.waitFor();
                long livedNs = System.nanoTime() - startedAtNanos;
                if (epoch.get() != myEpoch || userStopped.get() || stopRequested.get() || paused.get()) {
                    continue;
                }
                if (livedNs < MIN_PLAY_BEFORE_EOS_NS && earlyExitRetries < MAX_EARLY_EXIT_RETRIES) {
                    earlyExitRetries++;
                    // Keep the intended -ss so we do not reopen at 0 after a failed seek open.
                    if (startAt > 0.05) {
                        pendingSeekSeconds.compareAndSet(null, startAt);
                    }
                    log.warn("HLS ffplay exited early code={} after {}ms (ss={}); retry {}/{}",
                            code, livedNs / 1_000_000L,
                            startAt > 0.05 ? String.format(Locale.US, "%.3f", startAt) : "0",
                            earlyExitRetries, MAX_EARLY_EXIT_RETRIES);
                    sleepQuiet(250);
                    continue;
                }
                log.info("HLS ffplay exited code={} after {}ms", code, livedNs / 1_000_000L);
                markEndedAndRefresh("EOS", myEpoch);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyFfplay();
                break;
            }
        }
    }

    private Process startFfplay(String playlistUri, double startAt, double volume) throws Exception {
        boolean headless = Boolean.parseBoolean(System.getProperty("airplay.ffmpeg.hls.headless", "false"));
        List<String> cmd = new ArrayList<>();
        cmd.add("ffplay");
        if (headless) {
            cmd.add("-nodisp");
        } else {
            cmd.add("-fs");
        }
        cmd.add("-autoexit");
        cmd.add("-loglevel");
        cmd.add("error");
        // FFmpeg 6+/8 default extension_picky rejects YouTube googlevideo URLs (no .ts/.m4s).
        // Only for HLS — these options can break plain mpegts/.ts opens used in smoke tests.
        if (isHlsPlaylistUri(playlistUri)) {
            cmd.add("-extension_picky");
            cmd.add("0");
            cmd.add("-allowed_extensions");
            cmd.add("ALL");
            cmd.add("-allowed_segment_extensions");
            cmd.add("ALL");
        }
        // -fflags nobuffer makes ffplay exit in ~200ms on short VOD ENDLIST items.
        cmd.add("-flags");
        cmd.add("low_delay");
        cmd.add("-framedrop");
        cmd.add("-sync");
        cmd.add("audio");
        cmd.add("-volume");
        cmd.add(String.valueOf(Math.max(0, Math.min(100, (int) Math.round(volume * 100)))));
        if (startAt > 0.05) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", startAt));
        }
        cmd.add(playlistUri);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        FfplayPcmSink.forcePulseAudioEnv(pb);
        NativeProcessLog.configureProcessLogging(pb, "ffmpeg");
        Process process = pb.start();
        // Give SDL a moment; if it dies immediately the URL/env is wrong.
        sleepQuiet(150);
        if (!process.isAlive()) {
            process.destroyForcibly();
            throw new IllegalStateException("ffplay exited immediately for " + playlistUri);
        }
        return process;
    }

    private static boolean isHlsPlaylistUri(String playlistUri) {
        if (playlistUri == null) {
            return false;
        }
        String lower = playlistUri.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    private void destroyFfplay() {
        Process process;
        synchronized (processLock) {
            process = ffplayProcess;
            ffplayProcess = null;
        }
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
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
        onEnded.run();
    }

    void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded == null ? () -> {} : onEnded;
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
