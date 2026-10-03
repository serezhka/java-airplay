package com.github.serezhka.airplay.player.gstreamer;

import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.PadProbeReturn;
import org.freedesktop.gstreamer.PadProbeType;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.State;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;

import javax.swing.JFrame;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HLS URI playback via {@code playbin3} (fallback {@code playbin}).
 * <p>
 * Modern {@code hlsdemux2} needs a streams-aware context; plain {@code uridecodebin} does not
 * provide one. A custom demux graph is a follow-up.
 */
@Slf4j
final class GstHlsPipeline {

    private final ScheduledExecutorService seekScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "hls-seek-retry");
        t.setDaemon(true);
        return t;
    });

    private volatile Runnable onEnded = () -> {};
    private volatile Runnable onPresented = () -> {};
    private volatile Runnable onSeekDisplayed = () -> {};
    /** Set when a user seek is accepted; cleared on the following ASYNC_DONE. */
    private volatile boolean seekPicturePending;
    private final AtomicBoolean presented = new AtomicBoolean();
    private Pipeline pipeline;
    private Element videoSink;
    private JFrame window;
    private String uri;
    private volatile Double pendingSeekSeconds;
    private final AtomicInteger seekAttempts = new AtomicInteger();
    private ScheduledFuture<?> seekRetry;
    private ScheduledFuture<?> endWatch;
    private volatile boolean paused;
    private volatile boolean ended;
    private volatile double positionBaseSeconds;
    private volatile long positionAnchorNanos;
    private volatile double playlistDurationSeconds;
    private volatile long lastEndedNotifyNanos;
    private volatile long startedAtNanos;
    /** Wall-clock position must not run until GST reports a real media clock (avoids black-screen EOS). */
    private volatile boolean mediaClockTrusted;
    private final ScrubClock scrubClock = new ScrubClock();
    private volatile double volumeLinear = 1.0;
    /** First open uses playbin3. A slot failure rebuilds once with playbin. */
    private volatile boolean usePlaybin3 = true;
    private volatile int recoveries;
    private volatile int pipelineGeneration;
    private final AtomicBoolean recovering = new AtomicBoolean();
    private final AtomicBoolean gaveUp = new AtomicBoolean();
    private final Object pipelineLock = new Object();

    void start(String playlistUri, double volumeLinear) {
        synchronized (pipelineLock) {
            stop();
            uri = playlistUri;
            paused = false;
            ended = false;
            positionBaseSeconds = 0;
            positionAnchorNanos = System.nanoTime();
            startedAtNanos = System.nanoTime();
            lastEndedNotifyNanos = 0;
            mediaClockTrusted = false;
            this.volumeLinear = clampVolume(volumeLinear);
            usePlaybin3 = ElementFactory.find("playbin3") != null;
            recoveries = 0;
            buildPipeline();
        }
    }

    private void buildPipeline() {
        boolean headless = Boolean.parseBoolean(System.getProperty("airplay.gst.hls.headless", "false"));
        GstVideoSinkFactory.Result display = null;
        if (headless) {
            videoSink = ElementFactory.make("fakesink", "hls-sink");
            // Keep clock sync in headless smoke tests too.
            videoSink.set("sync", true);
            videoSink.set("async", false);
        } else {
            // sync=true: share pipeline clock with playbin audio (avoids free-run desync).
            display = GstVideoSinkFactory.create("hls", true);
            videoSink = display.sink();
        }

        String launch = usePlaybin3 ? "playbin3 name=hls" : "playbin name=hls";
        final int generation = ++pipelineGeneration;
        pipeline = (Pipeline) Gst.parseLaunch(launch);
        pipeline.set("uri", uri);
        pipeline.set("volume", volumeLinear);
        pipeline.set("video-sink", videoSink);
        watchFirstPicture(videoSink);
        log.info("HLS pipeline using {} headless={}", launch.split(" ")[0], headless);

        if (!headless && display != null && display.overlay() && display.canvas() != null) {
            window = GstFullscreenWindow.create(display.canvas());
            GstFullscreenWindow.show(window);
            Element overlaySink = GstVideoSinkFactory.overlayTarget(videoSink, "hls-sink");
            VideoOverlay overlay = VideoOverlay.wrap(overlaySink);
            long hwnd = Native.getComponentID(display.canvas());
            pipeline.getBus().setSyncHandler(message -> {
                if (!VideoOverlay.isPrepareWindowHandleMessage(message)) {
                    return BusSyncReply.PASS;
                }
                // Never invokeAndWait(EDT) from a GST sync handler — deadlocks d3d11 on Windows.
                overlay.setWindowHandle(hwnd);
                return BusSyncReply.DROP;
            });
        }

        pipeline.getBus().connect((Bus.EOS) source -> {
            if (generation != pipelineGeneration || uri == null) {
                return;
            }
            markEndedAndRefresh("EOS");
        });
        pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {
            if (generation != pipelineGeneration || uri == null || ended) {
                return;
            }
            log.error("HLS pipeline error: code={} message={}", code, message);
            // hlsdemux2 "Invalid manifest" after ad often never delivers bus EOS — treat as end.
            if (message != null && message.toLowerCase().contains("manifest")) {
                markEndedAndRefresh("ERROR " + message);
                return;
            }
            scheduleRebuild(code, message);
        });
        pipeline.getBus().connect((Bus.WARNING) (source, code, message) -> {
            if (generation != pipelineGeneration) {
                return;
            }
            log.warn("HLS pipeline warning: code={} message={}", code, message);
        });
        pipeline.getBus().connect((Bus.ASYNC_DONE) source -> {
            if (generation != pipelineGeneration) {
                return;
            }
            tryPendingSeek("async-done");
            ensurePlaying("async-done");
            noteSeekPicture();
        });
        pipeline.getBus().connect((Bus.DURATION_CHANGED) source -> {
            if (generation != pipelineGeneration) {
                return;
            }
            tryPendingSeek("duration");
        });
        pipeline.getBus().connect((Bus.STATE_CHANGED) (source, old, current, pending) -> {
            if (generation != pipelineGeneration) {
                return;
            }
            if (source == pipeline && current == State.PLAYING) {
                tryPendingSeek("playing");
            }
        });

        pipeline.play();
        tryPendingSeek("start");
        // playbin3 + hlsdemux2 often never posts bus EOS for short VOD ENDLIST ads;
        // poll playlist duration vs position (same idea as the FFmpeg player ENDLIST path).
        endWatch = seekScheduler.scheduleAtFixedRate(this::checkPositionAtEnd, 400, 200, TimeUnit.MILLISECONDS);
        log.info("HLS pipeline started uri={}", uri);
    }

    /**
     * playbin3 drops the picture when decodebin3 cannot take another stream
     * (logged as a missing plug-in) and then leaves the last frame on screen.
     * Rebuild once with playbin, which keeps decoding the streams it can show.
     */
    private void scheduleRebuild(int code, String message) {
        if (recoveries >= 1) {
            if (!recovering.get() && gaveUp.compareAndSet(false, true)) {
                log.warn("HLS pipeline error after restart, leaving it: code={} message={}", code, message);
            }
            return;
        }
        if (!recovering.compareAndSet(false, true)) {
            return;
        }
        recoveries++;
        usePlaybin3 = false;
        Double pending = pendingSeekSeconds;
        double resumeAt = pending != null ? pending : (mediaClockTrusted ? wallClockSeconds() : positionBaseSeconds);
        final int token = pipelineGeneration;
        final String keepUri = uri;
        log.info("HLS playbin3 failed (code={} {}), restarting with playbin at {}s", code, message, resumeAt);
        seekScheduler.execute(() -> {
            try {
                synchronized (pipelineLock) {
                    if (token != pipelineGeneration || keepUri == null || !keepUri.equals(uri) || ended) {
                        return;
                    }
                    boolean wasPaused = paused;
                    disposePipeline();
                    if (!keepUri.equals(uri) || ended) {
                        return;
                    }
                    buildPipeline();
                    if (resumeAt > 0.5) {
                        pendingSeekSeconds = resumeAt;
                        seekAttempts.set(0);
                        tryPendingSeek("restart");
                    }
                    if (wasPaused && pipeline != null) {
                        pipeline.pause();
                        paused = true;
                    }
                }
            } catch (RuntimeException e) {
                log.warn("HLS pipeline restart failed: {}", e.toString());
            } finally {
                recovering.set(false);
            }
        });
    }

    /** Drop the current pipeline without forgetting the playlist or the clock. */
    private void disposePipeline() {
        pipelineGeneration++;
        cancelSeekRetry();
        cancelEndWatch();
        if (pipeline != null) {
            try {
                pipeline.setState(State.NULL);
            } catch (Throwable t) {
                log.debug("HLS setState(NULL) failed: {}", t.toString());
            }
            try {
                pipeline.dispose();
            } catch (Throwable t) {
                log.debug("HLS dispose failed: {}", t.toString());
            }
            pipeline = null;
        }
        videoSink = null;
        if (window != null) {
            GstFullscreenWindow.hide(window);
            window = null;
        }
    }

    void stop() {
        synchronized (pipelineLock) {
            cancelSeekRetry();
            cancelEndWatch();
            pendingSeekSeconds = null;
            seekAttempts.set(0);
            paused = false;
            ended = false;
            positionBaseSeconds = 0;
            positionAnchorNanos = 0;
            playlistDurationSeconds = 0;
            lastEndedNotifyNanos = 0;
            startedAtNanos = 0;
            mediaClockTrusted = false;
            scrubClock.clear();
            presented.set(false);
            seekPicturePending = false;
            gaveUp.set(false);
            uri = null;
            disposePipeline();
        }
    }

    void pause() {
        if (pipeline == null || ended) {
            return;
        }
        // Anchor on the position we already told the sender. A raw demux query
        // here is the earlier keyframe and would walk the timeline backward.
        double reported = currentPositionSeconds();
        positionBaseSeconds = reported > 0 ? reported : positionBaseSeconds;
        paused = true;
        pipeline.pause();
        log.info("HLS paused at {}s", positionBaseSeconds);
    }

    void resume() {
        if (pipeline == null) {
            return;
        }
        boolean wasPaused = paused;
        ended = false;
        paused = false;
        positionAnchorNanos = System.nanoTime();
        double pos = positionBaseSeconds;
        pipeline.play();
        // playbin3 often stays frozen after PAUSED→PLAYING without a flush seek.
        // Only seek when leaving pause — never on redundant rate=1 while already playing.
        if (wasPaused && pos > 0.05) {
            pendingSeekSeconds = pos;
            seekAttempts.set(0);
            tryPendingSeek("resume");
        }
        log.info("HLS resumed from {}s (wasPaused={})", pos, wasPaused);
    }

    void seek(double positionSeconds) {
        if (positionSeconds < 0 || Double.isNaN(positionSeconds) || Double.isInfinite(positionSeconds)) {
            return;
        }
        // Guard against CLOCK_TIME_NONE /overflow-style targets (seen as ~2.5e6 hours in demux).
        if (positionSeconds > 86_400.0 * 7) {
            log.warn("Ignoring absurd HLS seek to {}s", positionSeconds);
            return;
        }
        ended = false;
        positionBaseSeconds = positionSeconds;
        positionAnchorNanos = System.nanoTime();
        // Demux often answers with the previous picture or an earlier keyframe.
        // Hold the scrub clock so /playback-info does not step backward.
        scrubClock.arm(positionSeconds);
        paused = false;
        if (pipeline != null) {
            pipeline.play();
        }
        pendingSeekSeconds = positionSeconds;
        seekAttempts.set(0);
        tryPendingSeek("request");
    }

    void noteMediaDuration(double seconds) {
        if (seconds > playlistDurationSeconds) {
            playlistDurationSeconds = seconds;
        }
    }

    void setVolume(double volumeLinear) {
        this.volumeLinear = clampVolume(volumeLinear);
        if (pipeline != null) {
            pipeline.set("volume", this.volumeLinear);
        }
    }

    boolean isActive() {
        return pipeline != null || uri != null;
    }

    double currentPositionSeconds() {
        if (ended) {
            double dur = durationSeconds();
            return dur > 0 ? dur : positionBaseSeconds;
        }
        double pos = scrubClock.report(rawPositionSeconds(), wallClockSeconds());
        checkPositionAtEnd(pos);
        return ended ? durationSeconds() : pos;
    }

    /** Raw pipeline clock position (0 if unknown) — for smoke tests. */
    double pipelinePositionSeconds() {
        return querySeconds(false);
    }

    double durationSeconds() {
        // Playlist ENDLIST sum is the item length YouTube expects. GST duration for HLS ads
        // is often hours (wrong demux timeline) and must not win.
        if (playlistDurationSeconds > 0) {
            return playlistDurationSeconds;
        }
        double gst = querySeconds(true);
        if (gst > 0 && gst <= 600) {
            return gst;
        }
        return 0;
    }

    boolean isPaused() {
        return paused || ended;
    }

    /** True after VOD/EOS — {@code /playback-info} should still report rate=1 (playing at end). */
    boolean isEnded() {
        return ended;
    }

    private void checkPositionAtEnd() {
        if (ended || uri == null || paused) {
            return;
        }
        checkPositionAtEnd(rawPositionSeconds());
    }

    private void checkPositionAtEnd(double pos) {
        if (ended || uri == null || paused || !mediaClockTrusted) {
            return;
        }
        double playlist = playlistDurationSeconds;
        // Need a real ENDLIST length and a bit of play time so a bad open/seek isn't EOS at t=0.
        if (playlist <= 0.5 || startedAtNanos == 0) {
            return;
        }
        if (System.nanoTime() - startedAtNanos < 1_500_000_000L) {
            return;
        }
        if (pos >= playlist - 0.05) {
            markEndedAndRefresh("position-at-end");
        }
    }

    private double wallClockSeconds() {
        if (paused || positionAnchorNanos == 0) {
            return positionBaseSeconds;
        }
        double elapsed = (System.nanoTime() - positionAnchorNanos) / 1_000_000_000.0;
        double wall = Math.max(0, positionBaseSeconds + elapsed);
        double playlist = playlistDurationSeconds;
        return playlist > 0 ? Math.min(wall, playlist) : wall;
    }

    private double rawPositionSeconds() {
        double playlist = playlistDurationSeconds;
        double gst = querySeconds(false);
        // Ignore GST positions past the VOD length — hlsdemux2 sometimes uses a huge media clock.
        if (gst > 0 && (playlist <= 0 || gst <= playlist + 1.0)) {
            mediaClockTrusted = true;
            return gst;
        }
        // Until the demux reports a real position, do not invent progress from wall-clock —
        // that produced black-screen "playback" and fake EOS on the first YouTube ad.
        if (!mediaClockTrusted) {
            return 0;
        }
        if (paused || positionAnchorNanos == 0) {
            return positionBaseSeconds;
        }
        double elapsed = (System.nanoTime() - positionAnchorNanos) / 1_000_000_000.0;
        double wall = Math.max(0, positionBaseSeconds + elapsed);
        return playlist > 0 ? Math.min(wall, playlist) : wall;
    }

    private void markEndedAndRefresh(String reason) {
        long now = System.nanoTime();
        if (now - lastEndedNotifyNanos < 1_500_000_000L) {
            log.debug("Skipping duplicate HLS end notify ({})", reason);
            return;
        }
        lastEndedNotifyNanos = now;
        ended = true;
        cancelEndWatch();
        double dur = durationSeconds();
        if (dur > 0) {
            positionBaseSeconds = dur;
        }
        // Local player stop at item end.
        // Do not call Playback.onPause — that emits reverse "paused"
        // and YouTube treats it as a user pause (blocks playlistRemove).
        if (pipeline != null) {
            try {
                pipeline.pause();
            } catch (Throwable t) {
                log.debug("HLS pause-at-end failed: {}", t.toString());
            }
        }
        paused = true;
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

    /** First buffer on the sink is the frame that is about to be shown. */
    private void watchFirstPicture(Element sink) {
        if (sink == null) {
            return;
        }
        Pad pad = sink.getStaticPad("sink");
        if (pad == null) {
            log.warn("HLS sink has no sink pad; picture announcement will use the fallback");
            return;
        }
        pad.addProbe(PadProbeType.BUFFER, (probed, info) -> {
            notePresented();
            return PadProbeReturn.REMOVE;
        });
    }

    private void notePresented() {
        if (uri == null || !presented.compareAndSet(false, true)) {
            return;
        }
        log.info("HLS first picture");
        try {
            onPresented.run();
        } catch (RuntimeException e) {
            log.warn("HLS picture callback failed: {}", e.toString());
        }
    }

    /** New picture after a user seek. Sends {@code playing} with the scrub clock. */
    private void noteSeekPicture() {
        if (!seekPicturePending || uri == null) {
            return;
        }
        seekPicturePending = false;
        log.info("HLS seek picture at {}s", currentPositionSeconds());
        try {
            onSeekDisplayed.run();
        } catch (RuntimeException e) {
            log.warn("HLS seek picture callback failed: {}", e.toString());
        }
    }

    private void cancelEndWatch() {
        ScheduledFuture<?> future = endWatch;
        endWatch = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private void ensurePlaying(String reason) {
        if (pipeline == null || paused) {
            return;
        }
        pipeline.play();
        log.debug("HLS ensure PLAYING ({})", reason);
    }

    private void tryPendingSeek(String reason) {
        Double seek = pendingSeekSeconds;
        Pipeline pipe = pipeline;
        if (seek == null || pipe == null) {
            return;
        }
        if (seek < 0.05 && ("start".equals(reason) || reason.startsWith("retry-"))) {
            pendingSeekSeconds = null;
            seekAttempts.set(0);
            cancelSeekRetry();
            positionBaseSeconds = 0;
            positionAnchorNanos = System.nanoTime();
            log.debug("Skipping no-op HLS seek to {}s ({})", seek, reason);
            return;
        }
        long ns = (long) (seek * 1_000_000_000L);
        boolean ok = pipe.seek(
                1.0,
                Format.TIME,
                EnumSet.of(SeekFlags.FLUSH, SeekFlags.KEY_UNIT),
                SeekType.SET,
                ns,
                SeekType.NONE,
                -1);
        log.info("HLS seek to {}s -> {} ({})", seek, ok, reason);
        if (ok) {
            pendingSeekSeconds = null;
            seekAttempts.set(0);
            cancelSeekRetry();
            positionBaseSeconds = seek;
            positionAnchorNanos = System.nanoTime();
            if (!paused) {
                pipe.play();
            }
            if ("request".equals(reason) || reason.startsWith("retry-")) {
                seekPicturePending = true;
            }
            return;
        }
        int attempt = seekAttempts.incrementAndGet();
        if (attempt > 40) {
            log.warn("Giving up HLS seek to {}s after {} attempts", seek, attempt);
            pendingSeekSeconds = null;
            cancelSeekRetry();
            return;
        }
        cancelSeekRetry();
        seekRetry = seekScheduler.schedule(() -> tryPendingSeek("retry-" + attempt), 250, TimeUnit.MILLISECONDS);
    }

    private void cancelSeekRetry() {
        ScheduledFuture<?> future = seekRetry;
        seekRetry = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private double querySeconds(boolean duration) {
        Pipeline pipe = pipeline;
        if (pipe == null) {
            return 0;
        }
        try {
            long nanos = duration
                    ? pipe.queryDuration(TimeUnit.NANOSECONDS)
                    : pipe.queryPosition(TimeUnit.NANOSECONDS);
            if (nanos <= 0 || nanos == Long.MAX_VALUE) {
                return 0;
            }
            return nanos / 1_000_000_000.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double clampVolume(double volumeLinear) {
        return Math.max(0.0, Math.min(1.0, volumeLinear));
    }
}

