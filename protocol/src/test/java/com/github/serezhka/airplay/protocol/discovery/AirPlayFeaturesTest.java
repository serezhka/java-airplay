package com.github.serezhka.airplay.protocol.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirPlayFeaturesTest {

    @Test
    void parsesTwoWordFeaturesTxt() {
        // 0x40000a00,0x80300 → upper<<32 | lower
        long features = AirPlayFeatures.parse("0x40000a00,0x80300");
        assertEquals(0x00080300_40000a00L, features);
        assertTrue(AirPlayFeatures.has(features, AirPlayFeatures.AUDIO));
        assertTrue(AirPlayFeatures.has(features, AirPlayFeatures.SUPPORTS_BUFFERED_AUDIO));
    }

    @Test
    void prefersHomeKitWhenHkWithoutLegacy() {
        long hk = (1L << AirPlayFeatures.SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL)
                | (1L << AirPlayFeatures.SUPPORTS_TRANSIENT_PAIRING);
        assertTrue(AirPlayFeatures.prefersHomeKitPairing(hk));
        long mixed = hk | (1L << AirPlayFeatures.SUPPORTS_LEGACY_PAIRING);
        assertFalse(AirPlayFeatures.prefersHomeKitPairing(mixed));
    }

    @Test
    void statusFlagsPasswordBit() {
        assertTrue(AirPlayStatusFlags.passwordRequired(0x80));
        assertTrue(AirPlayStatusFlags.pinRequired(0x08));
        assertEquals(0x10644L, AirPlayStatusFlags.parse("0x10644"));
    }
}
