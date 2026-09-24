package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

/**
 * Decorator that times {@link #onVideo} / {@link #onAudio} and feeds {@link PlaybackMetrics}.
 * Keeps instrumentation out of production player classes.
 */
public final class InstrumentedConsumer implements Playback {

    private final Playback delegate;
    private final PlaybackMetrics metrics;

    public InstrumentedConsumer(Playback delegate, PlaybackMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        delegate.onVideoFormat(videoStreamInfo);
    }

    @Override
    public void onVideo(byte[] bytes) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            delegate.onVideo(bytes);
            ok = true;
        } finally {
            metrics.recordVideoWrite(System.nanoTime() - start, bytes != null ? bytes.length : 0, ok);
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        delegate.onVideoSrcDisconnect();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        delegate.onAudioFormat(audioStreamInfo);
    }

    @Override
    public void onAudio(byte[] bytes) {
        delegate.onAudio(bytes);
    }

    @Override
    public void onAudioSrcDisconnect() {
        delegate.onAudioSrcDisconnect();
    }

    @Override
    public void onPlaylist(String playlistUri) {
        delegate.onPlaylist(playlistUri);
    }

    @Override
    public void onPlaylistRemoved() {
        delegate.onPlaylistRemoved();
    }

    @Override
    public void onPause() {
        delegate.onPause();
    }

    @Override
    public void onResume() {
        delegate.onResume();
    }

    @Override
    public void onSeek(double positionSeconds) {
        delegate.onSeek(positionSeconds);
    }

    @Override
    public void onVolume(double volumeLinear) {
        delegate.onVolume(volumeLinear);
    }

    @Override
    public double volume() {
        return delegate.volume();
    }

    public Playback delegate() {
        return delegate;
    }
}
