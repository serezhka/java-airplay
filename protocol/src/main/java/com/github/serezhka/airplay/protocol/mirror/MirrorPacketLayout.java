package com.github.serezhka.airplay.protocol.mirror;

/**
 * Fixed layout of the 128-byte AirPlay mirror TCP packet header.
 */
public final class MirrorPacketLayout {

    public static final int HEADER_BYTES = 128;

    /** Payload type: encrypted (or sealed) video AU. */
    public static final int PAYLOAD_VIDEO = 0;
    /** Payload type: codec config (often plaintext). */
    public static final int PAYLOAD_CODEC = 1;
    /** Payload type: streaming-report plist (never decrypt as video). */
    public static final int PAYLOAD_STREAMING_REPORT = 5;

    /** Seconds from 1900-01-01 to 1970-01-01 (NTP epoch offset). */
    public static final long NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L;

    private MirrorPacketLayout() {
    }
}
