package com.github.serezhka.airplay.protocol.fairplay;

/**
 * Receiver-side FairPlay AES-CTR decrypt for mirror video.
 * Delegates to {@link FairPlayVideoCipher} so sender and receiver share one schedule.
 */
public class FairPlayVideoDecryptor {

    private final FairPlayVideoCipher cipher;

    public FairPlayVideoDecryptor(byte[] aesKey, byte[] sharedSecret, String streamConnectionID)
            throws Exception {
        this.cipher = new FairPlayVideoCipher(aesKey, sharedSecret, streamConnectionID);
    }

    public void decrypt(byte[] video) throws Exception {
        cipher.crypt(video);
    }
}
