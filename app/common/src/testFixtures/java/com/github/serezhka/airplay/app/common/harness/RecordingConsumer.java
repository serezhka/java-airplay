package com.github.serezhka.airplay.app.common.harness;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal consumer that records callbacks without touching media sinks. */
public final class RecordingConsumer implements Playback {

    private final AtomicReference<VideoStreamInfo> videoFormat = new AtomicReference<>();
    private final AtomicReference<AudioStreamInfo> audioFormat = new AtomicReference<>();
    private final AtomicInteger videoFrames = new AtomicInteger();
    private final AtomicInteger audioFrames = new AtomicInteger();
    private final AtomicInteger videoDisconnects = new AtomicInteger();
    private final AtomicInteger audioDisconnects = new AtomicInteger();
    private final AtomicReference<Double> volumeLinear = new AtomicReference<>(1.0);
    private final AtomicReference<Playback.Observer> observer = new AtomicReference<>(Playback.Observer.NONE);
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        videoFormat.set(videoStreamInfo);
        events.add("videoFormat");
    }

    @Override
    public void onVideo(byte[] bytes) {
        videoFrames.incrementAndGet();
        events.add("video");
    }

    @Override
    public void onVideoSrcDisconnect() {
        videoDisconnects.incrementAndGet();
        events.add("videoDisconnect");
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audioFormat.set(audioStreamInfo);
        events.add("audioFormat");
    }

    @Override
    public void onAudio(byte[] bytes) {
        audioFrames.incrementAndGet();
        events.add("audio");
    }

    @Override
    public void onAudioSrcDisconnect() {
        audioDisconnects.incrementAndGet();
        events.add("audioDisconnect");
    }

    @Override
    public void onVolume(double volumeLinear) {
        this.volumeLinear.set(Math.max(0.0, Math.min(1.0, volumeLinear)));
        events.add("volume");
    }

    @Override
    public double volume() {
        return volumeLinear.get();
    }

    @Override
    public void onPause() {
        events.add("pause");
    }

    @Override
    public void onResume() {
        events.add("resume");
    }

    @Override
    public void onSeek(double positionSeconds) {
        events.add("seek");
    }

    @Override
    public void onPlaylist(String playlistUri) {
        events.add("playlist");
    }

    @Override
    public void onPlaylistRemoved() {
        events.add("playlistRemoved");
    }

    @Override
    public void onPlaylistContent(String playlistUri, String content) {
        events.add("playlistContent");
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

    public int videoDisconnects() {
        return videoDisconnects.get();
    }

    public int audioDisconnects() {
        return audioDisconnects.get();
    }

    /** Ordered control/media callback names (not every video/audio frame when bulk-fed). */
    public List<String> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public boolean eventsContainInOrder(String... expected) {
        List<String> seen = events();
        int from = 0;
        for (String needle : expected) {
            int idx = seen.subList(from, seen.size()).indexOf(needle);
            if (idx < 0) {
                return false;
            }
            from += idx + 1;
        }
        return true;
    }
}
