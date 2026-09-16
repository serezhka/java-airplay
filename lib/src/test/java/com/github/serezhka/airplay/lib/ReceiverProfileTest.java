package com.github.serezhka.airplay.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverProfileTest {

    @Test
    void bit27LegacyPairingIsOff() {
        assertFalse((ReceiverProfile.FEATURES_LO & (1 << 27)) != 0);
    }

    @Test
    void restoresHistoricLoBitsExceptPairing() {
        // Historic 0x5A7FFFF7 with bit 27 cleared.
        assertEquals(0x527FFFF7, ReceiverProfile.FEATURES_LO);
        assertEquals(0, ReceiverProfile.FEATURES_HI);
        assertTrue((ReceiverProfile.FEATURES_LO & 1) != 0); // bit 0
        assertTrue((ReceiverProfile.FEATURES_LO & (1 << 4)) != 0);
        assertTrue((ReceiverProfile.FEATURES_LO & (1 << 8)) != 0);
    }

    @Test
    void txtMatchesLoHi() {
        assertEquals("0x527FFFF7,0x0", ReceiverProfile.FEATURES_TXT);
    }
}
