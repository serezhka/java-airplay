package com.github.serezhka.airplay.server.internal.handler.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRtpInfoTest {

    @Test
    void parseSeqAndRtpTime() {
        AudioRtpInfo info = AudioRtpInfo.parse("seq=1234;rtptime=567890");
        assertEquals(1234, info.seq().orElseThrow());
        assertEquals(567890L, info.rtpTime().orElseThrow());
    }

    @Test
    void parseToleratesWhitespaceAndOrder() {
        AudioRtpInfo info = AudioRtpInfo.parse("rtptime=9; seq=65535");
        assertEquals(65535, info.seq().orElseThrow());
        assertEquals(9L, info.rtpTime().orElseThrow());
    }

    @Test
    void parseMissingOrJunkIsEmpty() {
        assertTrue(AudioRtpInfo.parse(null).isEmpty());
        assertTrue(AudioRtpInfo.parse("").isEmpty());
        assertTrue(AudioRtpInfo.parse("foo=bar").isEmpty());
    }

    @Test
    void parseSeqOnly() {
        AudioRtpInfo info = AudioRtpInfo.parse("seq=42");
        assertEquals(42, info.seq().orElseThrow());
        assertTrue(info.rtpTime().isEmpty());
    }
}
