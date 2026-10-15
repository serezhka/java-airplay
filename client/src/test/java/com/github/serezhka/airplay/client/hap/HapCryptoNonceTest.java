package com.github.serezhka.airplay.client.hap;

import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HapCryptoNonceTest {

    @Test
    void leftAlignedPsMsg() {
        byte[] n = HapCrypto.nonce(AirPlayKeyLabels.PS_MSG_05);
        assertEquals(12, n.length);
        assertEquals('P', n[0] & 0xFF);
        assertEquals(0, n[8] & 0xFF);
    }

    @Test
    void offset4PvMsgMatchesWorkingSender() {
        byte[] n = HapCrypto.nonceAt4(AirPlayKeyLabels.PV_MSG_02);
        assertEquals(12, n.length);
        assertEquals(0, n[0] & 0xFF);
        assertEquals(0, n[3] & 0xFF);
        assertEquals('P', n[4] & 0xFF);
        assertEquals('2', n[11] & 0xFF);
    }
}
