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

    /**
     * Compact profile for {@code GET /server-info} only (not mDNS / {@code GET /info}).
     * Historic mask {@code 119}: video + HLS + FairPlay, without FPSAP / screen / RAOP.
     * Using {@link #ADVERTISED} here pushed some clients onto HTTP FairPlay v1 and
     * aborted YouTube HLS.
     */
    private static final Set<AirPlayFeature> SERVER_INFO = EnumSet.of(
            AirPlayFeature.VIDEO,
            AirPlayFeature.PHOTO,
            AirPlayFeature.VIDEO_FAIRPLAY,
            AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS,
            AirPlayFeature.SLIDESHOW,
            AirPlayFeature.UNASSIGNED_6);

    /** Feature mask for {@link #SERVER_INFO} ({@code 119} / {@code 0x77}). */
    public static final long SERVER_INFO_FEATURES = AirPlayFeature.maskOf(SERVER_INFO);

    /** {@code srcvers} on {@code GET /server-info} (kept distinct from {@link #SOURCE_VERSION}). */
    public static final String SERVER_INFO_SOURCE_VERSION = "101.28";

    private AdvertisedReceiver() {
    }

    public static Set<AirPlayFeature> selected(boolean hlsEnabled) {
        EnumSet<AirPlayFeature> selected = EnumSet.copyOf(ADVERTISED);
        if (!hlsEnabled) {
            selected.removeAll(AirPlayFeature.VIDEO_PLAYBACK);
        }
        return selected;
    }

    public static long features(boolean hlsEnabled) {
        return AirPlayFeature.maskOf(selected(hlsEnabled));
    }

    public static String featuresTxt(boolean hlsEnabled) {
        long mask = features(hlsEnabled);
        return String.format("0x%X,0x%X", (int) mask, (int) (mask >>> 32));
    }
}
