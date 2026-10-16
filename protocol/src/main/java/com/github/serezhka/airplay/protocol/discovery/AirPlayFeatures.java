package com.github.serezhka.airplay.protocol.discovery;

/**
 * Bits of mDNS TXT {@code features} / {@code ft} (64-bit capability mask).
 * Encoding on the wire: {@code 0xLOWER,0xUPPER} → value {@code (UPPER << 32) | LOWER}.
 */
public final class AirPlayFeatures {

    public static final int VIDEO = 0;
    public static final int PHOTO = 1;
    public static final int SCREEN = 7;
    public static final int AUDIO = 9;
    public static final int FPSAP_V2_5_AES_GCM = 12;
    public static final int AUTHENTICATION_4 = 14;
    public static final int HAS_UNIFIED_ADVERTISER_INFO = 26;
    public static final int SUPPORTS_LEGACY_PAIRING = 27;
    public static final int RAOP = 30;
    public static final int SUPPORTS_BUFFERED_AUDIO = 40;
    public static final int SUPPORTS_PTP = 41;
    public static final int SUPPORTS_SYSTEM_PAIRING = 43;
    public static final int SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL = 46;
    public static final int SUPPORTS_TRANSIENT_PAIRING = 48;
    public static final int SUPPORTS_AIRPLAY_VIDEO_V2 = 49;
    public static final int SUPPORTS_UNIFIED_PAIR_SETUP_AND_MFI = 51;

    private AirPlayFeatures() {
    }

    public static boolean has(long features, int bit) {
        return (features & (1L << bit)) != 0;
    }

    /**
     * Prefer HomeKit-style pairing when the receiver advertises HK / transient pairing
     * and does not advertise legacy pairing.
     */
    public static boolean prefersHomeKitPairing(long features) {
        boolean hk = has(features, SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL)
                || has(features, SUPPORTS_TRANSIENT_PAIRING)
                || has(features, SUPPORTS_SYSTEM_PAIRING);
        boolean legacy = has(features, SUPPORTS_LEGACY_PAIRING);
        return hk && !legacy;
    }

    /** Parse {@code 0xLOWER,0xUPPER} or a single hex word. Returns 0 on missing/invalid. */
    public static long parse(String txt) {
        if (txt == null || txt.isBlank()) {
            return 0L;
        }
        String[] parts = txt.trim().split(",");
        try {
            long lower = parseWord(parts[0]);
            long upper = parts.length > 1 ? parseWord(parts[1]) : 0L;
            return (upper << 32) | (lower & 0xFFFFFFFFL);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseWord(String word) {
        String h = word.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) {
            h = h.substring(2);
        }
        return Long.parseLong(h, 16) & 0xFFFFFFFFL;
    }
}
