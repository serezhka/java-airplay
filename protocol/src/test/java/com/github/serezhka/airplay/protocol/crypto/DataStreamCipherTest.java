package com.github.serezhka.airplay.protocol.crypto;

import com.github.serezhka.airplay.protocol.mirror.MirrorPacketHeaders;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DataStreamCipherTest {

    @Test
    void sealUsesTheHeaderAsAssociatedData() throws Exception {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);
        DataStreamCipher cipher = new DataStreamCipher(secret, 42L);
        byte[] plain = new byte[]{1, 2, 3, 4, 5};
        byte[] header = MirrorPacketHeaders.video(plain.length + 16, 1L << 32, true);
        byte[] sealed = cipher.seal(plain, header);
        assertEquals(plain.length + 16, sealed.length);
        assertEquals(16, header[5] & 0xFF);
        assertArrayEquals(plain, cipher.open(sealed, header, 0L));
    }

    @Test
    void codecHeaderMarksARunningStream() {
        byte[] header = MirrorPacketHeaders.codec(64, 1L << 32, 1920, 1080);
        assertEquals(0x01, header[4] & 0xFF);
        assertEquals(0x16, header[6] & 0xFF);
        assertEquals(0x01, header[7] & 0xFF);
    }
}
