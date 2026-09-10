package com.github.serezhka.airplay.client.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;

/** AES-CBC encryptor for AirPlay audio payloads (mirrors server-side decryptor). */
public class FairPlayAudioEncryptor {

    private final byte[] aesIV;
    private final byte[] eaesKey;
    private final Cipher aesCbcEncrypt;

    public FairPlayAudioEncryptor(byte[] aesKey, byte[] aesIV, byte[] sharedSecret) throws Exception {
        this.aesIV = aesIV;

        if (sharedSecret == null || sharedSecret.length == 0) {
            eaesKey = Arrays.copyOf(aesKey, 16);
        } else {
            MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
            sha512Digest.update(aesKey);
            sha512Digest.update(sharedSecret);
            eaesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);
        }

        aesCbcEncrypt = Cipher.getInstance("AES/CBC/NoPadding");
    }

    public void encrypt(byte[] audio, int audioLength) throws Exception {
        aesCbcEncrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(eaesKey, "AES"), new IvParameterSpec(aesIV));
        aesCbcEncrypt.update(audio, 0, audioLength / 16 * 16, audio, 0);
    }
}
