package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.ControlExchange;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DumpingAirPlayConsumer implements Playback {

    private final Playback player;
    private final DumpPlayer dump;

    public DumpingAirPlayConsumer(Playback player, DumpPlayer dump) {
        this.player = player;
        this.dump = dump;
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        player.onVideoFormat(videoStreamInfo);
        dumpSafely(() -> dump.onVideoFormat(videoStreamInfo));
    }

    @Override
    public void onVideo(byte[] bytes) {
        player.onVideo(bytes);
        dumpSafely(() -> dump.onVideo(bytes));
    }

    @Override
    public void onVideoSrcDisconnect() {
        player.onVideoSrcDisconnect();
        dumpSafely(dump::onVideoSrcDisconnect);
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        player.onAudioFormat(audioStreamInfo);
        dumpSafely(() -> dump.onAudioFormat(audioStreamInfo));
    }

    @Override
    public void onAudio(byte[] bytes) {
        player.onAudio(bytes);
        dumpSafely(() -> dump.onAudio(bytes));
    }

    @Override
    public void onAudioSrcDisconnect() {
        player.onAudioSrcDisconnect();
        dumpSafely(dump::onAudioSrcDisconnect);
    }

    @Override
    public void setObserver(Playback.Observer observer) {
        player.setObserver(observer);
    }

    @Override
    public void onPlaylist(String playlistUri) {
        player.onPlaylist(playlistUri);
        dumpSafely(() -> dump.onPlaylist(playlistUri));
    }

    @Override
    public void onPlaylistContent(String playlistUri, String content) {
        player.onPlaylistContent(playlistUri, content);
        dumpSafely(() -> dump.onPlaylistContent(playlistUri, content));
    }

    @Override
    public void onPlaylistRemoved() {
        player.onPlaylistRemoved();
        dumpSafely(dump::onPlaylistRemoved);
    }

    @Override
    public void onPause() {
        player.onPause();
        dumpSafely(dump::onPause);
    }

    @Override
    public void onResume() {
        player.onResume();
        dumpSafely(dump::onResume);
    }

    @Override
    public void onSeek(double positionSeconds) {
        player.onSeek(positionSeconds);
    }

    @Override
    public void onVolume(double volumeLinear) {
        player.onVolume(volumeLinear);
    }

    @Override
    public double volume() {
        return player.volume();
    }

    @Override
    public void onControlExchange(ControlExchange exchange) {
        player.onControlExchange(exchange);
        dumpSafely(() -> dump.onControlExchange(exchange));
    }

    @Override
    public Playback.Info info() {
        return player.info();
    }

    @PreDestroy
    public void close() {
        dump.close();
    }

    private void dumpSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Dump sidecar failed", e);
        }
    }
}
