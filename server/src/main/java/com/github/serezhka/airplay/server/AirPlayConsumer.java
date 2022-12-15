package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;

public interface AirPlayConsumer {

    void onVideo(byte[] video);

    void onVideoFormat(VideoStreamInfo videoStreamInfo);

    void onAudio(byte[] audio);

    void onAudioFormat(AudioStreamInfo audioInfo);
}
