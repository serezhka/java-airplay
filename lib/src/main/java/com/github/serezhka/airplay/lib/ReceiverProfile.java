package com.github.serezhka.airplay.lib;

/**
 * Receiver advertisement profile (mDNS TXT + {@code GET /info}).
 * <p>
 * Historic receiver mask used when YouTube HLS support was first wired:
 * {@code 0x5A7FFFF7,0x1E} (same value as {@code GET /info} features
 * {@code 130367356919}).
 */
public final class ReceiverProfile {

    public static final int FEATURES_LO = 0x5A7FFFF7;
    public static final int FEATURES_HI = 0x1E;

    /** 64-bit features for {@code GET /info} plist. */
    public static final long FEATURES = ((long) FEATURES_HI << 32) | (FEATURES_LO & 0xFFFFFFFFL);

    public static final String FEATURES_TXT = String.format("0x%X,0x%X", FEATURES_LO, FEATURES_HI);

    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    public static final int STATUS_FLAGS = 0x44;
    public static final String MDNS_FLAGS = "0x44";
    public static final int VV = 2;

    private ReceiverProfile() {
    }

    public static String airTunesServerHeader() {
        return "AirTunes/" + SOURCE_VERSION;
    }
}
