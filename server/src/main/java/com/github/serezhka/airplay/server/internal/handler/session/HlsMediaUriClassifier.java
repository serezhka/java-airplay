package com.github.serezhka.airplay.server.internal.handler.session;

/**
 * Classifies mediadata playlist URIs as video vs audio-alternative.
 * Audio alternatives often use itag 233/234 and/or {@code /xtags/}; STREAM-INF video
 * playlists use other itags without xtags.
 */
public final class HlsMediaUriClassifier {

    private HlsMediaUriClassifier() {
    }

    public static boolean isLikelyVideo(String uri) {
        if (uri == null || !uri.contains("mediadata")) {
            return false;
        }
        if (uri.contains("/xtags/")) {
            return false;
        }
        if (uri.contains("/itag/233/") || uri.contains("/itag/234/")) {
            return false;
        }
        return true;
    }
}
