package com.github.serezhka.airplay.client.hap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class HapControlCipherVectorTest {
    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    void twoMessagesMatchPythonVector() throws Exception {
        byte[] secret = new byte[64];
        for (int i = 0; i < 64; i++) secret[i] = (byte) i;
        HapControlCipher c = HapControlCipher.forClient(secret);
        byte[] e1 = c.encryptor().encrypt("SETUP phase1 test message\r\n\r\n".getBytes());
        byte[] e2 = c.encryptor().encrypt("SETUP phase2 test message longer\r\n\r\n".getBytes());
        assertArrayEquals(hex("1d00f04f377c1058748fa887b9dd460b9b3e7c7801a0ef96d91ef46879b2dd18d33d06ec90138f04a92278bab03502"), e1);
        assertArrayEquals(hex("2400747c33de6ac280fb544dbefc1a55ebed2e66e1e56bc05a3b407993bb81860d77c740088fdb5866c9bb97b3b500c8fb57375b69b3"), e2);
    }
}
