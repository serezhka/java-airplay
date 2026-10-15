package com.github.serezhka.airplay.protocol.mirror;

/**
 * Names advertised in mirror video SETUP {@code timestampInfo}.
 * Receivers use these labels when correlating NTP timing with frame stamps.
 */
public final class MirrorTimestampInfo {

    public static final String[] NAMES = {
            "SubSu",
            "BePxT",
            "AfPxT",
            "BefEn",
            "EmEnc",
    };

    private MirrorTimestampInfo() {
    }
}
