package com.github.serezhka.airplay.client.hap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Tlv8Test {

    @Test
    void roundTripLongPublicKey() {
        byte[] pk = new byte[384];
        for (int i = 0; i < pk.length; i++) {
            pk[i] = (byte) i;
        }
        byte[] encoded = Tlv8.of(
                HapTlv.STATE, HapTlv.STATE_M2,
                HapTlv.SALT, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                HapTlv.PUBLIC_KEY, pk);
        Map<Integer, byte[]> decoded = Tlv8.decode(encoded);
        assertEquals(HapTlv.STATE_M2, Tlv8.requireByte(decoded, HapTlv.STATE));
        assertEquals(16, decoded.get(HapTlv.SALT).length);
        assertArrayEquals(pk, decoded.get(HapTlv.PUBLIC_KEY));
    }

    @Test
    void decodeSamsungStyleM2() {
        // State=2, Salt=16, PublicKey chunked as 255+129
        byte[] body = new byte[2 + 1 + 2 + 16 + 2 + 255 + 2 + 129];
        int i = 0;
        body[i++] = HapTlv.STATE;
        body[i++] = 1;
        body[i++] = HapTlv.STATE_M2;
        body[i++] = HapTlv.SALT;
        body[i++] = 16;
        for (int s = 0; s < 16; s++) {
            body[i++] = (byte) s;
        }
        body[i++] = HapTlv.PUBLIC_KEY;
        body[i++] = (byte) 255;
        for (int s = 0; s < 255; s++) {
            body[i++] = (byte) s;
        }
        body[i++] = HapTlv.PUBLIC_KEY;
        body[i++] = (byte) 129;
        for (int s = 0; s < 129; s++) {
            body[i++] = (byte) s;
        }
        Map<Integer, byte[]> decoded = Tlv8.decode(body);
        assertEquals(384, decoded.get(HapTlv.PUBLIC_KEY).length);
        assertEquals(2, Tlv8.requireByte(decoded, HapTlv.STATE));
    }
}
