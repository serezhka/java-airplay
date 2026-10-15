package com.github.serezhka.airplay.protocol.media;

public interface MediaStreamInfo {

    StreamType getStreamType();

    enum StreamType {
        AUDIO,
        VIDEO
    }
}
