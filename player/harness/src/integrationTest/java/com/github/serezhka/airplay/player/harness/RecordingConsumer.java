package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal consumer that records callbacks without touching media sinks. */
public final class RecordingConsumer implements AirPlayConsumer {

    private final AtomicReference<VideoStreamInfo> videoFormat = new AtomicReference<>();
    private final AtomicReference<AudioStreamInfo> audioFormat = new AtomicReference<>();
    private final AtomicInteger videoFrames = new AtomicInteger();
    private final AtomicInteger audioFrames = new AtomicInteger();

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
