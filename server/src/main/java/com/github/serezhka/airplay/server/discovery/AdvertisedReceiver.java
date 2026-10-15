package com.github.serezhka.airplay.server.discovery;

import java.util.EnumSet;
import java.util.Set;

/**
 * Receiver advertisement profile (mDNS TXT + {@code GET /info}).
 * <p>
 * {@link #ADVERTISED} is only what this server implements.
 * With HLS off, {@link AirPlayFeature#VIDEO_PLAYBACK} is removed.
 * FairPlay video decrypt stays: that is the mirror stream, not HLS.
 */
public final class AdvertisedReceiver {

    /**
     * Screen mirroring, FairPlay, legacy pairing, RAOP, and the codecs we decode.
     * Video playback ({@code /play}, volume, HLS, play queue) is included and
     * dropped as a group when {@code airplay.hls-enabled} is false.
     */
    private static final Set<AirPlayFeature> ADVERTISED = EnumSet.of(
            AirPlayFeature.VIDEO,
            AirPlayFeature.VIDEO_FAIRPLAY,
            AirPlayFeature.VIDEO_VOLUME_CONTROL,
            AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS,
            AirPlayFeature.SCREEN,
            AirPlayFeature.AUDIO,
            AirPlayFeature.FPSAP_V2_5_AES_GCM,
            AirPlayFeature.AUTHENTICATION_4,
            AirPlayFeature.AUDIO_FORMAT_2,
            AirPlayFeature.AUDIO_FORMAT_3,
            AirPlayFeature.AUDIO_FORMAT_4,
            AirPlayFeature.SUPPORTS_LEGACY_PAIRING,
            AirPlayFeature.RAOP,
            AirPlayFeature.SUPPORTS_AIRPLAY_VIDEO_PLAY_QUEUE);

    public static final long FEATURES = features(true);
    public static final int FEATURES_LO = (int) FEATURES;
    public static final int FEATURES_HI = (int) (FEATURES >>> 32);
    public static final String FEATURES_TXT = featuresTxt(true);

    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    public static final int STATUS_FLAGS = 0x44;
    public static final String MDNS_FLAGS = "0x44";
    public static final int VV = 2;

    private AdvertisedReceiver() {
    }

    public static String airTunesServerHeader() {
        return "AirTunes/" + SOURCE_VERSION;
    }

    public static Set<AirPlayFeature> selected(boolean hlsEnabled) {
        EnumSet<AirPlayFeature> selected = EnumSet.copyOf(ADVERTISED);
        if (!hlsEnabled) {
            selected.removeAll(AirPlayFeature.VIDEO_PLAYBACK);
        }
        return selected;
    }

    public static int featuresLo(boolean hlsEnabled) {
        return (int) features(hlsEnabled);
    }

    public static int featuresHi(boolean hlsEnabled) {
        return (int) (features(hlsEnabled) >>> 32);
    }

    public static long features(boolean hlsEnabled) {
        return AirPlayFeature.maskOf(selected(hlsEnabled));
    }

    public static String featuresTxt(boolean hlsEnabled) {
        return String.format("0x%X,0x%X", featuresLo(hlsEnabled), featuresHi(hlsEnabled));
    }
}
