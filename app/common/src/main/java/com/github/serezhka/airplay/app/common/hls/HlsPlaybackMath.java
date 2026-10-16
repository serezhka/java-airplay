package com.github.serezhka.airplay.app.common.hls;

/**
 * Shared numeric helpers for HLS pipelines (GStreamer / FFmpeg).
 * Keeps seek/volume clamps identical across backends.
 */
public final class HlsPlaybackMath {

    /** Reject seeks beyond one week — usually a clock / demux glitch. */
    public static final double ABSURD_SEEK_SECONDS = 86_400.0 * 7;

    private HlsPlaybackMath() {
    }

    public static double clampVolume(double volumeLinear) {
        return Math.max(0.0, Math.min(1.0, volumeLinear));
    }

    public static boolean isAbsurdSeek(double positionSeconds) {
        return Double.isNaN(positionSeconds)
                || Double.isInfinite(positionSeconds)
                || positionSeconds < 0
                || positionSeconds > ABSURD_SEEK_SECONDS;
    }
}
