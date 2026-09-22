package com.github.serezhka.airplay.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HlsEndListDurationTest {

    @Test
    void sumsEndListExtinf() {
        String vod = """
                #EXTM3U
                #EXTINF:6.5,
                a.ts
                #EXTINF:7.1,
                b.ts
                #EXT-X-ENDLIST
                """;
        assertEquals(13.6, HlsEndListDuration.sumSeconds(vod), 0.001);
    }

    @Test
    void liveWithoutEndListIsZero() {
        String live = """
                #EXTM3U
                #EXTINF:6.0,
                a.ts
                #EXTINF:6.0,
                b.ts
                """;
        assertEquals(0, HlsEndListDuration.sumSeconds(live), 0.001);
    }

    @Test
    void nullOrEmptyIsZero() {
        assertEquals(0, HlsEndListDuration.sumSeconds(null), 0.001);
        assertEquals(0, HlsEndListDuration.sumSeconds(""), 0.001);
    }
}
