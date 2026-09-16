package com.github.serezhka.airplay.server.internal.handler.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirPlayVolumeTest {

    @Test
    void maxDbIsFullLinear() {
        assertEquals(1.0, AirPlayVolume.fromDecibels(0.0), 1e-9);
    }

    @Test
    void muteDbIsZero() {
        assertEquals(0.0, AirPlayVolume.fromDecibels(-144.0), 1e-9);
    }

    @Test
    void roundTripNearUnity() {
        double linear = AirPlayVolume.fromDecibels(-6.0);
        assertTrue(linear > 0.4 && linear < 0.6);
        assertEquals(-6.0, AirPlayVolume.toDecibels(linear), 0.05);
    }

    @Test
    void rtspFormatUsesDb() {
        assertTrue(AirPlayVolume.formatRtspParameter(1.0).startsWith("volume: 0.000000"));
    }
}
