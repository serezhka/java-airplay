package com.github.serezhka.airplay.protocol.mirror;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H264SpsDimensionsTest {

    @Test
    void parsesBaseline720pSps() {
        // libx264 Baseline yuv420p 1280x720, SPS NAL only
        byte[] sps = HexFormat.of().parseHex(
                "6742c01fd9005005bb011000000300100000030320f183248000");
        Optional<H264SpsDimensions.Dimensions> d = H264SpsDimensions.parse(sps);
        assertTrue(d.isPresent());
        assertEquals(1280, d.get().width());
        assertEquals(720, d.get().height());
    }
}
