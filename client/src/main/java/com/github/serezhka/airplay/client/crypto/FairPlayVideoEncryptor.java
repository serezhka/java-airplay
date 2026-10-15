package com.github.serezhka.airplay.client.crypto;

import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoCipher;

/**
 * Sender-side FairPlay AES-CTR for mirror video.
 * Same schedule as {@link com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoDecryptor}.
 */
public class FairPlayVideoEncryptor {

    private final FairPlayVideoCipher cipher;

    public FairPlayVideoEncryptor(byte[] aesKey, byte[] sharedSecret, String streamConnectionID)
            throws Exception {
        this.cipher = new FairPlayVideoCipher(aesKey, sharedSecret, streamConnectionID);
    }

    public void encrypt(byte[] video) throws Exception {
        cipher.crypt(video);
    }
}
