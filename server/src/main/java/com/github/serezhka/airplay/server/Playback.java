package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;

public interface Playback {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    void onAudioSrcDisconnect();

    default void onPlaylist(String playlistUri) {
    }

    default void onPlaylistContent(String playlistUri, String content) {
    }

    default void onPlaylistRemoved() {
    }

    default void onPause() {
    }

    default void onResume() {
    }

    default void onSeek(double positionSeconds) {
    }

    /** Linear volume in {@code [0, 1]}. RTSP dB is converted by the control layer. */
    default void onVolume(double volumeLinear) {
    }

    default double volume() {
        return 1.0;
    }

    default void onControlExchange(ControlExchange exchange) {
    }

    default Playback.Info info() {
        return Playback.Info.idle();
    }

    /** Server registers one observer. The player calls {@link Observer#onEnded()} at VOD end. */
    default void setObserver(Observer observer) {
    }

    interface Observer {
        void onEnded();

        Observer NONE = () -> {};
    }

    /** {@code rate}: {@code 0} paused, {@code 1} playing ({@code /playback-info}, {@code /rate}). */
    record Info(double duration, double position, double rate) {
        public Info(double duration, double position) {
            this(duration, position, 1);
        }

        public static Info idle() {
            return new Info(0, 0, 1);
        }
    }
}
