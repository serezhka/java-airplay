package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.decode.LibavAacDecoder;
import com.github.serezhka.airplay.app.mse.decode.LibavAlacDecoder;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.EndListDuration;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Browser MSE player: Chromium + hls.js (HTTP-Live) + jmuxer (mirror H.264).
 * Mirror/Music audio is decoded with bundled libav (AAC-ELD / AAC-LC / ALAC) to PCM
 * and played in the page via Web Audio — browsers cannot decode AirPlay AAC-ELD natively.
 *
 * <p>Chromium is started lazily on the first mirror/HLS/audio session so boot does not
 * open a black idle window. Without a system browser the receiver still runs (mDNS/tray)
 * but playback is a no-op with a clear log.
 */
@Slf4j
public class MsePlayer implements Playback, AutoCloseable {

    private final MseMediaServer media;
    private final ChromiumHost chromium;
    private final int fps;
    private final boolean browserAvailable;
    private volatile boolean browserFailed;
    private volatile Observer observer = Observer.NONE;
    private volatile AudioStreamInfo.CompressionType audioCompressionType;
    private volatile LibavAacDecoder aacDecoder;
    private volatile LibavAlacDecoder alacDecoder;

    public MsePlayer() {
        this(60);
    }

    public MsePlayer(int fps) {
        this.fps = Math.max(1, fps);
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

    private void ensureBrowser() {
        if (browserFailed || !browserAvailable) {
            return;
        }
        try {
            if (chromium.isAlive() && media.hasControlClient()) {
                return;
            }
            // Reset the ready latch BEFORE launching Chrome. Otherwise the page can emit
            // "ready" on the old latch, then resetPageReady() leaves us waiting forever and
            // startMirror is delayed past the first IDR (black picture after window close).
            media.resetPageReady();
            if (!chromium.isAlive()) {
                chromium.ensureAlive();
            } else {
                chromium.restart();
            }
            if (!media.awaitReady(15, TimeUnit.SECONDS)) {
                if (media.hasControlClient()) {
                    log.info("MSE Chromium control connected without ready latch; continuing");
                } else {
                    log.warn("MSE Chromium relaunched but page did not signal ready");
                }
            }
        } catch (Exception e) {
            browserFailed = true;
            log.error("MSE failed to ensure Chromium: {}", e.toString());
        }
    }

    private static Path extractAssets() throws Exception {
        Path dir = Files.createTempDirectory("airplay-mse-");
        dir.toFile().deleteOnExit();
        for (String name : new String[]{"player.html", "hls.min.js", "jmuxer.min.js"}) {
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
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        ensureBrowser();
        // Mirror tears HLS on the shared <video> element (same as GST/FFmpeg shared display).
        media.stopHls();
        media.startMirror(fps);
        chromium.setPresentationActive(true);
        log.info("MSE mirror started for {}", videoStreamInfo.getStreamConnectionId());
    }

    @Override
    public void onVideo(byte[] bytes) {
        media.sendNal(bytes);
    }

    @Override
    public void onVideoSrcDisconnect() {
        media.stopMirror();
        if (!media.isHlsActive()) {
            chromium.setPresentationActive(false);
        }
        log.info("MSE mirror stopped");
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        closeAudioDecoders();
        audioCompressionType = audioStreamInfo == null ? null : audioStreamInfo.getCompressionType();
        if (audioCompressionType == null) {
            return;
        }
        ensureBrowser();
        try {
            switch (audioCompressionType) {
                case AAC_ELD, AAC -> {
                    aacDecoder = new LibavAacDecoder(audioStreamInfo);
                    media.configureAudio(aacDecoder.sampleRate(), aacDecoder.channels());
                    log.info("MSE audio decoder {} {}Hz x{}",
                            audioCompressionType, aacDecoder.sampleRate(), aacDecoder.channels());
                }
                case ALAC -> {
                    alacDecoder = new LibavAlacDecoder(audioStreamInfo);
                    media.configureAudio(alacDecoder.sampleRate(), alacDecoder.channels());
                    log.info("MSE audio decoder ALAC {}Hz x{}",
                            alacDecoder.sampleRate(), alacDecoder.channels());
                }
                default -> log.warn("MSE unsupported audio compression {}", audioCompressionType);
            }
        } catch (Exception e) {
            log.error("MSE failed to open audio decoder {}: {}", audioCompressionType, e.toString());
            closeAudioDecoders();
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        onAudio(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    @Override
    public void onAudio(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || audioCompressionType == null) {
            return;
        }
        try {
            byte[] packet = bytes;
            if (offset != 0 || length != bytes.length) {
                packet = new byte[length];
                System.arraycopy(bytes, offset, packet, 0, length);
            }
            switch (audioCompressionType) {
                case AAC_ELD, AAC -> {
                    LibavAacDecoder dec = aacDecoder;
                    if (dec != null) {
                        dec.decode(packet, media::sendPcm);
                    }
                }
                case ALAC -> {
                    LibavAlacDecoder dec = alacDecoder;
                    if (dec != null) {
                        dec.decode(packet, media::sendPcm);
                    }
                }
                default -> {
                }
            }
        } catch (Exception e) {
            log.debug("MSE audio decode failed: {}", e.toString());
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        closeAudioDecoders();
        audioCompressionType = null;
    }

    @Override
    public void onPlaylist(String playlistUri) {
        ensureBrowser();
        closeAudioDecoders();
        // Match GST/FFmpeg: do not tear mirror here — onVideoFormat / onVideoSrcDisconnect
        // own that. Only stop local mirror sink so the shared <video> can show HLS.
        media.stopMirror();
        media.startHls(playlistUri);
        chromium.setPresentationActive(true);
        log.info("MSE HLS start {}", playlistUri);
    }

    @Override
    public void onPlaylistRemoved() {
        // Keep the Chromium window up: YouTube often remirrors the phone UI right after
        // stopping the VOD item. Minimizing here left a black gap (unlike GST last-frame).
        media.stopHls();
        log.info("MSE HLS removed");
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

    /** Package-visible for the playback lifecycle harness. */
    boolean isMirrorActive() {
        return media.isMirrorActive();
    }

    boolean isHlsActive() {
        return media.isHlsActive();
    }

    boolean isAudioDecoderOpen() {
        return aacDecoder != null || alacDecoder != null;
    }

    boolean isBrowserReady() {
        return !browserFailed && browserAvailable
                && chromium.isAlive()
                && media.hasControlClient();
    }

    @Override
    public void setObserver(Observer observer) {
        this.observer = observer == null ? Observer.NONE : observer;
        media.setOnEnded(() -> this.observer.onEnded());
    }

    private void closeAudioDecoders() {
        LibavAacDecoder aac = aacDecoder;
        aacDecoder = null;
        if (aac != null) {
            aac.close();
        }
        LibavAlacDecoder alac = alacDecoder;
        alacDecoder = null;
        if (alac != null) {
            alac.close();
        }
    }

    @Override
    public void close() {
        closeAudioDecoders();
        try {
            chromium.close();
        } catch (Exception ignored) {
        }
        media.close();
    }
}
