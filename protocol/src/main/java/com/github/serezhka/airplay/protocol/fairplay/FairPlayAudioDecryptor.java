package com.github.serezhka.airplay.protocol.fairplay;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;

public class FairPlayAudioDecryptor {

    private final Cipher aesCbcDecrypt;
    private final SecretKeySpec keySpec;
    private final IvParameterSpec ivSpec;

    public FairPlayAudioDecryptor(byte[] aesKey, byte[] aesIV, byte[] sharedSecret) throws Exception {
        byte[] eaesKey;
        if (sharedSecret == null || sharedSecret.length == 0) {
            eaesKey = Arrays.copyOf(aesKey, 16);
        } else {
            MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
            sha512Digest.update(aesKey);
            sha512Digest.update(sharedSecret);
            eaesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);
        }

        keySpec = new SecretKeySpec(eaesKey, "AES");
        ivSpec = new IvParameterSpec(aesIV);
        aesCbcDecrypt = Cipher.getInstance("AES/CBC/NoPadding");
    }

    public void decrypt(byte[] audio, int audioLength) throws Exception {
        // CBC packets each start from the same IV — re-init is required, but key/IV objects are reused.
        aesCbcDecrypt.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        aesCbcDecrypt.update(audio, 0, audioLength / 16 * 16, audio, 0);
    }
}
