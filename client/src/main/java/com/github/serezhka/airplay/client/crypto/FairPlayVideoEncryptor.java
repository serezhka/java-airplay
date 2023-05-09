package com.github.serezhka.airplay.client.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class FairPlayVideoEncryptor {

    private final byte[] aesKey;
    private final byte[] sharedSecret;
    private final String streamConnectionID;

    private final Cipher aesCtrEncrypt;
    private final byte[] og = new byte[16];

    private int nextEncryptCount;

    public FairPlayVideoEncryptor(byte[] aesKey, byte[] sharedSecret, String streamConnectionID) throws Exception {
        this.aesKey = aesKey;
        this.sharedSecret = sharedSecret;
        this.streamConnectionID = streamConnectionID;

        aesCtrEncrypt = Cipher.getInstance("AES/CTR/NoPadding");

        initAesCtrCipher();
    }

    public void encrypt(byte[] video) throws Exception {
        if (nextEncryptCount > 0) {
            for (int i = 0; i < nextEncryptCount; i++) {
                video[i] = (byte) (video[i] ^ og[(16 - nextEncryptCount) + i]);
            }
        }

        int encryptlen = ((video.length - nextEncryptCount) / 16) * 16;
        aesCtrEncrypt.update(video, nextEncryptCount, encryptlen, video, nextEncryptCount);
        System.arraycopy(video, nextEncryptCount, video, nextEncryptCount, encryptlen);

        int restlen = (video.length - nextEncryptCount) % 16;
        int reststart = video.length - restlen;
        nextEncryptCount = 0;
        if (restlen > 0) {
            Arrays.fill(og, (byte) 0);
            System.arraycopy(video, reststart, og, 0, restlen);
            aesCtrEncrypt.update(og, 0, 16, og, 0);
            System.arraycopy(og, 0, video, reststart, restlen);
            nextEncryptCount = 16 - restlen;
        }
    }

    private void initAesCtrCipher() throws Exception {
        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        sha512Digest.update(aesKey);
        sha512Digest.update(sharedSecret);
        byte[] eaesKey = sha512Digest.digest();

        byte[] skey = ("AirPlayStreamKey" + streamConnectionID).getBytes(StandardCharsets.UTF_8);
        sha512Digest.update(skey);
        sha512Digest.update(eaesKey, 0, 16);
        byte[] hash1 = sha512Digest.digest();

        byte[] siv = ("AirPlayStreamIV" + streamConnectionID).getBytes(StandardCharsets.UTF_8);
        sha512Digest.update(siv);
        sha512Digest.update(eaesKey, 0, 16);
        byte[] hash2 = sha512Digest.digest();

        byte[] decryptAesKey = new byte[16];
        byte[] decryptAesIV = new byte[16];
        System.arraycopy(hash1, 0, decryptAesKey, 0, 16);
        System.arraycopy(hash2, 0, decryptAesIV, 0, 16);

        aesCtrEncrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(decryptAesKey, "AES"), new IvParameterSpec(decryptAesIV));
    }
}
