package com.github.serezhka.airplay.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiverProfileTest {

    @Test
    void matchesHistoricHlsEraMask() {
        assertEquals(0x5A7FFFF7, ReceiverProfile.FEATURES_LO);
        assertEquals(0x1E, ReceiverProfile.FEATURES_HI);
        assertEquals(130367356919L, ReceiverProfile.FEATURES);
    }

    @Test
    void txtMatchesLoHi() {
        assertEquals("0x5A7FFFF7,0x1E", ReceiverProfile.FEATURES_TXT);
    }
}
