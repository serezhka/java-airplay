package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;

import java.util.concurrent.TimeUnit;

public interface Playback {

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    /**
     * Optional early hint that a client is pairing — MSE may start Chromium in the background
     * so VIDEO SETUP does not pay the full cold-browser cost. Must not block the pair response.
     * Native players no-op.
     */
    default void preparePresentation() {
    }

    /**
     * Optional gate after {@link #onVideoFormat}: MSE waits until the Chromium page is on
     * {@code /control} so the video port is not advertised into a black idle window.
     * Native players no-op. Does not block pair-setup / pair-verify.
     */
    default void awaitPresentationReady(long timeout, TimeUnit unit) throws InterruptedException {
    }

    void onVideo(byte[] bytes);

    /**
     * Mirror video stream ended (RTSP VIDEO TEARDOWN, or server-side handoff before HLS).
     * Not a full client disconnect — receivers must keep presentation UI ready for HLS or
     * a follow-up mirror SETUP.
     */
    void onVideoSrcDisconnect();

    /**
     * AirPlay client ended the presentation (full RTSP TEARDOWN with no stream list, or
     * VIDEO TEARDOWN while no HLS session is active). MSE may close Chromium here; stream
     * switches ({@link #onVideoSrcDisconnect}, {@link #onPlaylistRemoved}, {@code /stop})
     * must not.
     */
    default void onPresentationEnded() {
    }

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

    /**
     * HLS item / session cleared ({@code /stop}, {@code playlistRemove}, reverse event gone).
     * Mirror may follow without re-pairing — do not tear down presentation UI here.
     */
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
