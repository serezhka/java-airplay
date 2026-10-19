package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;

public interface Playback {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onVideo(byte[] bytes);

    void onVideoSrcDisconnect();

    void onAudioFormat(AudioStreamInfo audioStreamInfo);

    void onAudio(byte[] bytes);

    /**
     * Audio AU with an explicit range. Default copies into a tight array and calls {@link #onAudio(byte[])}.
     * Hot-path players should override to avoid the copy when they can consume a slice.
     */
    default void onAudio(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) {
            return;
        }
        if (offset == 0 && length == bytes.length) {
            onAudio(bytes);
            return;
        }
        byte[] copy = new byte[length];
        System.arraycopy(bytes, offset, copy, 0, length);
        onAudio(copy);
    }

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

        /** First picture is on the display. Until then the sender stays on {@code loading}. */
        default void onPresented() {
        }

        /** A seek has a picture. Tell the sender {@code playing} at the real position. */
        default void onSeekDisplayed() {
        }

        Observer NONE = new Observer() {
            @Override
            public void onEnded() {
            }
        };
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
