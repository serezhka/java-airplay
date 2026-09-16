package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;

public interface AirPlayConsumer {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    void onAudioSrcDisconnect();

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

    default void onMediaPlaylistSeek(double positionSeconds) {
    }

    /** Linear volume in {@code [0, 1]}. RTSP dB is converted by the control layer. */
    default void onVolume(double volumeLinear) {
    }

    default double volume() {
        return 1.0;
    }

    default void onControlExchange(ControlExchange exchange) {
    }

    default PlaybackInfo playbackInfo() {
        return new PlaybackInfo(0, 0, 1);
    }

    /** {@code rate}: {@code 0} paused, {@code 1} playing ({@code /playback-info}, {@code /rate}). */
    record PlaybackInfo(double duration, double position, double rate) {
        public PlaybackInfo(double duration, double position) {
            this(duration, position, 1);
        }
    }
}
