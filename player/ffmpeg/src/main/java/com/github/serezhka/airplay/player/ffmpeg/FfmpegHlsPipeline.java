package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.player.support.NativeProcessLog;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
    private volatile Runnable onPresented = () -> {};
    private volatile Runnable onSeekDisplayed = () -> {};
    private volatile boolean replacementClockPending;
    private volatile boolean pictureAnnounced;
    private final Object processLock = new Object();

    private volatile String uri;
    private volatile double volumeLinear = 1.0;
    private volatile double playlistDurationSeconds;
    private volatile double positionBaseSeconds;
    private volatile long positionAnchorNanos;
    private volatile long lastEndedNotifyNanos;
    private volatile Thread playbackThread;
    private volatile Process ffplayProcess;
    /** Frozen window kept on screen until the seek replacement has a picture. */
    private volatile Process heldFrame;
    private volatile java.util.concurrent.atomic.AtomicBoolean incomingPicture = new java.util.concurrent.atomic.AtomicBoolean();

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
        // Do not run the sender clock until ffplay prints a real timestamp.
        // Starting it here put the phone several seconds ahead of the first frame.
        positionAnchorNanos = 0;
        lastEndedNotifyNanos = 0;
        pictureAnnounced = false;
        replacementClockPending = false;
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
        destroyLoose(heldFrame);
        heldFrame = null;
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
        // Freeze ffplay in place. Destroying it closes the SDL window (desktop shows through)
        // and a following /scrub has to reopen from scratch. SIGSTOP keeps the last frame and
        // stops Pulse. Windows has no SIGSTOP, so fall back to closing the process there.
        if (!signalFfplay("STOP")) {
            destroyFfplay();
        }
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
        if (!signalFfplay("CONT")) {
            pendingSeekSeconds.set(positionBaseSeconds);
            log.info("HLS resume reopening from {}s", positionBaseSeconds);
        } else {
            log.info("HLS resumed from {}s", positionBaseSeconds);
        }
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
        replacementClockPending = true;
        pendingSeekSeconds.set(positionSeconds);
        // Leave the current ffplay stopped so its window stays up. The playback thread
        // opens the replacement and closes this one only after the new picture is up.
        signalFfplay("STOP");
        Process running;
        synchronized (processLock) {
            running = ffplayProcess;
        }
        if ((running == null || !running.isAlive()) && (heldFrame == null || !heldFrame.isAlive())) {
            log.info("HLS seek queued to {}s", positionSeconds);
            return;
        }
        log.info("HLS seek requested to {}s, holding the current frame", positionSeconds);
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
        // Keep the scrub clock moving while ffplay is stopped or reopening. Freezing
        // here left /playback-info stuck and the sender showed pause.
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
        return playerPid() > 0;
    }

    /** Smoke tests: same pid across a hold means the window was not closed. */
    long playerPid() {
        Process p = ffplayProcess;
        return p != null && p.isAlive() ? p.pid() : -1;
    }

    /** Smoke tests: pid of the frozen window kept across a seek, or -1. */
    long heldFramePid() {
        Process p = heldFrame;
        return p != null && p.isAlive() ? p.pid() : -1;
    }

    /** ffplay prints {@code M-A:} / {@code M-V:} once a clock is running. {@code nan} is not a picture. */
    static boolean ffplayStatusShowsPicture(String statusLine) {
        return statusClockSeconds(statusLine) != null;
    }

    /** Leading clock on an ffplay status line, or null when it is missing or {@code nan}. */
    static Double statusClockSeconds(String statusLine) {
        if (statusLine == null) {
            return null;
        }
        int mark = markerIndex(statusLine);
        if (mark <= 0) {
            return null;
        }
        String head = statusLine.substring(0, mark).trim();
        if (head.isEmpty() || head.equalsIgnoreCase("nan")) {
            return null;
        }
        try {
            double value = Double.parseDouble(head);
            if (Double.isNaN(value) || value < 0 || value > ABSURD_SEEK_SECONDS) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int markerIndex(String statusLine) {
        for (String marker : new String[]{" M-A:", " M-V:", " A-V:"}) {
            int at = statusLine.indexOf(marker);
            if (at >= 0) {
                return at;
            }
        }
        return -1;
    }

    private void runPlayback(long myEpoch) {
        int earlyExitRetries = 0;
        while (epoch.get() == myEpoch && !stopRequested.get() && !userStopped.get()) {
            if (paused.get() && pendingSeekSeconds.get() == null) {
                sleepQuiet(40);
                continue;
            }
            String playlistUri = uri;
            if (playlistUri == null) {
                break;
            }

            Process existing;
            synchronized (processLock) {
                existing = ffplayProcess;
            }
            boolean reuseFrozen = existing != null && existing.isAlive() && pendingSeekSeconds.get() == null;
            Process process;
            long startedAtNanos;
            double startAt;
            if (reuseFrozen) {
                process = existing;
                startAt = positionBaseSeconds;
                startedAtNanos = System.nanoTime();
                positionAnchorNanos = startedAtNanos;
            } else {
                Double seek = pendingSeekSeconds.getAndSet(null);
                startAt = seek != null && seek > 0.05 ? seek : positionBaseSeconds;
                if (startAt > 0.05) {
                    positionBaseSeconds = startAt;
                } else if (positionAnchorNanos == 0) {
                    positionBaseSeconds = 0;
                }
                if (existing != null && existing.isAlive()) {
                    Process alreadyHeld = heldFrame;
                    if (alreadyHeld != null && alreadyHeld.isAlive() && alreadyHeld != existing) {
                        // A newer seek arrived before the previous replacement had a picture.
                        destroyLoose(existing);
                    } else {
                        heldFrame = existing;
                        synchronized (processLock) {
                            if (ffplayProcess == existing) {
                                ffplayProcess = null;
                            }
                        }
                    }
                }
                java.util.concurrent.atomic.AtomicBoolean picture;
                try {
                    process = startFfplay(playlistUri, startAt, volumeLinear);
                    picture = incomingPicture;
                    startedAtNanos = System.nanoTime();
                } catch (Exception e) {
                    log.warn("HLS ffplay start failed, retrying: {}", e.toString());
                    if (seek != null && seek > 0.05) {
                        pendingSeekSeconds.compareAndSet(null, seek);
                    }
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
                Process held = heldFrame;
                if (held != null && held.isAlive() && held != process) {
                    boolean shown = waitForPicture(picture, process, myEpoch);
                    if (epoch.get() != myEpoch || stopRequested.get() || userStopped.get()) {
                        destroyLoose(process);
                        destroyLoose(held);
                        heldFrame = null;
                        continue;
                    }
                    if (!shown && !process.isAlive()) {
                        log.warn("HLS replacement exited before a picture; keeping the held frame");
                        synchronized (processLock) {
                            if (ffplayProcess == process) {
                                ffplayProcess = null;
                            }
                        }
                        continue;
                    }
                    if (pendingSeekSeconds.get() != null && !shown) {
                        destroyLoose(process);
                        synchronized (processLock) {
                            if (ffplayProcess == process) {
                                ffplayProcess = null;
                            }
                        }
                        continue;
                    }
                    destroyLoose(held);
                    if (heldFrame == held) {
                        heldFrame = null;
                    }
                    log.info("HLS previous frame dropped (replacement picture={})", shown);
                }
            }

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
                if (stopRequested.get() || userStopped.get() || epoch.get() != myEpoch) {
                    destroyFfplay();
                    destroyLoose(heldFrame);
                    heldFrame = null;
                    continue;
                }
                if (pendingSeekSeconds.get() != null) {
                    Process held = heldFrame;
                    if (held != null && held.isAlive() && held != process) {
                        destroyLoose(process);
                        synchronized (processLock) {
                            if (ffplayProcess == process) {
                                ffplayProcess = null;
                            }
                        }
                    }
                    // Otherwise keep this process; the next iteration freezes it as the held frame.
                    continue;
                }
                if (paused.get()) {
                    // Keep the frozen window. The next loop iteration waits until resume or seek.
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
        // info (not error) so the status line is visible; the log pump keeps only real messages.
        cmd.add("-loglevel");
        cmd.add("info");
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
        // Sync to the video clock. "audio" holds the first frame until the audio
        // clock exists, which on HLS is the multi-second probe.
        cmd.add("-sync");
        cmd.add("video");
        cmd.add("-volume");
        cmd.add(String.valueOf(Math.max(0, Math.min(100, (int) Math.round(volume * 100)))));
        if (startAt > 0.05) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", startAt));
        }
        // Default analyze window is 5s. A short window is enough for H.264 HLS and
        // is what was sitting between "ffplay started" and the first picture.
        cmd.add("-probesize");
        cmd.add("32768");
        cmd.add("-analyzeduration");
        cmd.add("200000");
        cmd.add(playlistUri);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        FfplayPcmSink.forcePulseAudioEnv(pb);
        if (isHlsPlaylistUri(playlistUri)) {
            Ipv4Getaddrinfo.apply(pb);
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        java.util.concurrent.atomic.AtomicBoolean picture = new java.util.concurrent.atomic.AtomicBoolean();
        incomingPicture = picture;
        Process process = pb.start();
        Thread pump = new Thread(() -> pumpFfplayLog(process.getInputStream(), picture), "ffmpeg-hls-log");
        pump.setDaemon(true);
        pump.start();
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

    /**
     * @return true when the signal was delivered. False on Windows or when ffplay is not running.
     */
    private boolean signalFfplay(String signal) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return false;
        }
        Process process;
        synchronized (processLock) {
            process = ffplayProcess;
        }
        if (process == null || !process.isAlive()) {
            return false;
        }
        try {
            Process kill = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!kill.waitFor(2, TimeUnit.SECONDS) || kill.exitValue() != 0) {
                log.warn("kill -{} pid={} failed", signal, process.pid());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("kill -{} failed: {}", signal, e.toString());
            return false;
        }
    }

    private static final long HELD_FRAME_TIMEOUT_MS = 12_000;

    private boolean waitForPicture(java.util.concurrent.atomic.AtomicBoolean picture, Process process, long myEpoch) {
        long deadline = System.nanoTime() + HELD_FRAME_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (picture != null && picture.get()) {
                return true;
            }
            if (process == null || !process.isAlive()) {
                return false;
            }
            if (epoch.get() != myEpoch || stopRequested.get() || userStopped.get()) {
                return false;
            }
            if (pendingSeekSeconds.get() != null) {
                return false;
            }
            sleepQuiet(40);
        }
        return picture != null && picture.get();
    }

    private void pumpFfplayLog(java.io.InputStream in, java.util.concurrent.atomic.AtomicBoolean picture) {
        java.nio.file.Path logFile = NativeProcessLog.playerLogFile("ffmpeg");
        try {
            java.nio.file.Path parent = logFile.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (java.io.IOException e) {
            log.debug("HLS log dir: {}", e.toString());
        }
        try (java.io.InputStream input = in;
             java.io.OutputStream out = java.nio.file.Files.newOutputStream(
                     logFile,
                     java.nio.file.StandardOpenOption.CREATE,
                     java.nio.file.StandardOpenOption.APPEND)) {
            byte[] buf = new byte[2048];
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = input.read(buf)) >= 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\r' || b == '\n') {
                        String text = line.toString(java.nio.charset.StandardCharsets.UTF_8).replace("\u001b[2K", "");
                        line.reset();
                        if (text.isBlank()) {
                            continue;
                        }
                        if (text.contains("fd=") && text.contains("vq=")) {
                            Double clock = statusClockSeconds(text);
                            if (clock != null) {
                                picture.set(true);
                                noteClock(clock);
                            }
                            continue;
                        }
                        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        out.write('\n');
                    } else {
                        line.write(b);
                    }
                }
            }
        } catch (java.io.IOException e) {
            log.debug("HLS ffplay log pump ended: {}", e.toString());
        }
    }

    private void destroyFfplay() {
        Process process;
        synchronized (processLock) {
            process = ffplayProcess;
            ffplayProcess = null;
        }
        destroyLoose(process);
    }

    private void destroyLoose(Process process) {
        if (process == null) {
            return;
        }
        synchronized (processLock) {
            if (ffplayProcess == process) {
                ffplayProcess = null;
            }
            if (heldFrame == process) {
                heldFrame = null;
            }
        }
        if (process.isAlive()) {
            try {
                new ProcessBuilder("kill", "-CONT", Long.toString(process.pid()))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // SIGKILL below still reaps a stopped process.
            }
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
        onEnded.run();
    }

    void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded == null ? () -> {} : onEnded;
    }

    void setOnPresented(Runnable onPresented) {
        this.onPresented = onPresented == null ? () -> {} : onPresented;
    }

    void setOnSeekDisplayed(Runnable onSeekDisplayed) {
        this.onSeekDisplayed = onSeekDisplayed == null ? () -> {} : onSeekDisplayed;
    }

    /** First real ffplay clock. Until then the reported position does not advance. */
    private void noteClock(double clockSeconds) {
        if (positionAnchorNanos == 0) {
            positionBaseSeconds = clockSeconds;
            positionAnchorNanos = System.nanoTime();
            log.info("HLS clock latched at {}s", String.format(Locale.US, "%.3f", clockSeconds));
        }
        if (replacementClockPending) {
            replacementClockPending = false;
            // The status clock is the real picture. Do not walk the sender backward
            // onto an earlier keyframe; only adopt it when it is at or ahead of us.
            if (clockSeconds + 0.05 >= positionBaseSeconds) {
                positionBaseSeconds = clockSeconds;
                positionAnchorNanos = System.nanoTime();
            }
            log.info("HLS seek picture at {}s", String.format(Locale.US, "%.3f", currentPositionSeconds()));
            try {
                onSeekDisplayed.run();
            } catch (RuntimeException e) {
                log.warn("HLS seek picture callback failed: {}", e.toString());
            }
        }
        notePicture();
    }

    private void notePicture() {
        if (pictureAnnounced) {
            return;
        }
        pictureAnnounced = true;
        log.info("HLS first picture");
        try {
            onPresented.run();
        } catch (RuntimeException e) {
            log.warn("HLS picture callback failed: {}", e.toString());
        }
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
