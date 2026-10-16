package com.github.serezhka.airplay.protocol.media;

/**
 * RTSP SETUP {@code streams[].type} values used on the wire.
 */
public final class AirPlayStreamType {

    /** Realtime audio (ALAC / AAC). */
    public static final int AUDIO_REALTIME = 96;
    /** Buffered audio (AirPlay 2). */
    public static final int AUDIO_BUFFERED = 103;
    /** Screen-mirroring H.264 video. */
    public static final int SCREEN = 110;

    private AirPlayStreamType() {
    }
}
