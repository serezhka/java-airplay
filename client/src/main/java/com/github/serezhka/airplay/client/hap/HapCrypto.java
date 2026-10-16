package com.github.serezhka.airplay.client.hap;

import com.github.serezhka.airplay.protocol.crypto.HkdfSha512;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** ChaCha helpers and thin wrappers for HAP pair-setup / pair-verify. */
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
        return HkdfSha512.derive(ikm, salt, info, length);
    }

    public static byte[] hkdfSha512(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        return HkdfSha512.derive(ikm, salt, info, length);
    }

    /** 12-byte ChaCha20-Poly1305 nonce: ASCII label left-aligned, zero-padded (HAP PS-Msg*). */
    public static byte[] nonce(String label) {
        byte[] n = new byte[12];
        byte[] raw = label.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, n, 0, Math.min(raw.length, 12));
        return n;
    }

    /**
     * 12-byte nonce with the label at offset 4 (working AirPlay pair-verify / DataStream).
     * {@code PV-Msg02}/{@code PV-Msg03} and LE counter nonces use this layout.
     */
    public static byte[] nonceAt4(byte[] label) {
        byte[] n = new byte[12];
        System.arraycopy(label, 0, n, 4, Math.min(label.length, 8));
        return n;
    }

    public static byte[] nonceAt4(String label) {
        return nonceAt4(label.getBytes(StandardCharsets.UTF_8));
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
