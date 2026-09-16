package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public interface AirPlayConsumer {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    void onAudioSrcDisconnect();

    // HLS stuff, youtube
    default void onMediaPlaylist(String playlistUri) {
    }

    default void onMediaPlaylistContent(String playlistUri, String content) {
    }

    default void onMediaPlaylistRemove() {
    }

    default void onMediaPlaylistPause() {
    }

    default void onMediaPlaylistResume() {
    }

    /**
     * Seek media (HLS / YouTube) to {@code positionSeconds}.
     */
    default void onMediaPlaylistSeek(double positionSeconds) {
    }

    /**
     * Linear volume in {@code [0, 1]} (HTTP {@code /play} and UI). RTSP dB values are converted by the control layer.
     */
    default void onVolume(double volumeLinear) {
    }

    default double volume() {
        return 1.0;
    }

    default void onControlExchange(ControlExchange exchange) {
    }

    default PlaybackInfo playbackInfo() {
        return new PlaybackInfo(0, 0);
    }

    record PlaybackInfo(double duration, double position) {
    }
}
