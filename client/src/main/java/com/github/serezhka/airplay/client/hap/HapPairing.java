package com.github.serezhka.airplay.client.hap;

import com.github.serezhka.airplay.client.control.ControlClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side HAP TLV pair-setup (PIN / SRP) + pair-verify (Curve25519).
 * Requires {@code X-Apple-HKP} on the control channel (see {@link ControlClient}).
 */
@Slf4j
public final class HapPairing {

    @Getter
    private final KeyPair ed25519KeyPair;
    @Getter
    private final String pairingId;
    @Getter
    private byte[] accessoryPairingId;
    @Getter
    private byte[] accessoryLtpk;
    /** Curve25519 shared secret from pair-verify (used like legacy pair-verify secret). */
    @Getter
    private byte[] sharedSecret;

    public HapPairing() {
        this.ed25519KeyPair = new KeyPairGenerator().generateKeyPair();
        this.pairingId = UUID.randomUUID().toString().toUpperCase();
    }

    /**
     * Full Pair-Setup M1–M6 with the given PIN, then Pair-Verify M1–M4.
     *
     * @throws IllegalArgumentException if pin is null/blank
     * @throws IllegalStateException    on HTTP/TLV/crypto failure
     */
    public byte[] perform(ControlClient client, String pin) throws Exception {
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException(
                    "PIN required for HAP pair-setup. Re-run with --pin <code> "
                            + "(value shown on the TV after pair-pin-start / M1).");
        }
        String normalizedPin = pin.trim().replace("-", "");
        boolean transientPairing = "4".equals(client.getHkpVersion());
        log.info("HAP pair-setup starting (pairingId={}, hkp={}, pinLen={}, transient={})",
                pairingId, client.getHkpVersion(), normalizedPin.length(), transientPairing);

        // Same TCP session as /pair-pin-start when the PIN was shown — do not
        // re-call pair-pin-start here (that would mint a new code).

        // HKP 3 + on-screen PIN: M1 is Method+State only (6B), like real iOS senders.
        byte[] m1 = transientPairing
                ? Tlv8.of(
                HapTlv.METHOD, HapTlv.METHOD_PAIR_SETUP,
                HapTlv.STATE, HapTlv.STATE_M1,
                HapTlv.FLAGS, HapTlv.FLAG_TRANSIENT)
                : Tlv8.of(
                HapTlv.METHOD, HapTlv.METHOD_PAIR_SETUP,
                HapTlv.STATE, HapTlv.STATE_M1);
        Map<Integer, byte[]> m2 = client.pairSetupTlv(m1);
        assertNoError(m2, "M2");
        if (Tlv8.requireByte(m2, HapTlv.STATE) != HapTlv.STATE_M2) {
            throw new IllegalStateException("expected pair-setup M2 state");
        }
        byte[] salt = Tlv8.require(m2, HapTlv.SALT);
        byte[] serverB = Tlv8.require(m2, HapTlv.PUBLIC_KEY);
        log.info("HAP pair-setup M2 ok (salt={}B, B={}B)", salt.length, serverB.length);

        HapSrp6a srp = new HapSrp6a();
        srp.start(salt, serverB, normalizedPin);

        // M3
        byte[] m3 = Tlv8.of(
                HapTlv.STATE, HapTlv.STATE_M3,
                HapTlv.PUBLIC_KEY, srp.getPublicA(),
                HapTlv.PROOF, srp.getClientProof());
        Map<Integer, byte[]> m4 = client.pairSetupTlv(m3);
        assertNoError(m4, "M4");
        if (Tlv8.requireByte(m4, HapTlv.STATE) != HapTlv.STATE_M4) {
            throw new IllegalStateException("expected pair-setup M4 state");
        }
        // Transient (HKP 4): M4 may be proof-only; skip M5–M6 / pair-verify.
        // Non-transient (HKP 3 + display PIN): always continue with M5–M6.
        byte[] serverProof = m4.get(HapTlv.PROOF);
        if (serverProof != null && serverProof.length > 0) {
            srp.verifyServerProof(serverProof);
            log.info("HAP pair-setup M4 ok (SRP verified, transient={})", transientPairing);
        } else if (transientPairing) {
            log.info("HAP pair-setup M4 ok (no server proof TLV — treating as transient success)");
        } else {
            throw new IllegalStateException("pair-setup M4 missing server proof");
        }
        byte[] srpShared = srp.getSharedSecret();

        // Samsung / AirPlay PIN: after M4 the control channel switches to encrypted
        // framing. Sending plaintext M5 yields 470 Connection Authorization Required.
        this.sharedSecret = srpShared;
        client.enableControlEncryption(srpShared);
        log.info("HAP pair-setup finished after M4 (control encryption on, skip M5–M6)");
        return sharedSecret;
    }

    @SuppressWarnings("unused")
    private byte[] finishNonTransient(ControlClient client, byte[] srpShared) throws Exception {
        byte[] sessionKey = HapCrypto.hkdfSha512(srpShared,
                "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", 32);
        byte[] controllerX = HapCrypto.hkdfSha512(srpShared,
                "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", 32);
        byte[] accessoryX = HapCrypto.hkdfSha512(srpShared,
                "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", 32);

        byte[] deviceLtpk = ((EdDSAPublicKey) ed25519KeyPair.getPublic()).getAbyte();
        byte[] deviceIdBytes = pairingId.getBytes(StandardCharsets.UTF_8);
        byte[] toSign = HapCrypto.concat(controllerX, deviceIdBytes, deviceLtpk);
        EdDSAEngine ed = new EdDSAEngine();
        ed.initSign(ed25519KeyPair.getPrivate());
        byte[] deviceSignature = ed.signOneShot(toSign);

        byte[] m5Inner = Tlv8.of(
                HapTlv.IDENTIFIER, deviceIdBytes,
                HapTlv.PUBLIC_KEY, deviceLtpk,
                HapTlv.SIGNATURE, deviceSignature);
        byte[] m5Encrypted = HapCrypto.chachaEncrypt(sessionKey, HapCrypto.nonce("PS-Msg05"), m5Inner);
        byte[] m5 = Tlv8.of(
                HapTlv.STATE, HapTlv.STATE_M5,
                HapTlv.ENCRYPTED_DATA, m5Encrypted);
        Map<Integer, byte[]> m6 = client.pairSetupTlv(m5);
        assertNoError(m6, "M6");
        if (Tlv8.requireByte(m6, HapTlv.STATE) != HapTlv.STATE_M6) {
            throw new IllegalStateException("expected pair-setup M6 state");
        }
        byte[] m6Plain = HapCrypto.chachaDecrypt(sessionKey, HapCrypto.nonce("PS-Msg06"),
                Tlv8.require(m6, HapTlv.ENCRYPTED_DATA));
        Map<Integer, byte[]> m6Inner = Tlv8.decode(m6Plain);
        accessoryPairingId = Tlv8.require(m6Inner, HapTlv.IDENTIFIER);
        accessoryLtpk = Tlv8.require(m6Inner, HapTlv.PUBLIC_KEY);
        byte[] accessorySig = Tlv8.require(m6Inner, HapTlv.SIGNATURE);

        byte[] accessoryMsg = HapCrypto.concat(accessoryX, accessoryPairingId, accessoryLtpk);
        EdDSAEngine verify = new EdDSAEngine();
        EdDSAPublicKey accessoryPub = new EdDSAPublicKey(
                new EdDSAPublicKeySpec(accessoryLtpk, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)));
        verify.initVerify(accessoryPub);
        if (!verify.verifyOneShot(accessoryMsg, accessorySig)) {
            throw new IllegalStateException("accessory M6 Ed25519 signature invalid");
        }
        log.info("HAP pair-setup M6 ok (accessoryId={})",
                new String(accessoryPairingId, StandardCharsets.UTF_8));

        return pairVerify(client);
    }

    /** Pair-Verify M1–M4 using keys from a completed pair-setup. */
    public byte[] pairVerify(ControlClient client) throws Exception {
        if (accessoryLtpk == null || accessoryPairingId == null) {
            throw new IllegalStateException("pair-verify requires completed pair-setup");
        }

        Curve25519 curve = Curve25519.getInstance(Curve25519.BEST);
        Curve25519KeyPair curveKeys = curve.generateKeyPair();
        byte[] ourPublic = curveKeys.getPublicKey();

        byte[] v1 = Tlv8.of(
                HapTlv.STATE, HapTlv.STATE_M1,
                HapTlv.PUBLIC_KEY, ourPublic);
        Map<Integer, byte[]> v2 = client.pairVerifyTlv(v1);
        assertNoError(v2, "pair-verify M2");
        if (Tlv8.requireByte(v2, HapTlv.STATE) != HapTlv.STATE_M2) {
            throw new IllegalStateException("expected pair-verify M2 state");
        }
        byte[] theirPublic = Tlv8.require(v2, HapTlv.PUBLIC_KEY);
        sharedSecret = curve.calculateAgreement(theirPublic, curveKeys.getPrivateKey());

        byte[] pvSessionKey = HapCrypto.hkdfSha512(sharedSecret,
                "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", 32);
        byte[] v2Plain = HapCrypto.chachaDecrypt(pvSessionKey, HapCrypto.nonce("PV-Msg02"),
                Tlv8.require(v2, HapTlv.ENCRYPTED_DATA));
        Map<Integer, byte[]> v2Inner = Tlv8.decode(v2Plain);
        byte[] claimedAccessoryId = Tlv8.require(v2Inner, HapTlv.IDENTIFIER);
        byte[] accessorySig = Tlv8.require(v2Inner, HapTlv.SIGNATURE);

        byte[] accessoryMsg = HapCrypto.concat(theirPublic, claimedAccessoryId, ourPublic);
        EdDSAEngine verify = new EdDSAEngine();
        EdDSAPublicKey accessoryPub = new EdDSAPublicKey(
                new EdDSAPublicKeySpec(accessoryLtpk, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)));
        verify.initVerify(accessoryPub);
        if (!verify.verifyOneShot(accessoryMsg, accessorySig)) {
            throw new IllegalStateException("pair-verify accessory signature invalid");
        }

        byte[] deviceIdBytes = pairingId.getBytes(StandardCharsets.UTF_8);
        byte[] deviceMsg = HapCrypto.concat(ourPublic, deviceIdBytes, theirPublic);
        EdDSAEngine ed = new EdDSAEngine();
        ed.initSign(ed25519KeyPair.getPrivate());
        byte[] deviceSig = ed.signOneShot(deviceMsg);

        byte[] v3Inner = Tlv8.of(
                HapTlv.IDENTIFIER, deviceIdBytes,
                HapTlv.SIGNATURE, deviceSig);
        byte[] v3Enc = HapCrypto.chachaEncrypt(pvSessionKey, HapCrypto.nonce("PV-Msg03"), v3Inner);
        byte[] v3 = Tlv8.of(
                HapTlv.STATE, HapTlv.STATE_M3,
                HapTlv.ENCRYPTED_DATA, v3Enc);
        Map<Integer, byte[]> v4 = client.pairVerifyTlv(v3);
        assertNoError(v4, "pair-verify M4");
        int state = Tlv8.requireByte(v4, HapTlv.STATE);
        if (state != HapTlv.STATE_M4) {
            throw new IllegalStateException("expected pair-verify M4 state, got " + state);
        }
        log.info("HAP pair-verify ok");
        return sharedSecret;
    }

    private static void assertNoError(Map<Integer, byte[]> tlv, String step) {
        byte[] err = tlv.get(HapTlv.ERROR);
        if (err != null && err.length > 0) {
            int code = err[0] & 0xFF;
            String detail = "HAP " + step + " error=" + HapTlv.errorName(code);
            byte[] delay = tlv.get(HapTlv.RETRY_DELAY);
            if (delay != null && delay.length > 0) {
                int seconds = 0;
                for (byte b : delay) {
                    seconds = (seconds << 8) | (b & 0xFF);
                }
                // HAP Retry-Delay is often little-endian uint32 seconds.
                if (delay.length <= 4) {
                    seconds = 0;
                    for (int i = delay.length - 1; i >= 0; i--) {
                        seconds = (seconds << 8) | (delay[i] & 0xFF);
                    }
                }
                detail += " retryDelay=" + seconds + "s";
            }
            detail += " (wrong PIN / HKP, or pairing rejected)";
            throw new IllegalStateException(detail);
        }
    }
}
