package com.github.serezhka.airplay.client.crypto;

import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoCipher;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Continuous AES-CTR over mirror payloads (raw {@code ekey} master, no FairPlay residual).
 * Key schedule matches {@link FairPlayVideoCipher} with an empty shared secret.
 */
public final class AesCtrStreamEncryptor {

    private final Cipher ctr;

    public AesCtrStreamEncryptor(byte[] ekeyMaster16, long streamConnectionId) throws Exception {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        FairPlayVideoCipher.deriveStreamKeyIv(
                ekeyMaster16, new byte[0], Long.toUnsignedString(streamConnectionId), key, iv);
        ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    }

    public void encrypt(byte[] payload) throws ShortBufferException {
        ctr.update(payload, 0, payload.length, payload, 0);
    }
}
