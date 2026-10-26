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
 */
@Slf4j
public class MsePlayer implements Playback, AutoCloseable {

    private final MseMediaServer media;
    private final ChromiumHost chromium;
    private final int fps;
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
            if (!media.awaitReady(25, TimeUnit.SECONDS)) {
                log.warn("MSE Chromium page did not signal ready within timeout; continuing");
            }
            log.info("MsePlayer ready page={} fps={}", media.pageUrl(), this.fps);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start MSE Chromium player", e);
        }
    }

    private void ensureBrowser() {
        try {
            boolean relaunched;
            if (!chromium.isAlive()) {
                relaunched = chromium.ensureAlive();
            } else if (!media.hasControlClient()) {
                chromium.restart();
                relaunched = true;
            } else {
                return;
            }
            if (relaunched) {
                media.resetPageReady();
                if (!media.awaitReady(15, TimeUnit.SECONDS)) {
                    log.warn("MSE Chromium relaunched but page did not signal ready");
                }
            }
        } catch (Exception e) {
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
        chromium.setPresentationActive(false);
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
        media.stopMirror();
        media.startHls(playlistUri);
        chromium.setPresentationActive(true);
        log.info("MSE HLS start {}", playlistUri);
    }

    @Override
    public void onPlaylistRemoved() {
        media.stopHls();
        chromium.setPresentationActive(false);
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
