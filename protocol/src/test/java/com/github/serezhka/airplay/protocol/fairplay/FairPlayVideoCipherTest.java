package com.github.serezhka.airplay.protocol.fairplay;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FairPlayVideoCipherTest {

    @Test
    void encryptDecryptRoundtripAcrossPacketSizes() throws Exception {
        byte[] aesKey = new byte[16];
        byte[] shared = new byte[32];
        new SecureRandom().nextBytes(aesKey);
        new SecureRandom().nextBytes(shared);
        String sid = Long.toUnsignedString(-3907568444900622110L);

        FairPlayVideoCipher enc = new FairPlayVideoCipher(aesKey, shared, sid);
        FairPlayVideoCipher dec = new FairPlayVideoCipher(aesKey, shared, sid);

        int[] sizes = {43, 8412, 1200, 500, 8412};
        SecureRandom rnd = new SecureRandom();
        for (int size : sizes) {
            byte[] plain = new byte[size];
            rnd.nextBytes(plain);
            byte[] wire = plain.clone();
            enc.crypt(wire);
            dec.crypt(wire);
            assertTrue(Arrays.equals(plain, wire), "size=" + size);
        }
    }
}
