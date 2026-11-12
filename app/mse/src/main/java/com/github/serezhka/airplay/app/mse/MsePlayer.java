package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.EndListDuration;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browser MSE player: Chromium + hls.js (HTTP-Live) + jmuxer (mirror H.264).
 * Mirror AAC-ELD / AAC-LC: fdk-aac WASM → Web Audio. Music ALAC: vendored pure-JS
 * decoder ({@code alac.js}). Tray cast uses a separate navigable Chromium window.
 *
 * <p>Chromium starts lazily on the first mirror/HLS/audio session (no boot window).
 * VIDEO SETUP awaits {@link #awaitPresentationReady} so the video port is advertised
 * only after {@code /control} (and preferably {@code /mirror}) is up; pairing stays
 * non-blocking. The page shows a loading spinner until the first decoded frame.
 * Chromium stays up across mirror↔HLS switches; it is closed only on
 * {@link #onPresentationEnded()} (real client disconnect / stop mirroring).
 */
@Slf4j
public class MsePlayer implements Playback, AutoCloseable {

    /** Same ASC as iOS AirPlay mirror / GStreamer aac-eld ({@code codec_data=f8e85000}). */
    private static final byte[] ASC_ELD_44100_2 = hex("f8e85000");
    private static final byte[] ASC_LC_44100_2 = hex("1210");

    private static final String[] ASSETS = {
            "player.html", "hls.min.js", "jmuxer.min.js",
            "fdk_aac.js", "fdk_aac.wasm", "FDK_AAC_NOTICE.txt",
            "alac.js", "ALAC_JS_NOTICE.txt"
    };

    /** Same ALACSpecificConfig as CAF / libav Music path (24 bytes after alac atom). */
    private static byte[] alacCookie(AudioStreamInfo info) {
        int frameLength = info != null && info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 352;
        int bitDepth = 16;
        int channels = 2;
        int sampleRate = sampleRate(info);
        // ALACSpecificConfig only (createALAC parseConfig); not the 36-byte QuickTime atom.
        java.nio.ByteBuffer cookie = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.BIG_ENDIAN);
        cookie.putInt(frameLength);
        cookie.put((byte) 0);
        cookie.put((byte) bitDepth);
        cookie.put((byte) 40);
        cookie.put((byte) 10);
        cookie.put((byte) 14);
        cookie.put((byte) channels);
        cookie.putShort((short) 0x00ff);
        cookie.putInt(0);
        cookie.putInt(0);
        cookie.putInt(sampleRate);
        return cookie.array();
    }

    private final MseMediaServer media;
    private final ChromiumHost chromium;
    private final int fps;
    private final boolean browserAvailable;
    private final ExecutorService browserExecutor;
    private final AtomicBoolean watchingPageReady = new AtomicBoolean();
    private volatile boolean browserFailed;
    private volatile Observer observer = Observer.NONE;
    private volatile AudioStreamInfo.CompressionType audioCompressionType;
    private volatile boolean audioConfigured;

    public MsePlayer() {
        this(60);
    }

    public MsePlayer(int fps) {
        this.fps = Math.max(1, fps);
        this.browserExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mse-chromium");
            t.setDaemon(true);
            return t;
        });
        try {
            Path assets = extractAssets();
            media = new MseMediaServer();
            media.start(assets);
            media.setOnEnded(() -> observer.onEnded());
            chromium = new ChromiumHost(media.pageUrl());
            browserAvailable = chromium.browserAvailable();
            if (!browserAvailable) {
                browserFailed = true;
                log.error("MSE: no Chromium/Chrome found. Install a browser or set "
                        + "-Dairplay.mse.browser=/path/to/chrome — receiver will advertise "
                        + "but mirror/HLS will not display until a browser is available");
            } else {
                log.info("MsePlayer ready page={} fps={} (Chromium starts on first session)",
                        media.pageUrl(), this.fps);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start MSE media server", e);
        }
    }

    /**
     * Kick Chromium without waiting. RTSP SETUP must return the video port immediately;
     * {@link MseMediaServer} buffers NALs and resyncs {@code startMirror} when /control connects.
     */
    private void ensureBrowserAsync() {
        if (browserFailed || !browserAvailable) {
            return;
        }
        browserExecutor.execute(this::ensureBrowser);
    }

    private void ensureBrowser() {
        if (browserFailed || !browserAvailable) {
            return;
        }
        try {
            if (chromium.isAlive() && media.hasControlClient()) {
                return;
            }
            if (!chromium.isAlive()) {
                media.resetPageReady();
                chromium.ensureAlive();
                watchPageReadyAsync();
                return;
            }
            // Alive without /control: still booting. Watchdog restarts only if stuck.
            watchPageReadyAsync();
        } catch (Exception e) {
            browserFailed = true;
            log.error("MSE failed to ensure Chromium: {}", e.toString());
        }
    }

    /**
     * Off the presentation executor: wait for {@code ready}. Restart at most once, and only while
     * a session is still active (avoid thrashing Chromium after the phone already stopped).
     */
    private void watchPageReadyAsync() {
        if (!watchingPageReady.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            boolean handoffRestart = false;
            try {
                if (media.awaitReady(45, TimeUnit.SECONDS) || media.hasControlClient()) {
                    return;
                }
                if (browserFailed || !chromium.isAlive()) {
                    return;
                }
                if (!media.isMirrorActive() && !media.isHlsActive()) {
                    log.info("MSE Chromium had no /control but session already ended — not restarting");
                    return;
                }
                log.warn("MSE Chromium alive without /control — restarting once");
                handoffRestart = true;
                browserExecutor.execute(() -> {
                    try {
                        media.resetPageReady();
                        chromium.restart();
                    } catch (Exception e) {
                        browserFailed = true;
                        log.error("MSE Chromium restart failed: {}", e.toString());
                    } finally {
                        watchingPageReady.set(false);
                        watchPageReadyAsync();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (!handoffRestart) {
                    watchingPageReady.set(false);
                }
            }
        }, "mse-chromium-ready");
        t.setDaemon(true);
        t.start();
    }

    private static Path extractAssets() throws Exception {
        Path dir = Files.createTempDirectory("airplay-mse-");
        dir.toFile().deleteOnExit();
        for (String name : ASSETS) {
            Path target = dir.resolve(name);
            try (InputStream in = MsePlayer.class.getResourceAsStream("/mse/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("Missing /mse/" + name);
                }
                try (OutputStream out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
            target.toFile().deleteOnExit();
        }
        return dir;
    }

    @Override
    public void preparePresentation() {
        // Pair-verify path: start Chromium early without blocking the pair response.
        ensureBrowserAsync();
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        // Kick Chromium off-thread; {@link #awaitPresentationReady} gates the video port.
        ensureBrowserAsync();
        media.stopHls();
        media.startMirror(fps);
        log.info("MSE mirror started for {} (browserReady={} controlClients={})",
                videoStreamInfo.getStreamConnectionId(),
                isBrowserReady(),
                media.hasControlClient());
    }

    /**
     * Block VIDEO SETUP (not pairing) until the page has /control and ideally /mirror,
     * so the phone pushes into a live jmuxer instead of a black idle window.
     */
    @Override
    public void awaitPresentationReady(long timeout, TimeUnit unit) throws InterruptedException {
        if (browserFailed || !browserAvailable) {
            return;
        }
        ensureBrowserAsync();
        long startNanos = System.nanoTime();
        boolean ready = media.awaitReady(timeout, unit);
        long usedNanos = System.nanoTime() - startNanos;
        long leftNanos = unit.toNanos(timeout) - usedNanos;
        if (ready && leftNanos > 0 && !media.hasMirrorClient()) {
            long mirrorWaitMs = Math.min(TimeUnit.NANOSECONDS.toMillis(leftNanos), 3_000L);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(mirrorWaitMs);
            while (System.nanoTime() < deadline && !media.hasMirrorClient()) {
                Thread.sleep(50);
            }
        }
        if (!ready && !media.hasControlClient()) {
            log.warn("MSE presentation not ready after {} {} — continuing VIDEO SETUP", timeout, unit);
        } else {
            log.info("MSE presentation ready control={} mirror={} ({} ms)",
                    media.hasControlClient(),
                    media.hasMirrorClient(),
                    (System.nanoTime() - startNanos) / 1_000_000L);
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        media.sendNal(bytes);
    }

    @Override
    public void onVideoSrcDisconnect() {
        // Stream end or mirror→HLS handoff — keep Chromium for the next mode.
        media.stopMirror();
        log.info("MSE mirror stopped");
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audioConfigured = false;
        audioCompressionType = audioStreamInfo == null ? null : audioStreamInfo.getCompressionType();
        if (audioCompressionType == null) {
            return;
        }
        ensureBrowserAsync();
        int rate = sampleRate(audioStreamInfo);
        int ch = channels(audioStreamInfo);
        switch (audioCompressionType) {
            case AAC_ELD -> {
                media.configureAac("aac-eld", rate, ch, ASC_ELD_44100_2);
                audioConfigured = true;
                log.info("MSE audio fdk-wasm AAC-ELD {}Hz x{}", rate, ch);
            }
            case AAC -> {
                media.configureAac("aac-lc", rate, ch, ASC_LC_44100_2);
                audioConfigured = true;
                log.info("MSE audio fdk-wasm AAC-LC {}Hz x{}", rate, ch);
            }
            case ALAC -> {
                media.configureAlac(rate, ch, alacCookie(audioStreamInfo));
                audioConfigured = true;
                log.info("MSE audio alac.js ALAC {}Hz x{}", rate, ch);
            }
            default -> log.warn("MSE unsupported audio compression {}", audioCompressionType);
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || !audioConfigured) {
            return;
        }
        if (audioCompressionType != AudioStreamInfo.CompressionType.AAC_ELD
                && audioCompressionType != AudioStreamInfo.CompressionType.AAC
                && audioCompressionType != AudioStreamInfo.CompressionType.ALAC) {
            return;
        }
        media.sendAudioAu(bytes, offset, length);
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioConfigured = false;
        audioCompressionType = null;
        media.configureAac("none", 0, 0, new byte[0]);
    }

    @Override
    public void onPlaylist(String playlistUri) {
        ensureBrowserAsync();
        audioConfigured = false;
        audioCompressionType = null;
        media.stopMirror();
        media.startHls(playlistUri);
        log.info("MSE HLS start {}", playlistUri);
    }

    @Override
    public void onPlaylistRemoved() {
        // /stop / playlistRemove — remirror may follow on the same AirPlay session.
        media.stopHls();
        log.info("MSE HLS removed");
    }

    @Override
    public void onPresentationEnded() {
        // Real client disconnect. Stop current A/V first; abort the Chromium kill only if a
        // concurrent onVideoFormat/onPlaylist already re-armed the page (mirror↔HLS race).
        browserExecutor.execute(() -> {
            try {
                media.stopMirror();
                media.stopHls();
                if (media.isMirrorActive() || media.isHlsActive()) {
                    log.info("MSE presentation end aborted — new session already active");
                    return;
                }
                chromium.stop();
                media.resetPageReady();
                watchingPageReady.set(false);
                log.info("MSE Chromium closed (presentation ended)");
            } catch (Exception e) {
                log.debug("MSE Chromium stop: {}", e.toString());
            }
        });
    }

    @Override
    public void onPlaylistContent(String playlistUri, String content) {
        if (playlistUri == null || content == null || !playlistUri.contains("mediadata.m3u8")) {
            return;
        }
        double sum = EndListDuration.sumSeconds(content);
        if (sum > 0) {
            media.noteMediaDuration(sum);
        }
    }

    @Override
    public void onPause() {
        media.pause();
    }

    @Override
    public void onResume() {
        media.resume();
    }

    @Override
    public void onSeek(double positionSeconds) {
        media.seek(positionSeconds);
    }

    @Override
    public void onVolume(double volumeLinear) {
        media.setVolume(volumeLinear);
        log.info("MSE volume {}", volumeLinear);
    }

    @Override
    public double volume() {
        return media.volume();
    }

    @Override
    public Info info() {
        if (!media.isHlsActive()) {
            return Info.idle();
        }
        MseMediaServer.PlaybackSnapshot clock = media.playback();
        return new Info(clock.duration(), clock.position(), clock.rate());
    }

    @Override
    public void setObserver(Observer observer) {
        this.observer = observer == null ? Observer.NONE : observer;
        media.setOnEnded(() -> this.observer.onEnded());
    }

    boolean isMirrorActive() {
        return media.isMirrorActive();
    }

    boolean isHlsActive() {
        return media.isHlsActive();
    }

    boolean isAudioDecoderOpen() {
        return audioConfigured;
    }

    boolean isBrowserReady() {
        return !browserFailed && browserAvailable
                && chromium.isAlive()
                && media.hasControlClient();
    }

    private static int sampleRate(AudioStreamInfo info) {
        if (info == null || info.getAudioFormat() == null) {
            return 44_100;
        }
        return switch (info.getAudioFormat()) {
            case AAC_ELD_44100_2, AAC_LC_44100_2, ALAC_44100_16_2 -> 44_100;
            default -> 44_100;
        };
    }

    private static int channels(AudioStreamInfo info) {
        return 2;
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Override
    public void close() {
        audioConfigured = false;
        browserExecutor.shutdownNow();
        try {
            chromium.close();
        } catch (Exception ignored) {
        }
        media.close();
    }
}
