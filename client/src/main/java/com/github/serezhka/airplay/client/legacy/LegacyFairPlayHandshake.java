package com.github.serezhka.airplay.client.legacy;

import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayHandshake;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Legacy pair-setup / pair-verify / fp-setup handshake used by older AirPlay receivers.
 * Does not fall back to HomeKit-style TLV pairing.
 */
public final class LegacyFairPlayHandshake {

    /** FairPlay fixture ekey used by legacy FPAS senders after successful fp-setup. */
    public static final byte[] FIXTURE_EKEY = new byte[]{
            70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
            105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
            -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
            15, -45};

    private static final byte[] FP_SETUP_1 = new byte[]{
            70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69};
    private static final byte[] FP_SETUP_2 = new byte[]{
            70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6,
            -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123,
            -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128,
            -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76,
            54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92,
            1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76,
            -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50,
            -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};

    private LegacyFairPlayHandshake() {
    }

    /**
     * Pair-verify ECDH secret plus the AES key the receiver will unwrap from {@code ekey}.
     * The plaintext AES key must match the peer's FairPlay unwrap — a hardcoded constant drifts.
     */
    public record Result(byte[] sharedSecret, byte[] aesKey) {
    }

    /**
     * Completes legacy pairing and FairPlay setup on an already-connected control channel,
     * then locally unwraps {@link #FIXTURE_EKEY} with the same fp-setup state the peer has.
     *
     * @throws IllegalStateException if the receiver rejects legacy pair-setup or pair-verify
     */
    public static Result perform(ControlClient controlClient) throws Exception {
        return perform(controlClient, FIXTURE_EKEY);
    }

    public static Result perform(ControlClient controlClient, byte[] encryptedAesKey) throws Exception {
        byte[] pairSetupResponseBytes = controlClient.pairSetup();
        if (pairSetupResponseBytes.length < 32) {
            throw new LegacyPairingRejectedException(
                    "Receiver rejected legacy POST /pair-setup (got " + pairSetupResponseBytes.length
                            + " bytes). Modern AirPlay receivers require a PIN / HomeKit-style pairing.");
        }

        KeyPair keyPair = new KeyPairGenerator().generateKeyPair();
        Curve25519 curve25519 = Curve25519.getInstance(Curve25519.BEST);
        Curve25519KeyPair curve25519KeyPair = curve25519.generateKeyPair();
        byte[] pairVerify1Request = new byte[68];
        pairVerify1Request[0] = 1;
        System.arraycopy(curve25519KeyPair.getPublicKey(), 0, pairVerify1Request, 4, 32);
        System.arraycopy(((EdDSAPublicKey) keyPair.getPublic()).getAbyte(), 0, pairVerify1Request, 36, 32);
        byte[] pairVerify1Response = controlClient.pairVerify(pairVerify1Request);
        if (pairVerify1Response.length < 96) {
            throw new IllegalStateException(
                    "Receiver rejected legacy POST /pair-verify (got " + pairVerify1Response.length
                            + " bytes). Expected 96-byte M2 from a legacy FairPlay receiver; "
                            + "no protocol fallback.");
        }

        byte[] atvPublicKey = Arrays.copyOfRange(pairVerify1Response, 0, 32);
        byte[] sharedSecret = curve25519.calculateAgreement(atvPublicKey, curve25519KeyPair.getPrivateKey());

        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        sha512Digest.update(AirPlayKeyLabels.PAIR_VERIFY_AES_KEY.getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(sharedSecret);
        byte[] sharedSecretSha512AesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);
        sha512Digest.update(AirPlayKeyLabels.PAIR_VERIFY_AES_IV.getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(sharedSecret);
        byte[] sharedSecretSha512AesIV = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        Cipher aesCtr128Encrypt = Cipher.getInstance("AES/CTR/NoPadding");
        aesCtr128Encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecretSha512AesKey, "AES"),
                new IvParameterSpec(sharedSecretSha512AesIV));
        aesCtr128Encrypt.update(Arrays.copyOfRange(pairVerify1Response, 32, 96));

        EdDSAEngine edDSAEngine = new EdDSAEngine();
        edDSAEngine.initSign(keyPair.getPrivate());
        byte[] dataToSign = new byte[64];
        System.arraycopy(curve25519KeyPair.getPublicKey(), 0, dataToSign, 0, 32);
        System.arraycopy(atvPublicKey, 0, dataToSign, 32, 32);
        byte[] signature = aesCtr128Encrypt.update(edDSAEngine.signOneShot(dataToSign));

        byte[] pairVerify2Request = new byte[68];
        System.arraycopy(signature, 0, pairVerify2Request, 4, 64);
        controlClient.pairVerify(pairVerify2Request);

        controlClient.fpSetup(FP_SETUP_1);
        controlClient.fpSetup(FP_SETUP_2);

        return new Result(sharedSecret, unwrapAesKey(encryptedAesKey));
    }

    /**
     * Replay the same fp-setup messages locally and unwrap {@code ekey}.
     * Must match the receiver's {@code FairPlaySession.getFairPlayAesKey()} after those messages.
     */
    public static byte[] unwrapAesKey(byte[] encryptedAesKey) throws Exception {
        FairPlayHandshake local = new FairPlayHandshake();
        local.fairPlaySetup(new ByteArrayInputStream(FP_SETUP_1), new ByteArrayOutputStream());
        local.fairPlaySetup(new ByteArrayInputStream(FP_SETUP_2), new ByteArrayOutputStream());
        return local.decryptAesKey(encryptedAesKey);
    }
}
