package com.github.serezhka.airplay.server.discovery;

import java.util.EnumSet;
import java.util.Set;

/**
 * Receiver advertisement profiles.
 * <p>
 * {@link #ADVERTISED} backs mDNS TXT and {@code GET /info} — what this server
 * actually implements (mirror, RAOP, FairPlay v3, HLS when enabled).
 * {@link #SERVER_INFO} backs only {@code GET /server-info} and must stay
 * distinct; see that field's comment.
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
     * Compact profile for {@code GET /server-info} only — not mDNS, not
     * {@code GET /info}.
     * <p>
     * YouTube (and similar HTTP HLS clients) probe {@code /server-info} before
     * {@code POST /play}. Advertising the full {@link #ADVERTISED} mask (or
     * {@link #SOURCE_VERSION}) there pushes those clients onto HTTP FairPlay
     * v1 ({@code /fp-setup}, {@code /fp-setup2}), which we do not implement;
     * the phone then aborts with “something went wrong” and may fall back to
     * chrome-only mirroring without HLS. Keep this historic compact set
     * (video + HLS + FairPlay, without screen / FPSAP / RAOP) and the
     * matching {@link #SERVER_INFO_SOURCE_VERSION}.
     */
    private static final Set<AirPlayFeature> SERVER_INFO = EnumSet.of(
            AirPlayFeature.VIDEO,
            AirPlayFeature.PHOTO,
            AirPlayFeature.VIDEO_FAIRPLAY,
            AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS,
            AirPlayFeature.SLIDESHOW,
            AirPlayFeature.UNASSIGNED_6);

    /** Feature mask for {@link #SERVER_INFO}. */
    public static final long SERVER_INFO_FEATURES = AirPlayFeature.maskOf(SERVER_INFO);

    /**
     * {@code srcvers} on {@code GET /server-info}. Intentionally not
     * {@link #SOURCE_VERSION}: the HTTP HLS probe path expects this value.
     */
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
