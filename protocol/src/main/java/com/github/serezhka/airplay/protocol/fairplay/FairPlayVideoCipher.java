package com.github.serezhka.airplay.protocol.fairplay;

import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * FairPlay AES-CTR over mirror video payloads (sender and receiver share this schedule).
 * <p>
 * Master material: empty {@code sharedSecret} → {@code aesKey} is the stream master;
 * otherwise {@code SHA-512(aesKey ‖ sharedSecret)[:16]}. Then
 * {@link AirPlayKeyLabels#STREAM_KEY}/{@link AirPlayKeyLabels#STREAM_IV} + unsigned
 * {@code streamConnectionID}. Residual keystream spans packets ({@code og} buffer).
 * <p>
 * CTR is symmetric — {@link #crypt(byte[])} is used for both encrypt and decrypt.
 * Payloads are expected to be at least as long as any pending residual (senders
 * pack that way).
 */
public final class FairPlayVideoCipher {

    private final Cipher aesCtr;
    private final byte[] og = new byte[16];
    private int nextCount;

    public FairPlayVideoCipher(byte[] aesKey, byte[] sharedSecret, String streamConnectionID)
            throws Exception {
        aesCtr = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] secret = sharedSecret == null ? new byte[0] : sharedSecret;
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        deriveStreamKeyIv(aesKey, secret, streamConnectionID, key, iv);
        // ENCRYPT_MODE == DECRYPT_MODE for CTR; either works for crypt().
        aesCtr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    }

    /** In-place AES-CTR with cross-packet residual (FairPlay video). */
    public void crypt(byte[] video) throws Exception {
        if (nextCount > 0) {
            for (int i = 0; i < nextCount; i++) {
                video[i] = (byte) (video[i] ^ og[(16 - nextCount) + i]);
            }
        }

        int encryptlen = ((video.length - nextCount) / 16) * 16;
        aesCtr.update(video, nextCount, encryptlen, video, nextCount);

        int restlen = (video.length - nextCount) % 16;
        int reststart = video.length - restlen;
        nextCount = 0;
        if (restlen > 0) {
            Arrays.fill(og, (byte) 0);
            System.arraycopy(video, reststart, og, 0, restlen);
            aesCtr.update(og, 0, 16, og, 0);
            System.arraycopy(og, 0, video, reststart, restlen);
            nextCount = 16 - restlen;
        }
    }

    /**
     * Stream key/IV for FairPlay AES-CTR (and for continuous CTR with empty shared secret).
     */
    public static void deriveStreamKeyIv(
            byte[] aesKey,
            byte[] sharedSecret,
            String streamConnectionID,
            byte[] keyOut16,
            byte[] ivOut16) throws Exception {
        byte[] eaesKeyMaterial;
        if (sharedSecret == null || sharedSecret.length == 0) {
            eaesKeyMaterial = aesKey;
        } else {
            MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
            sha512Digest.update(aesKey);
            sha512Digest.update(sharedSecret);
            eaesKeyMaterial = sha512Digest.digest();
        }

        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        byte[] skey = (AirPlayKeyLabels.STREAM_KEY + streamConnectionID).getBytes(StandardCharsets.UTF_8);
        sha512Digest.update(skey);
        sha512Digest.update(eaesKeyMaterial, 0, 16);
        System.arraycopy(sha512Digest.digest(), 0, keyOut16, 0, 16);

        byte[] siv = (AirPlayKeyLabels.STREAM_IV + streamConnectionID).getBytes(StandardCharsets.UTF_8);
        sha512Digest.update(siv);
        sha512Digest.update(eaesKeyMaterial, 0, 16);
        System.arraycopy(sha512Digest.digest(), 0, ivOut16, 0, 16);
    }
}
