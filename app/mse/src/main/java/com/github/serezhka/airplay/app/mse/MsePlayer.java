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
import java.util.concurrent.TimeUnit;

/**
 * Browser MSE player: Chromium + hls.js (HTTP-Live) + jmuxer (mirror H.264).
 * Mirror AAC-ELD / AAC-LC is decoded in-page with bundled fdk-aac WASM → Web Audio
 * (browsers have no native AAC-ELD). ALAC / tray cast capture are not supported here.
 *
 * <p>Chromium starts lazily on the first mirror/HLS/audio session.
 */
@Slf4j
public class MsePlayer implements Playback, AutoCloseable {

    /** Same ASC as iOS AirPlay mirror / GStreamer aac-eld ({@code codec_data=f8e85000}). */
    private static final byte[] ASC_ELD_44100_2 = hex("f8e85000");
    private static final byte[] ASC_LC_44100_2 = hex("1210");

    private static final String[] ASSETS = {
            "player.html", "hls.min.js", "jmuxer.min.js",
            "fdk_aac.js", "fdk_aac.wasm", "FDK_AAC_NOTICE.txt"
    };

    private final MseMediaServer media;
    private final ChromiumHost chromium;
    private final int fps;
    private final boolean browserAvailable;
    private volatile boolean browserFailed;
    private volatile Observer observer = Observer.NONE;
    private volatile AudioStreamInfo.CompressionType audioCompressionType;
    private volatile boolean audioConfigured;

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
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        ensureBrowser();
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
        audioConfigured = false;
        audioCompressionType = audioStreamInfo == null ? null : audioStreamInfo.getCompressionType();
        if (audioCompressionType == null) {
            return;
        }
        ensureBrowser();
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
            case ALAC -> log.warn("MSE ALAC not supported (fdk-wasm path is AAC only); Music audio skipped");
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
                && audioCompressionType != AudioStreamInfo.CompressionType.AAC) {
            return;
        }
        media.sendAac(bytes, offset, length);
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioConfigured = false;
        audioCompressionType = null;
        media.configureAac("none", 0, 0, new byte[0]);
    }

    @Override
    public void onPlaylist(String playlistUri) {
        ensureBrowser();
        audioConfigured = false;
        audioCompressionType = null;
        media.stopMirror();
        media.startHls(playlistUri);
        chromium.setPresentationActive(true);
        log.info("MSE HLS start {}", playlistUri);
    }

    @Override
    public void onPlaylistRemoved() {
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
        try {
            chromium.close();
        } catch (Exception ignored) {
        }
        media.close();
    }
}
