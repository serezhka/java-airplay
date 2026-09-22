package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.HlsLifecycle;
import com.sun.jna.Native;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.BusSyncReply;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.Gst;
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

    void start(String playlistUri, double volumeLinear) {
        stop();
        uri = playlistUri;
        paused = false;
        ended = false;
        positionBaseSeconds = 0;
        positionAnchorNanos = System.nanoTime();
        startedAtNanos = System.nanoTime();
        lastEndedNotifyNanos = 0;
        mediaClockTrusted = false;

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

        String launch = ElementFactory.find("playbin3") != null ? "playbin3 name=hls" : "playbin name=hls";
        pipeline = (Pipeline) Gst.parseLaunch(launch);
        pipeline.set("uri", playlistUri);
        pipeline.set("volume", clampVolume(volumeLinear));
        pipeline.set("video-sink", videoSink);
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
            if (uri == null) {
                return;
            }
            markEndedAndRefresh("EOS");
        });
        pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {
            log.error("HLS pipeline error: code={} message={}", code, message);
            // hlsdemux2 "Invalid manifest" after ad often never delivers bus EOS — treat as end.
            if (uri != null && message != null && message.toLowerCase().contains("manifest")) {
                markEndedAndRefresh("ERROR " + message);
            }
        });
        pipeline.getBus().connect((Bus.WARNING) (source, code, message) ->
                log.warn("HLS pipeline warning: code={} message={}", code, message));
        pipeline.getBus().connect((Bus.ASYNC_DONE) source -> {
            tryPendingSeek("async-done");
            ensurePlaying("async-done");
        });
        pipeline.getBus().connect((Bus.DURATION_CHANGED) source -> tryPendingSeek("duration"));
        pipeline.getBus().connect((Bus.STATE_CHANGED) (source, old, current, pending) -> {
            if (source == pipeline && current == State.PLAYING) {
                tryPendingSeek("playing");
            }
        });

        pipeline.play();
        tryPendingSeek("start");
        // playbin3 + hlsdemux2 often never posts bus EOS for short VOD ENDLIST ads;
        // poll playlist duration vs position (same idea as the FFmpeg player ENDLIST path).
        endWatch = seekScheduler.scheduleAtFixedRate(this::checkPositionAtEnd, 400, 200, TimeUnit.MILLISECONDS);
        log.info("HLS pipeline started uri={}", playlistUri);
    }

    void stop() {
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
        uri = null;
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

    void pause() {
        if (pipeline == null || ended) {
            return;
        }
        double gstPos = querySeconds(false);
        positionBaseSeconds = gstPos > 0 ? gstPos : currentPositionSeconds();
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
        if (pipeline != null) {
            pipeline.set("volume", clampVolume(volumeLinear));
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
        double pos = rawPositionSeconds();
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
        // Do not call AirPlayConsumer.onMediaPlaylistPause — that emits reverse "paused"
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
        HlsLifecycle.notifyEnded();
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

