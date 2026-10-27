package com.github.serezhka.airplay.client.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

/** AES-CBC audio encryptor using the HAP audio SETUP {@code shk} (16-byte key, zero IV). */
public final class ShkAudioEncryptor {

    private final Cipher aesCbcEncrypt;
    private final byte[] iv = new byte[16];

    public ShkAudioEncryptor(byte[] shk) throws Exception {
        byte[] key = Arrays.copyOf(shk, 16);
        aesCbcEncrypt = Cipher.getInstance("AES/CBC/NoPadding");
        aesCbcEncrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    }

    public void encrypt(byte[] audio, int audioLength) throws Exception {
        aesCbcEncrypt.update(audio, 0, audioLength / 16 * 16, audio, 0);
    }
}
