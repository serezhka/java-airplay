package com.github.serezhka.airplay.server.internal.handler.util;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import com.github.serezhka.airplay.server.Playback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyListUtilPlaybackInfoTest {

    @Test
    void playingEventCarriesScrubPositionAndRate() throws Exception {
        byte[] xml = PropertyListUtil.preparePlaybackStateEvent(
                "playing", 4, "item-1", null, new Playback.Info(1434, 333.22, 1));
        NSDictionary dict = (NSDictionary) PropertyListParser.parse(xml);
        assertEquals("playing", dict.get("state").toJavaObject());
        // Top-level clock fields are ignored; the sender reads params.
        assertEquals(null, dict.get("position"));
        assertEquals(null, dict.get("rate"));
        NSDictionary params = (NSDictionary) dict.get("params");
        assertEquals("item-1", params.get("uuid").toJavaObject());
        assertEquals(333.22, ((Number) params.get("position").toJavaObject()).doubleValue(), 0.001);
        assertEquals(1434.0, ((Number) params.get("duration").toJavaObject()).doubleValue(), 0.001);
        assertEquals(1.0, ((Number) params.get("rate").toJavaObject()).doubleValue(), 0.001);
        assertTrue((Boolean) params.get("readyToPlay").toJavaObject());
        assertFalse((Boolean) params.get("playbackBufferEmpty").toJavaObject());
        assertTrue((Boolean) params.get("playbackBufferFull").toJavaObject());
        assertTrue((Boolean) params.get("playbackLikelyToKeepUp").toJavaObject());
    }

    @Test
    void readyToPlayWhenDurationKnown() throws Exception {
        byte[] xml = PropertyListUtil.preparePlaybackInfoResponse(new Playback.Info(120, 15, 1));
        NSDictionary dict = (NSDictionary) PropertyListParser.parse(xml);
        assertEquals(120.0, ((Number) dict.get("duration").toJavaObject()).doubleValue(), 0.001);
        assertEquals(15.0, ((Number) dict.get("position").toJavaObject()).doubleValue(), 0.001);
        assertEquals(1, ((Number) dict.get("rate").toJavaObject()).intValue());
        assertTrue((Boolean) dict.get("readyToPlay").toJavaObject());
        assertFalse((Boolean) dict.get("playbackBufferEmpty").toJavaObject());
        assertTrue((Boolean) dict.get("playbackBufferFull").toJavaObject());
        assertTrue((Boolean) dict.get("playbackLikelyToKeepUp").toJavaObject());
    }

    @Test
    void keepsCallerRateAtEnd() throws Exception {
        // VOD ad EOS: ControlHandler pins rate=1 at position=duration; must not be zeroed here.
        byte[] xml = PropertyListUtil.preparePlaybackInfoResponse(new Playback.Info(6, 6, 1));
        NSDictionary dict = (NSDictionary) PropertyListParser.parse(xml);
        assertEquals(1, ((Number) dict.get("rate").toJavaObject()).intValue());
        assertEquals(6.0, ((Number) dict.get("position").toJavaObject()).doubleValue(), 0.001);
        assertTrue((Boolean) dict.get("playbackBufferEmpty").toJavaObject());
        assertFalse((Boolean) dict.get("playbackBufferFull").toJavaObject());
    }

    @Test
    void reportsPausedRate() throws Exception {
        byte[] xml = PropertyListUtil.preparePlaybackInfoResponse(new Playback.Info(60, 10, 0));
        NSDictionary dict = (NSDictionary) PropertyListParser.parse(xml);
        assertEquals(0, ((Number) dict.get("rate").toJavaObject()).intValue());
        assertTrue((Boolean) dict.get("readyToPlay").toJavaObject());
    }

    @Test
    void spinnerFlagsWhenDurationUnknown() throws Exception {
        byte[] xml = PropertyListUtil.preparePlaybackInfoResponse(new Playback.Info(0, 0, 1));
        NSDictionary dict = (NSDictionary) PropertyListParser.parse(xml);
        assertFalse((Boolean) dict.get("readyToPlay").toJavaObject());
        assertTrue((Boolean) dict.get("playbackBufferEmpty").toJavaObject());
    }
}
