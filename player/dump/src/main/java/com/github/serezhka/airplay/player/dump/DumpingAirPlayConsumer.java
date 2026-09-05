package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.ControlExchange;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DumpingAirPlayConsumer implements AirPlayConsumer {

    private final AirPlayConsumer player;
    private final DumpPlayer dump;

    public DumpingAirPlayConsumer(AirPlayConsumer player, DumpPlayer dump) {
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
    public void onMediaPlaylist(String playlistUri) {
        player.onMediaPlaylist(playlistUri);
        dumpSafely(() -> dump.onMediaPlaylist(playlistUri));
    }

    @Override
    public void onMediaPlaylistContent(String playlistUri, String content) {
        player.onMediaPlaylistContent(playlistUri, content);
        dumpSafely(() -> dump.onMediaPlaylistContent(playlistUri, content));
    }

    @Override
    public void onMediaPlaylistRemove() {
        player.onMediaPlaylistRemove();
        dumpSafely(dump::onMediaPlaylistRemove);
    }

    @Override
    public void onMediaPlaylistPause() {
        player.onMediaPlaylistPause();
        dumpSafely(dump::onMediaPlaylistPause);
    }

    @Override
    public void onMediaPlaylistResume() {
        player.onMediaPlaylistResume();
        dumpSafely(dump::onMediaPlaylistResume);
    }

    @Override
    public void onControlExchange(ControlExchange exchange) {
        player.onControlExchange(exchange);
        dumpSafely(() -> dump.onControlExchange(exchange));
    }

    @Override
    public PlaybackInfo playbackInfo() {
        return player.playbackInfo();
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
