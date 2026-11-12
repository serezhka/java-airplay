package com.github.serezhka.airplay.client.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ShkAudioEncryptorTest {

    @Test
    void eachPacketResetsCbcWithZeroIv() throws Exception {
        byte[] shk = new byte[16];
        Arrays.fill(shk, (byte) 0x3c);

        byte[] packet1 = new byte[32];
        Arrays.fill(packet1, (byte) 0x11);
        byte[] packet2 = new byte[32];
        Arrays.fill(packet2, (byte) 0x22);

        ShkAudioEncryptor encryptor = new ShkAudioEncryptor(shk);
        byte[] out1 = packet1.clone();
        encryptor.encrypt(out1, out1.length);
        byte[] out2 = packet2.clone();
        encryptor.encrypt(out2, out2.length);

        assertArrayEquals(encryptBlock(shk, packet1), out1);
        assertArrayEquals(encryptBlock(shk, packet2), out2);
        // Continuous CBC (wrong) would make packet2 ciphertext depend on packet1.
        assertFalse(Arrays.equals(out2, encryptChained(shk, packet1, packet2)));
    }

    private static byte[] encryptBlock(byte[] key, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return c.doFinal(plain);
    }

    private static byte[] encryptChained(byte[] key, byte[] first, byte[] second) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        c.update(first);
        byte[] out = new byte[second.length];
        c.update(second, 0, second.length, out, 0);
        return out;
    }
}
