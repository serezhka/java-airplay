package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;

/**
 * Decorator that times {@link #onVideo} / {@link #onAudio} and feeds {@link PlaybackMetrics}.
 * Keeps instrumentation out of production player classes.
 */
public final class InstrumentedConsumer implements AirPlayConsumer {

    private final AirPlayConsumer delegate;
    private final PlaybackMetrics metrics;

    public InstrumentedConsumer(AirPlayConsumer delegate, PlaybackMetrics metrics) {
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
    public void onMediaPlaylist(String playlistUri) {
        delegate.onMediaPlaylist(playlistUri);
    }

    @Override
    public void onMediaPlaylistRemove() {
        delegate.onMediaPlaylistRemove();
    }

    @Override
    public void onMediaPlaylistPause() {
        delegate.onMediaPlaylistPause();
    }

    @Override
    public void onMediaPlaylistResume() {
        delegate.onMediaPlaylistResume();
    }

    public AirPlayConsumer delegate() {
        return delegate;
    }
}
