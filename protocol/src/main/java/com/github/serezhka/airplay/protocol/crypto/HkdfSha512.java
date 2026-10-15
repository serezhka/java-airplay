package com.github.serezhka.airplay.protocol.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * HKDF-SHA512 as used by HKP pair-setup / pair-verify and DataStream keys.
 */
public final class HkdfSha512 {

    private HkdfSha512() {
    }

    public static byte[] derive(byte[] ikm, String salt, String info, int length)
            throws GeneralSecurityException {
        return derive(ikm, salt.getBytes(StandardCharsets.UTF_8),
                info.getBytes(StandardCharsets.UTF_8), length);
    }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length)
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
}
