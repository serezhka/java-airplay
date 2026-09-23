package com.github.serezhka.airplay.server.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiverProfileTest {

    @Test
    void matchesHistoricHlsEraMask() {
        assertEquals(0x5A7FFFF7, AdvertisedReceiver.FEATURES_LO);
        assertEquals(0x1E, AdvertisedReceiver.FEATURES_HI);
        assertEquals(130367356919L, AdvertisedReceiver.FEATURES);
    }

    @Test
    void txtMatchesLoHi() {
        assertEquals("0x5A7FFFF7,0x1E", AdvertisedReceiver.FEATURES_TXT);
    }
}
