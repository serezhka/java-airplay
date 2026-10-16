package com.github.serezhka.airplay.protocol.discovery;

/**
 * Bits of mDNS TXT {@code flags} / {@code sf} (receiver system state).
 */
public final class AirPlayStatusFlags {

    public static final int PROBLEM_DETECTED = 1 << 0;
    public static final int NOT_CONFIGURED = 1 << 1;
    public static final int AUDIO_CABLE_ATTACHED = 1 << 2;
    /** On-screen / one-shot PIN expected for pairing. */
    public static final int PIN_REQUIRED = 1 << 3;
    public static final int SUPPORTS_AIRPLAY_FROM_CLOUD = 1 << 6;
    /** Password (or equivalent) required to connect. */
    public static final int PASSWORD_REQUIRED = 1 << 7;
    public static final int ONE_TIME_PAIRING_REQUIRED = 1 << 9;
    public static final int HK_ACCESS_CONTROL_CONFIGURED = 1 << 10;
    public static final int DEVICE_SUPPORTS_RELAY = 1 << 11;
    public static final int RECEIVER_SESSION_ACTIVE = 1 << 17;

    private AirPlayStatusFlags() {
    }

    public static boolean passwordRequired(long flags) {
        return (flags & PASSWORD_REQUIRED) != 0;
    }

    public static boolean pinRequired(long flags) {
        return (flags & PIN_REQUIRED) != 0 || (flags & ONE_TIME_PAIRING_REQUIRED) != 0;
    }

    /** Parse TXT hex ({@code 0x…} or bare hex). Returns 0 on missing/invalid. */
    public static long parse(String hex) {
        if (hex == null || hex.isBlank()) {
            return 0L;
        }
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) {
            h = h.substring(2);
        }
        // Some stacks publish two words; take the low word if comma-separated.
        int comma = h.indexOf(',');
        if (comma > 0) {
            h = h.substring(0, comma);
        }
        try {
            return Long.parseLong(h, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
