package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.HlsLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Canvas;
import java.awt.Graphics2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;

/**
 * HLS playback via JavaCV {@link FFmpegFrameGrabber} (libav), mirroring {@code GstHlsPipeline}
 * semantics for AirPlay YouTube (position, pause, seek, volume, EOS → {@link HlsLifecycle}).
 */
@Slf4j
final class FfmpegHlsPipeline {

    private static final long EOS_DEBOUNCE_NS = 1_500_000_000L;
    private static final long PREFETCH_RETRY_MS = 300;
    private static final long PREFETCH_RETRY_BUDGET_MS = 15_000;
    private static final double ABSURD_SEEK_SECONDS = 86_400.0 * 7;

    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean userStopped = new AtomicBoolean();
    private final AtomicReference<Double> pendingSeekSeconds = new AtomicReference<>();
    private final Object audioLock = new Object();

    private volatile String uri;
    private volatile double volumeLinear = 1.0;
    private volatile double playlistDurationSeconds;
    private volatile double positionBaseSeconds;
    private volatile long positionAnchorNanos;
    private volatile double lastGrabberPositionSeconds;
    private volatile long lastEndedNotifyNanos;
    private volatile Thread playbackThread;
    private volatile JFrame window;
    private volatile Canvas videoCanvas;
    private volatile FfplayPcmSink pcmSink;
    private volatile boolean loggedUnsupportedAudio;
    private volatile boolean loggedAudioDead;

    void start(String playlistUri, double volume) {
        stop();
        uri = playlistUri;
        volumeLinear = clampVolume(volume);
        paused.set(false);
        ended.set(false);
        userStopped.set(false);
        stopRequested.set(false);
        playlistDurationSeconds = 0;
        positionBaseSeconds = 0;
        positionAnchorNanos = System.nanoTime();
        lastGrabberPositionSeconds = 0;
        lastEndedNotifyNanos = 0;
        pendingSeekSeconds.set(null);
        loggedUnsupportedAudio = false;
        loggedAudioDead = false;

        Thread thread = new Thread(this::runPlayback, "ffmpeg-hls");
        thread.setDaemon(true);
        playbackThread = thread;
        thread.start();
        log.info("HLS pipeline started uri={}", playlistUri);
    }

    void stop() {
        userStopped.set(true);
        stopRequested.set(true);
        paused.set(false);
        ended.set(false);
        pendingSeekSeconds.set(null);
        Thread thread = playbackThread;
        playbackThread = null;
        uri = null;
        playlistDurationSeconds = 0;
        positionBaseSeconds = 0;
        positionAnchorNanos = 0;
        lastGrabberPositionSeconds = 0;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        closeDisplayAndAudio();
    }

    void pause() {
        if (!isActive() || ended.get()) {
            return;
        }
        positionBaseSeconds = currentPositionSeconds();
        paused.set(true);
        log.info("HLS paused at {}s", positionBaseSeconds);
    }

    void resume() {
        if (!isActive()) {
            return;
        }
        ended.set(false);
        paused.set(false);
        positionAnchorNanos = System.nanoTime();
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
        lastGrabberPositionSeconds = positionSeconds;
        pendingSeekSeconds.set(positionSeconds);
        log.info("HLS seek requested to {}s", positionSeconds);
    }

    void noteMediaDuration(double seconds) {
        if (seconds > playlistDurationSeconds) {
            playlistDurationSeconds = seconds;
        }
    }

    void setVolume(double volume) {
        volumeLinear = clampVolume(volume);
    }

    boolean isActive() {
        return uri != null || (playbackThread != null && playbackThread.isAlive());
    }

    boolean isPaused() {
        return paused.get() || ended.get();
    }

    double currentPositionSeconds() {
        if (ended.get()) {
            double dur = durationSeconds();
            return dur > 0 ? dur : positionBaseSeconds;
        }
        if (paused.get()) {
            return positionBaseSeconds;
        }
        double playlist = playlistDurationSeconds;
        double grabber = lastGrabberPositionSeconds;
        if (grabber > 0 && (playlist <= 0 || grabber <= playlist + 1.0)) {
            return grabber;
        }
        if (positionAnchorNanos == 0) {
            return positionBaseSeconds;
        }
        double elapsed = (System.nanoTime() - positionAnchorNanos) / 1_000_000_000.0;
        double wall = Math.max(0, positionBaseSeconds + elapsed);
        return playlist > 0 ? Math.min(wall, playlist) : wall;
    }

    double durationSeconds() {
        if (playlistDurationSeconds > 0) {
            return playlistDurationSeconds;
        }
        return 0;
    }

    /** For smoke tests: whether the PCM ffplay child is still alive. */
    boolean audioSinkAlive() {
        FfplayPcmSink sink = pcmSink;
        return sink != null && sink.isAlive();
    }

    private void runPlayback() {
        long deadline = System.currentTimeMillis() + PREFETCH_RETRY_BUDGET_MS;
        boolean opened = false;
        while (!stopRequested.get() && !userStopped.get() && System.currentTimeMillis() < deadline) {
            try {
                playOnce();
                opened = true;
                break;
            } catch (Exception e) {
                if (stopRequested.get() || userStopped.get()) {
                    break;
                }
                log.warn("HLS open/play failed, retrying: {}", e.toString());
                sleepQuiet(PREFETCH_RETRY_MS);
            }
        }
        if (!opened && !userStopped.get() && uri != null) {
            log.error("HLS failed to start within {}ms for {}", PREFETCH_RETRY_BUDGET_MS, uri);
        }
    }

    private void playOnce() throws Exception {
        String playlistUri = uri;
        if (playlistUri == null) {
            return;
        }
        boolean headless = Boolean.parseBoolean(System.getProperty("airplay.ffmpeg.hls.headless", "false"));
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(playlistUri);
        grabber.setOption("reconnect", "1");
        grabber.setOption("reconnect_streamed", "1");
        grabber.setOption("reconnect_delay_max", "2");
        // Prefer interleaved S16 so SourceDataLine can play without float conversion.
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        try {
            grabber.start();
        } catch (Exception e) {
            try {
                grabber.release();
            } catch (Exception ignored) {
                // ignore
            }
            throw e;
        }

        Java2DFrameConverter converter = new Java2DFrameConverter();
        JFrame display = null;
        Canvas canvas = null;
        if (!headless) {
            canvas = FfmpegFullscreenWindow.createVideoCanvas();
            display = FfmpegFullscreenWindow.create(canvas);
            FfmpegFullscreenWindow.show(display);
            window = display;
            videoCanvas = canvas;
            ensureBufferStrategy(canvas);
        }

        try {
            openAudioLine(grabber);
            Double initialSeek = pendingSeekSeconds.getAndSet(null);
            if (initialSeek != null && initialSeek > 0.05) {
                applySeek(grabber, initialSeek);
            }

            long startNs = System.nanoTime();
            long framesSeen = 0;
            long firstPtsUs = -1;
            long paceOriginNs = System.nanoTime();
            while (!stopRequested.get() && !userStopped.get()) {
                if (paused.get()) {
                    sleepQuiet(40);
                    paceOriginNs = System.nanoTime();
                    firstPtsUs = -1;
                    continue;
                }
                Double seek = pendingSeekSeconds.getAndSet(null);
                if (seek != null) {
                    applySeek(grabber, seek);
                    firstPtsUs = -1;
                    paceOriginNs = System.nanoTime();
                }

                Frame media = grabber.grab();
                if (media == null) {
                    markEndedAndRefresh("EOS");
                    break;
                }
                framesSeen++;
                updatePositionFromGrabber(grabber);

                if (media.image != null && canvas != null) {
                    BufferedImage image = converter.convert(media);
                    if (image != null) {
                        paintFrame(canvas, image);
                    }
                }
                if (media.samples != null) {
                    writeAudio(media);
                }

                // Pace to media clock so we don't burn CPU decoding ahead of realtime.
                long ptsUs = media.timestamp > 0 ? media.timestamp : grabber.getTimestamp();
                if (ptsUs > 0) {
                    if (firstPtsUs < 0) {
                        firstPtsUs = ptsUs;
                        paceOriginNs = System.nanoTime();
                    } else {
                        long mediaUs = ptsUs - firstPtsUs;
                        long wallUs = (System.nanoTime() - paceOriginNs) / 1_000L;
                        long delayUs = mediaUs - wallUs;
                        if (delayUs > 2_000L) {
                            sleepQuiet(Math.min(delayUs / 1_000L, 40));
                        }
                    }
                }

                double dur = durationSeconds();
                if (dur > 0 && currentPositionSeconds() >= dur - 0.05) {
                    markEndedAndRefresh("ENDLIST");
                    break;
                }

                // Prefetch race: empty open that never demuxes — bail to retry.
                if (framesSeen == 0 && System.nanoTime() - startNs > 2_000_000_000L
                        && grabber.getLengthInTime() <= 0 && playlistDurationSeconds <= 0) {
                    throw new IllegalStateException("HLS produced no frames within 2s");
                }
            }
        } finally {
            closeAudioLine();
            closeWindow(display);
            try {
                grabber.stop();
            } catch (Exception e) {
                log.debug("grabber.stop failed: {}", e.toString());
            }
            try {
                grabber.release();
            } catch (Exception e) {
                log.debug("grabber.release failed: {}", e.toString());
            }
            converter.close();
        }
    }

    private void applySeek(FFmpegFrameGrabber grabber, double seconds) {
        try {
            long micros = (long) (seconds * 1_000_000L);
            grabber.setTimestamp(micros);
            positionBaseSeconds = seconds;
            positionAnchorNanos = System.nanoTime();
            lastGrabberPositionSeconds = seconds;
            log.info("HLS seek applied to {}s", seconds);
        } catch (Exception e) {
            log.warn("HLS seek to {}s failed: {}", seconds, e.toString());
        }
    }

    private void updatePositionFromGrabber(FFmpegFrameGrabber grabber) {
        if (paused.get()) {
            return;
        }
        try {
            long ts = grabber.getTimestamp();
            if (ts > 0) {
                double seconds = ts / 1_000_000.0;
                lastGrabberPositionSeconds = seconds;
                positionBaseSeconds = seconds;
                positionAnchorNanos = System.nanoTime();
            }
        } catch (Exception ignored) {
            // keep wall clock
        }
    }

    private void openAudioLine(FFmpegFrameGrabber grabber) {
        int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 44_100;
        int channels = grabber.getAudioChannels() > 0 ? grabber.getAudioChannels() : 2;
        try {
            FfplayPcmSink sink = FfplayPcmSink.start(sampleRate, channels);
            synchronized (audioLock) {
                pcmSink = sink;
            }
        } catch (Exception e) {
            log.warn("HLS ffplay PCM sink unavailable: {}", e.toString());
        }
    }

    private void writeAudio(Frame media) {
        FfplayPcmSink sink;
        synchronized (audioLock) {
            sink = pcmSink;
        }
        if (sink == null || media.samples == null || media.samples.length == 0) {
            return;
        }
        byte[] pcm = toInterleavedS16(media.samples, media.audioChannels);
        if (pcm == null || pcm.length == 0) {
            return;
        }
        float vol = (float) volumeLinear;
        if (vol < 0.999f) {
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                short sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (sample * vol)));
                pcm[i] = (byte) sample;
                pcm[i + 1] = (byte) (sample >> 8);
            }
        }
        try {
            sink.write(pcm, 0, pcm.length);
        } catch (Exception e) {
            if (!loggedAudioDead) {
                loggedAudioDead = true;
                log.warn("HLS PCM write failed (ffplay dead?): {}", e.toString());
            }
        }
    }

    /**
     * Convert grabber sample buffers (interleaved S16, planar float, or planar S16) to little-endian S16 PCM.
     */
    private byte[] toInterleavedS16(Buffer[] samples, int channels) {
        Buffer first = samples[0];
        if (first instanceof ShortBuffer) {
            int ch = channels > 0 ? channels : samples.length;
            // Planar S16: one ShortBuffer per channel.
            if (samples.length >= ch && ch > 1 && samples.length > 1) {
                for (Buffer b : samples) {
                    if (!(b instanceof ShortBuffer)) {
                        return logUnsupported(first);
                    }
                    ((ShortBuffer) b).rewind();
                }
                int samplesPerChannel = ((ShortBuffer) samples[0]).remaining();
                byte[] bytes = new byte[samplesPerChannel * ch * 2];
                int o = 0;
                for (int i = 0; i < samplesPerChannel; i++) {
                    for (int c = 0; c < ch; c++) {
                        short sample = ((ShortBuffer) samples[c]).get(i);
                        bytes[o++] = (byte) sample;
                        bytes[o++] = (byte) (sample >> 8);
                    }
                }
                return bytes;
            }
            ShortBuffer shorts = (ShortBuffer) first;
            shorts.rewind();
            int remaining = shorts.remaining();
            byte[] bytes = new byte[remaining * 2];
            for (int i = 0; i < remaining; i++) {
                short sample = shorts.get();
                bytes[i * 2] = (byte) sample;
                bytes[i * 2 + 1] = (byte) (sample >> 8);
            }
            return bytes;
        }
        if (first instanceof FloatBuffer) {
            int ch = channels > 0 ? channels : samples.length;
            // Planar: one FloatBuffer per channel.
            if (samples.length >= ch && ch > 1) {
                for (Buffer b : samples) {
                    if (!(b instanceof FloatBuffer)) {
                        return logUnsupported(first);
                    }
                    ((FloatBuffer) b).rewind();
                }
                int samplesPerChannel = ((FloatBuffer) samples[0]).remaining();
                byte[] bytes = new byte[samplesPerChannel * ch * 2];
                int o = 0;
                for (int i = 0; i < samplesPerChannel; i++) {
                    for (int c = 0; c < ch; c++) {
                        float f = ((FloatBuffer) samples[c]).get(i);
                        short sample = (short) Math.max(Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, (int) (f * 32767.0f)));
                        bytes[o++] = (byte) sample;
                        bytes[o++] = (byte) (sample >> 8);
                    }
                }
                return bytes;
            }
            // Interleaved float in a single buffer.
            FloatBuffer floats = (FloatBuffer) first;
            floats.rewind();
            int remaining = floats.remaining();
            byte[] bytes = new byte[remaining * 2];
            for (int i = 0; i < remaining; i++) {
                float f = floats.get();
                short sample = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, (int) (f * 32767.0f)));
                bytes[i * 2] = (byte) sample;
                bytes[i * 2 + 1] = (byte) (sample >> 8);
            }
            return bytes;
        }
        if (first instanceof ByteBuffer bytes) {
            bytes.rewind();
            byte[] out = new byte[bytes.remaining()];
            bytes.get(out);
            return out;
        }
        return logUnsupported(first);
    }

    private byte[] logUnsupported(Buffer first) {
        if (!loggedUnsupportedAudio) {
            loggedUnsupportedAudio = true;
            log.warn("Unsupported HLS audio buffer type: {}", first == null ? "null" : first.getClass().getName());
        }
        return null;
    }

    private void closeAudioLine() {
        synchronized (audioLock) {
            if (pcmSink != null) {
                pcmSink.close();
                pcmSink = null;
            }
        }
    }

    private static void ensureBufferStrategy(Canvas canvas) {
        try {
            FfmpegFullscreenWindow.onEdt(() -> {
                if (canvas.getBufferStrategy() == null) {
                    canvas.createBufferStrategy(2);
                }
            });
        } catch (Exception e) {
            // paintFrame falls back to getGraphics()
        }
    }

    private static void paintFrame(Canvas canvas, BufferedImage image) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        BufferStrategy strategy = canvas.getBufferStrategy();
        Graphics2D g = strategy != null ? (Graphics2D) strategy.getDrawGraphics() : (Graphics2D) canvas.getGraphics();
        if (g == null) {
            return;
        }
        try {
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, w, h);
            double sx = (double) w / image.getWidth();
            double sy = (double) h / image.getHeight();
            double scale = Math.min(sx, sy);
            int dw = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int dh = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int x = (w - dw) / 2;
            int y = (h - dh) / 2;
            g.drawImage(image, x, y, dw, dh, null);
        } finally {
            g.dispose();
            if (strategy != null) {
                strategy.show();
            }
        }
    }

    private void closeWindow(JFrame frame) {
        if (frame == null) {
            return;
        }
        FfmpegFullscreenWindow.hide(frame);
        if (window == frame) {
            window = null;
            videoCanvas = null;
        }
    }

    private void closeDisplayAndAudio() {
        JFrame frame = window;
        window = null;
        videoCanvas = null;
        closeWindow(frame);
        closeAudioLine();
    }

    private void markEndedAndRefresh(String reason) {
        if (userStopped.get() || uri == null) {
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
            lastGrabberPositionSeconds = dur;
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
}
