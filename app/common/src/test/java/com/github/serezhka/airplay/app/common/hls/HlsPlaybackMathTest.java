package com.github.serezhka.airplay.app.common.hls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsPlaybackMathTest {

    @Test
    void clampVolumeKeepsUnitInterval() {
        assertEquals(0.0, HlsPlaybackMath.clampVolume(-0.5), 0.0);
        assertEquals(1.0, HlsPlaybackMath.clampVolume(1.5), 0.0);
        assertEquals(0.42, HlsPlaybackMath.clampVolume(0.42), 0.0);
    }

    @Test
    void absurdSeekRejectsNegativeNanInfiniteAndWeekPlus() {
        assertTrue(HlsPlaybackMath.isAbsurdSeek(-1));
        assertTrue(HlsPlaybackMath.isAbsurdSeek(Double.NaN));
        assertTrue(HlsPlaybackMath.isAbsurdSeek(Double.POSITIVE_INFINITY));
        assertTrue(HlsPlaybackMath.isAbsurdSeek(HlsPlaybackMath.ABSURD_SEEK_SECONDS + 1));
        assertFalse(HlsPlaybackMath.isAbsurdSeek(0));
        assertFalse(HlsPlaybackMath.isAbsurdSeek(120.5));
    }
}
