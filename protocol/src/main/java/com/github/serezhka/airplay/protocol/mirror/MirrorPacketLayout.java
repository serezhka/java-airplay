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

    private MirrorPacketLayout() {
    }
}
