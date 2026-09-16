package com.github.serezhka.airplay.server.internal.handler.util;

/**
 * AirPlay RTSP volume is typically dB in roughly {@code [-30, 0]} (mute often {@code -144}).
 * HTTP {@code /play} uses linear {@code [0, 1]}.
 */
public final class AirPlayVolume {

    private AirPlayVolume() {
    }

    public static double clampLinear(double linear) {
        if (Double.isNaN(linear)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, linear));
    }

    /** Convert RTSP dB volume to linear gain in {@code [0, 1]}. */
    public static double fromDecibels(double db) {
        if (db <= -144.0) {
            return 0.0;
        }
        double clamped = Math.max(-30.0, Math.min(0.0, db));
        return clampLinear(Math.pow(10.0, clamped / 20.0));
    }

    /** Convert linear gain to RTSP dB for {@code GET_PARAMETER}. */
    public static double toDecibels(double linear) {
        double v = clampLinear(linear);
        if (v <= 1e-6) {
            return -144.0;
        }
        return 20.0 * Math.log10(v);
    }

    public static String formatRtspParameter(double linear) {
        return String.format("volume: %.6f\r\n", toDecibels(linear));
    }
}
