package com.github.serezhka.airplay.server.discovery;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Published receiver feature bits (mDNS {@code features} / {@code ft} and {@code GET /info}).
 * Bits whose name was never published stay in the catalog only when the historic mask sets them.
 */
public enum AirPlayFeature {

    VIDEO(0),
    PHOTO(1),
    VIDEO_FAIRPLAY(2),
    VIDEO_VOLUME_CONTROL(3),
    VIDEO_HTTP_LIVE_STREAMS(4),
    SLIDESHOW(5),
    /** Unpublished. Not advertised. */
    UNASSIGNED_6(6),
    SCREEN(7),
    SCREEN_ROTATE(8),
    AUDIO(9),
    /** Unpublished. Not advertised. */
    UNASSIGNED_10(10),
    AUDIO_REDUNDANT(11),
    FPSAP_V2_5_AES_GCM(12),
    PHOTO_CACHING(13),
    AUTHENTICATION_4(14),
    METADATA_ARTWORK(15),
    METADATA_PROGRESS(16),
    METADATA_TEXT(17),
    /** Audio format group 1 (PCM). Not advertised: incoming PCM is not decoded. */
    AUDIO_FORMAT_1(18),
    /** Audio format group 2 (ALAC). */
    AUDIO_FORMAT_2(19),
    /** Audio format group 3 (AAC-LC). */
    AUDIO_FORMAT_3(20),
    /** Audio format group 4 (AAC-ELD). */
    AUDIO_FORMAT_4(21),
    /** Unpublished. Not advertised. */
    UNASSIGNED_22(22),
    AUTHENTICATION_1(23),
    /** Unpublished. Not advertised. */
    UNASSIGNED_25(25),
    HAS_UNIFIED_ADVERTISER_INFO(26),
    SUPPORTS_LEGACY_PAIRING(27),
    /** Unpublished. Not advertised. */
    UNASSIGNED_28(28),
    RAOP(30),
    IS_CARPLAY(32),
    SUPPORTS_AIRPLAY_VIDEO_PLAY_QUEUE(33),
    SUPPORTS_AIRPLAY_FROM_CLOUD(34),
    SUPPORTS_TLS_PSK(35),
    /** Unpublished. Not advertised. */
    UNASSIGNED_36(36),
    SUPPORTS_UNIFIED_MEDIA_CONTROL(38),
    SUPPORTS_BUFFERED_AUDIO(40),
    SUPPORTS_PTP(41),
    SUPPORTS_SCREEN_MULTI_CODEC(42),
    SUPPORTS_SYSTEM_PAIRING(43),
    IS_AP_VALERIA_SCREEN_SENDER(44),
    SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL(46),
    SUPPORTS_TRANSIENT_PAIRING(48),
    SUPPORTS_AIRPLAY_VIDEO_V2(49),
    METADATA_NOW_PLAYING(50),
    SUPPORTS_UNIFIED_PAIR_SETUP_AND_MFI(51),
    SUPPORTS_SET_PEERS_EXTENDED_MESSAGE(52),
    SUPPORTS_AP_SYNC(54),
    SUPPORTS_WAKE_ON_LAN(55),
    SUPPORTS_WAKE_ON_LAN_ALTERNATE(56),
    /** Remote control. No such endpoint exists; never advertise. */
    SUPPORTS_HANGDOG_REMOTE_CONTROL(58),
    SUPPORTS_AUDIO_STREAM_CONNECTION_SETUP(59),
    SUPPORTS_AUDIO_MEDIA_DATA_CONTROL(60),
    SUPPORTS_RFC2198_REDUNDANCY(61);

    /**
     * Bits a sender treats as "this receiver can play video".
     * Cleared together when {@code airplay.hls-enabled} is false.
     */
    public static final Set<AirPlayFeature> VIDEO_PLAYBACK = EnumSet.of(
            VIDEO,
            VIDEO_VOLUME_CONTROL,
            VIDEO_HTTP_LIVE_STREAMS,
            SUPPORTS_AIRPLAY_VIDEO_PLAY_QUEUE,
            SUPPORTS_AIRPLAY_FROM_CLOUD,
            SUPPORTS_AIRPLAY_VIDEO_V2);

    private final int bit;

    AirPlayFeature(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public long mask() {
        return 1L << bit;
    }

    public static long maskOf(Collection<AirPlayFeature> features) {
        long mask = 0;
        for (AirPlayFeature feature : features) {
            mask |= feature.mask();
        }
        return mask;
    }
}
