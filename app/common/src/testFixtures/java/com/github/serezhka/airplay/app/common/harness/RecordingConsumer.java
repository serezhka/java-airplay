package com.github.serezhka.airplay.app.common.harness;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal consumer that records callbacks without touching media sinks. */
public final class RecordingConsumer implements Playback {

    private final AtomicReference<VideoStreamInfo> videoFormat = new AtomicReference<>();
    private final AtomicReference<AudioStreamInfo> audioFormat = new AtomicReference<>();
    private final AtomicInteger videoFrames = new AtomicInteger();
    private final AtomicInteger audioFrames = new AtomicInteger();
    private final AtomicReference<Double> volumeLinear = new AtomicReference<>(1.0);
    private final AtomicReference<Playback.Observer> observer = new AtomicReference<>(Playback.Observer.NONE);

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        videoFormat.set(videoStreamInfo);
    }

    @Override
    public void onVideo(byte[] bytes) {
        videoFrames.incrementAndGet();
    }

    @Override
    public void onVideoSrcDisconnect() {
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audioFormat.set(audioStreamInfo);
    }

    @Override
    public void onAudio(byte[] bytes) {
        audioFrames.incrementAndGet();
    }

    @Override
    public void onAudioSrcDisconnect() {
    }

    @Override
    public void onVolume(double volumeLinear) {
        this.volumeLinear.set(Math.max(0.0, Math.min(1.0, volumeLinear)));
    }

    @Override
    public double volume() {
        return volumeLinear.get();
    }

    @Override
    public void setObserver(Observer observer) {
        this.observer.set(observer == null ? Observer.NONE : observer);
    }

    /** Test helper: fire {@link Observer#onEnded()} if one was registered. */
    public void fireEnded() {
        observer.get().onEnded();
    }

    public VideoStreamInfo videoFormat() {
        return videoFormat.get();
    }

    public AudioStreamInfo audioFormat() {
        return audioFormat.get();
    }

    public int videoFrames() {
        return videoFrames.get();
    }

    public int audioFrames() {
        return audioFrames.get();
    }
}
