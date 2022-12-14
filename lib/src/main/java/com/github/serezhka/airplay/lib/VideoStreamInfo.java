package com.github.serezhka.airplay.lib;

public class VideoStreamInfo implements MediaStreamInfo {

    private final String streamConnectionID;

    public VideoStreamInfo(String streamConnectionID) {
        this.streamConnectionID = streamConnectionID;
    }

    @Override
    public StreamType getStreamType() {
        return MediaStreamInfo.StreamType.VIDEO;
    }

    public String getStreamConnectionID() {
        return streamConnectionID;
    }
}
