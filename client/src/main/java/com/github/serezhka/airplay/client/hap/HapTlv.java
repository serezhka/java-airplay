package com.github.serezhka.airplay.client.hap;

/** HAP / AirPlay pairing TLV type and constant codes. */
public final class HapTlv {

    public static final int METHOD = 0x00;
    public static final int IDENTIFIER = 0x01;
    public static final int SALT = 0x02;
    public static final int PUBLIC_KEY = 0x03;
    public static final int PROOF = 0x04;
    public static final int ENCRYPTED_DATA = 0x05;
    public static final int STATE = 0x06;
    public static final int ERROR = 0x07;
    public static final int RETRY_DELAY = 0x08;
    public static final int CERTIFICATE = 0x09;
    public static final int SIGNATURE = 0x0A;
    public static final int PERMISSIONS = 0x0B;
    public static final int FLAGS = 0x13;

    public static final int METHOD_PAIR_SETUP = 0x00;
    public static final int METHOD_PAIR_SETUP_WITH_AUTH = 0x01;
    public static final int METHOD_PAIR_VERIFY = 0x02;

    public static final int STATE_M1 = 0x01;
    public static final int STATE_M2 = 0x02;
    public static final int STATE_M3 = 0x03;
    public static final int STATE_M4 = 0x04;
    public static final int STATE_M5 = 0x05;
    public static final int STATE_M6 = 0x06;

    public static final int ERROR_AUTHENTICATION = 0x02;
    public static final int ERROR_BACKOFF = 0x03;
    public static final int ERROR_MAX_PEERS = 0x04;
    public static final int ERROR_MAX_TRIES = 0x05;
    public static final int ERROR_UNAVAILABLE = 0x06;
    public static final int ERROR_BUSY = 0x07;

    /** Pairing flag: transient session (no long-term identity exchange). */
    public static final int FLAG_TRANSIENT = 0x10;

    private HapTlv() {
    }

    public static String errorName(int code) {
        return switch (code) {
            case 0x01 -> "unknown";
            case ERROR_AUTHENTICATION -> "authentication";
            case ERROR_BACKOFF -> "backoff";
            case ERROR_MAX_PEERS -> "maxPeers";
            case ERROR_MAX_TRIES -> "maxTries";
            case ERROR_UNAVAILABLE -> "unavailable";
            case ERROR_BUSY -> "busy";
            default -> "0x" + Integer.toHexString(code);
        };
    }
}
