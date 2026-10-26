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
 * Browser MSE player: Chromium + hls.js (HTTP-Live) + jmuxer (mirror H.264 Annex-B).
 * No GStreamer / FFmpeg dependency. System Chromium/Chrome is required (JavaFX WebView
 * has no MSE on Linux).
 * <p>
 * Current limits: mirror AAC-ELD and Music ALAC are silent in-browser; HLS AAC-LC comes
 * from playlist segments via hls.js.
 */
@Slf4j
public class MsePlayer implements Playback {

    private final MseMediaServer media;
    private final ChromiumHost chromium;
    private final int fps;
    private volatile boolean audioWarned;
    private volatile Observer observer = Observer.NONE;

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
        if (!audioWarned) {
            audioWarned = true;
            log.warn("MSE: mirror/Music audio ({}) is silent — no AAC-ELD/ALAC in-browser decode yet; "
                    + "HLS audio still works via hls.js", audioStreamInfo.getCompressionType());
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        // Mirror/Music audio not wired yet (see implementation plan).
    }

    @Override
    public void onAudioSrcDisconnect() {
    }

    @Override
    public void onPlaylist(String playlistUri) {
        ensureBrowser();
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
}
