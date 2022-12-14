package com.github.serezhka.airplay.lib;

public interface MediaStreamInfo {

    StreamType getStreamType();

    enum StreamType {
        AUDIO,
        VIDEO
    }
}
