package com.github.serezhka.airplay.protocol.crypto;

/**
 * Well-known HKDF / SHA-512 labels used across AirPlay control and media crypto.
 * <p>
 * Sourced from public reverse-engineering notes of the wire protocol (not Apple docs).
 */
public final class AirPlayKeyLabels {

    /** FairPlay AES-CTR video: SHA-512 prefix before {@code streamConnectionID}. */
    public static final String STREAM_KEY = "AirPlayStreamKey";
    public static final String STREAM_IV = "AirPlayStreamIV";

    /** Legacy pair-verify AES wrap (pre-HKP). */
    public static final String PAIR_VERIFY_AES_KEY = "Pair-Verify-AES-Key";
    public static final String PAIR_VERIFY_AES_IV = "Pair-Verify-AES-IV";

    /** HKP pair-setup ChaCha session (SRP shared → encrypt M5/M6 inner TLVs). */
    public static final String PAIR_SETUP_ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt";
    public static final String PAIR_SETUP_ENCRYPT_INFO = "Pair-Setup-Encrypt-Info";
    public static final String PAIR_SETUP_CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt";
    public static final String PAIR_SETUP_CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info";
    public static final String PAIR_SETUP_ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt";
    public static final String PAIR_SETUP_ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info";

    /** HKP pair-verify ChaCha session (X25519 shared → encrypt M2/M3 inner TLVs). */
    public static final String PAIR_VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt";
    public static final String PAIR_VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info";

    /** Control-channel ChaCha after pair-verify (or after transient M4). */
    public static final String CONTROL_SALT = "Control-Salt";
    public static final String CONTROL_WRITE_KEY = "Control-Write-Encryption-Key";
    public static final String CONTROL_READ_KEY = "Control-Read-Encryption-Key";

    /** Mirror video payload ChaCha after pair-verify (header is AAD). */
    public static final String DATA_STREAM_SALT_PREFIX = "DataStream-Salt";
    public static final String DATA_STREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key";

    /** ChaCha nonces: ASCII at offset 4 (12-byte nonce). */
    public static final String PS_MSG_05 = "PS-Msg05";
    public static final String PS_MSG_06 = "PS-Msg06";
    public static final String PV_MSG_02 = "PV-Msg02";
    public static final String PV_MSG_03 = "PV-Msg03";

    /** SRP identity string for HKP pair-setup. */
    public static final String PAIR_SETUP_SRP_USERNAME = "Pair-Setup";

    /** Default PIN when the receiver allows transient access without an on-screen code. */
    public static final String TRANSIENT_DEFAULT_PIN = "3939";

    private AirPlayKeyLabels() {
    }

    /** {@code DataStream-Salt} + unsigned decimal {@code streamConnectionID}. */
    public static String dataStreamSalt(long streamConnectionId) {
        return DATA_STREAM_SALT_PREFIX + Long.toUnsignedString(streamConnectionId);
    }
}
