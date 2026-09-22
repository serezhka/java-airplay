package com.github.serezhka.airplay.client.hap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** HKDF-SHA512 and ChaCha20-Poly1305 helpers for HAP pair-setup / pair-verify. */
public final class HapCrypto {

    private HapCrypto() {
    }

    public static byte[] sha512(byte[]... parts) throws GeneralSecurityException {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        for (byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    public static byte[] hkdfSha512(byte[] ikm, String salt, String info, int length)
            throws GeneralSecurityException {
        return hkdfSha512(ikm, salt.getBytes(StandardCharsets.UTF_8),
                info.getBytes(StandardCharsets.UTF_8), length);
    }

    public static byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(salt, "HmacSHA512"));
        byte[] prk = mac.doFinal(ikm);

        mac.init(new SecretKeySpec(prk, "HmacSHA512"));
        byte[] result = new byte[length];
        byte[] t = new byte[0];
        int offset = 0;
        byte counter = 1;
        while (offset < length) {
            mac.update(t);
            mac.update(info);
            mac.update(counter);
            t = mac.doFinal();
            int copy = Math.min(t.length, length - offset);
            System.arraycopy(t, 0, result, offset, copy);
            offset += copy;
            counter++;
        }
        return result;
    }

    /** 12-byte ChaCha20-Poly1305 nonce: ASCII label left-aligned, zero-padded. */
    public static byte[] nonce(String label) {
        byte[] n = new byte[12];
        byte[] raw = label.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, n, 0, Math.min(raw.length, 12));
        return n;
    }

    public static byte[] chachaEncrypt(byte[] key, byte[] nonce12, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce12));
        return cipher.doFinal(plaintext);
    }

    public static byte[] chachaDecrypt(byte[] key, byte[] nonce12, byte[] ciphertextAndTag)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce12));
        return cipher.doFinal(ciphertextAndTag);
    }

    public static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static void wipe(byte[]... arrays) {
        for (byte[] a : arrays) {
            if (a != null) {
                Arrays.fill(a, (byte) 0);
            }
        }
    }
}
