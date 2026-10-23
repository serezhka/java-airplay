package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Test double for Control → {@link Playback} wiring (server module; no app/common dep). */
final class RecordingPlayback implements Playback {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Double> volumeLinear = new AtomicReference<>(1.0);
    private final AtomicReference<Double> lastSeek = new AtomicReference<>();
    private final AtomicReference<String> lastPlaylist = new AtomicReference<>();
    private final AtomicReference<Info> info = new AtomicReference<>(Info.idle());

    @Override
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        events.add("videoFormat");
    }

    @Override
    public void onVideo(byte[] bytes) {
        events.add("video");
    }

    @Override
    public void onVideoSrcDisconnect() {
        events.add("videoDisconnect");
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        events.add("audioFormat");
    }

    @Override
    public void onAudio(byte[] bytes) {
        events.add("audio");
    }

    @Override
    public void onAudioSrcDisconnect() {
        events.add("audioDisconnect");
    }

    @Override
    public void onPlaylist(String playlistUri) {
        lastPlaylist.set(playlistUri);
        events.add("playlist");
    }

    @Override
    public void onPlaylistRemoved() {
        events.add("playlistRemoved");
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
        lastSeek.set(positionSeconds);
        events.add("seek");
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
    public Info info() {
        return info.get();
    }

    void setInfo(Info info) {
        this.info.set(info == null ? Info.idle() : info);
    }

    List<String> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    void clearEvents() {
        events.clear();
    }

    boolean eventsContainInOrder(String... expected) {
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

    Double lastSeek() {
        return lastSeek.get();
    }

    String lastPlaylist() {
        return lastPlaylist.get();
    }
}
