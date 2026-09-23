package com.github.serezhka.airplay.protocol.media;

/**
 * Sums {@code #EXTINF} durations from a VOD media playlist that ends with {@code #EXT-X-ENDLIST}.
 * Sliding-window live playlists (no ENDLIST) return {@code 0} so callers do not inflate ad length.
 */
public final class EndListDuration {

    private EndListDuration() {
    }

    /**
     * @return total EXTINF seconds, or {@code 0} when content is null, not ENDLIST, or has no EXTINF
     */
    public static double sumSeconds(String content) {
        if (content == null || !content.contains("#EXT-X-ENDLIST")) {
            return 0;
        }
        double sum = 0;
        for (String line : content.split("\n")) {
            if (!line.startsWith("#EXTINF:")) {
                continue;
            }
            String value = line.substring("#EXTINF:".length()).split(",", 2)[0].trim();
            try {
                sum += Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                // skip malformed tags
            }
        }
        return sum;
    }
}
