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
        int from = 0;
        final int n = content.length();
        while (from < n) {
            int to = content.indexOf('\n', from);
            if (to < 0) {
                to = n;
            }
            if (content.startsWith("#EXTINF:", from)) {
                int valueStart = from + "#EXTINF:".length();
                int comma = content.indexOf(',', valueStart);
                int valueEnd = comma >= 0 && comma < to ? comma : to;
                // Trim CR from CRLF playlists.
                if (valueEnd > valueStart && content.charAt(valueEnd - 1) == '\r') {
                    valueEnd--;
                }
                try {
                    sum += Double.parseDouble(content.substring(valueStart, valueEnd).trim());
                } catch (NumberFormatException ignored) {
                    // skip malformed tags
                }
            }
            from = to + 1;
        }
        return sum;
    }
}
