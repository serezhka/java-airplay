package com.github.serezhka.airplay.lib;

/**
 * Receiver advertisement profile (mDNS TXT + {@code GET /info}).
 * <p>
 * Screen mirroring without an MFi chip needs <strong>bit 27 off</strong> (no legacy /
 * HKP pair-setup); clients then send FairPlay {@code ekey} in SETUP. That is UxPlay's
 * {@code 0x527FFEE6} idea, but we restore LO bits 0/4/8 that were dropped when we
 * switched from the historic {@code 0x5A7FFFF7,0x1E} mask — those bits are unrelated
 * to pairing and match the pre-experiment receiver better for media/HLS UX.
 * <p>
 * High word stays {@code 0x0}: restoring {@code 0x1E} can advertise CoreUtils /
 * cloud-style capabilities that pull clients onto pairing paths we cannot finish.
 *
 * @see <a href="https://github.com/FDH2/UxPlay/blob/master/lib/dnssdint.h">UxPlay dnssdint.h</a>
 */
public final class ReceiverProfile {

    /** Historic LO with bit 27 (legacy pairing) forced off. */
    public static final int FEATURES_LO = 0x527FFFF7;
    public static final int FEATURES_HI = 0x0;

    /** 64-bit features for {@code GET /info} plist. */
    public static final long FEATURES = ((long) FEATURES_HI << 32) | (FEATURES_LO & 0xFFFFFFFFL);

    public static final String FEATURES_TXT = String.format("0x%X,0x%X", FEATURES_LO, FEATURES_HI);

    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    public static final int STATUS_FLAGS = 0x44;
    public static final String MDNS_FLAGS = "0x44";
    public static final int VV = 2;

    private ReceiverProfile() {
    }

    public static String airTunesServerHeader() {
        return "AirTunes/" + SOURCE_VERSION;
    }
}
