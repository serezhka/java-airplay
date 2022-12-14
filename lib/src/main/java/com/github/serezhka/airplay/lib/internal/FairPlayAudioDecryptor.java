package com.github.serezhka.airplay.lib.internal;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;

public class FairPlayAudioDecryptor {

    private final byte[] aesIV;
    private final byte[] eaesKey;

    private final Cipher aesCbcDecrypt;

    public FairPlayAudioDecryptor(byte[] aesKey, byte[] aesIV, byte[] sharedSecret) throws Exception {
        this.aesIV = aesIV;

        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        sha512Digest.update(aesKey);
        sha512Digest.update(sharedSecret);
        eaesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        aesCbcDecrypt = Cipher.getInstance("AES/CBC/NoPadding");
    }

    public void decrypt(byte[] audio, int audioLength) throws Exception {
        initAesCbcCipher();
        aesCbcDecrypt.update(audio, 0, audioLength / 16 * 16, audio, 0);
    }

    private void initAesCbcCipher() throws Exception {
        aesCbcDecrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(eaesKey, "AES"), new IvParameterSpec(aesIV));
    }
}
