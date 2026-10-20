package com.github.serezhka.airplay.server.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReceiverProfileTest {

    @Test
    void matchesImplementedFeatures() {
        assertEquals(0x4838529D, AdvertisedReceiver.FEATURES_LO);
        assertEquals(0x2, AdvertisedReceiver.FEATURES_HI);
        assertEquals("0x4838529D,0x2", AdvertisedReceiver.FEATURES_TXT);
        assertEquals(AdvertisedReceiver.FEATURES, AdvertisedReceiver.features(true));
    }

    @Test
    void hangdogRemoteControlStaysOff() {
        long hangdog = AirPlayFeature.SUPPORTS_HANGDOG_REMOTE_CONTROL.mask();
        assertEquals(0, AdvertisedReceiver.features(true) & hangdog);
        assertEquals(0, AdvertisedReceiver.features(false) & hangdog);
    }

    @Test
    void hlsDisabledDropsVideoPlaybackBits() {
        assertEquals("0x48385284,0x0", AdvertisedReceiver.featuresTxt(false));
        long off = AdvertisedReceiver.features(false);
        assertEquals(0, off & AirPlayFeature.maskOf(AirPlayFeature.VIDEO_PLAYBACK));
        assertEquals(AirPlayFeature.SCREEN.mask(), off & AirPlayFeature.SCREEN.mask());
        assertEquals(AirPlayFeature.AUDIO.mask(), off & AirPlayFeature.AUDIO.mask());
        assertEquals(AirPlayFeature.VIDEO_FAIRPLAY.mask(), off & AirPlayFeature.VIDEO_FAIRPLAY.mask());
    }
}
