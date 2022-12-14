package com.github.serezhka.airplay.lib.internal;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class FairPlayVideoDecryptor {

    private final byte[] aesKey;
    private final byte[] sharedSecret;
    private final String streamConnectionID;

    private final Cipher aesCtrDecrypt;
    private final byte[] og = new byte[16];

    private int nextDecryptCount;

    public FairPlayVideoDecryptor(byte[] aesKey, byte[] sharedSecret, String streamConnectionID) throws Exception {
        this.aesKey = aesKey;
        this.sharedSecret = sharedSecret;
        this.streamConnectionID = streamConnectionID;

        aesCtrDecrypt = Cipher.getInstance("AES/CTR/NoPadding");

        initAesCtrCipher();
    }

    public void decrypt(byte[] video) throws Exception {
        if (nextDecryptCount > 0) {
            for (int i = 0; i < nextDecryptCount; i++) {
                video[i] = (byte) (video[i] ^ og[(16 - nextDecryptCount) + i]);
            }
        }

        int encryptlen = ((video.length - nextDecryptCount) / 16) * 16;
        aesCtrDecrypt.update(video, nextDecryptCount, encryptlen, video, nextDecryptCount);
        System.arraycopy(video, nextDecryptCount, video, nextDecryptCount, encryptlen);

        int restlen = (video.length - nextDecryptCount) % 16;
        int reststart = video.length - restlen;
        nextDecryptCount = 0;
        if (restlen > 0) {
            Arrays.fill(og, (byte) 0);
            System.arraycopy(video, reststart, og, 0, restlen);
            aesCtrDecrypt.update(og, 0, 16, og, 0);
            System.arraycopy(og, 0, video, reststart, restlen);
            nextDecryptCount = 16 - restlen;
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

        aesCtrDecrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(decryptAesKey, "AES"), new IvParameterSpec(decryptAesIV));
    }
}
