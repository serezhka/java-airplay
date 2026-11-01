package com.github.serezhka.airplay.app.common.app.config;

import java.util.Locale;
import java.util.Set;

/**
 * Allowed {@code client.video.src} / {@code client.audio.src} values for a player fat jar.
 */
@FunctionalInterface
public interface CastSourcePolicy {

    CastSourcePolicy NATIVE_PLAYER = CastSourcePolicy.of(
            Set.of("videotest", "screencapture", "none"),
            Set.of("audiotest", "none"),
            "videotest|screencapture|none",
            "audiotest|none");

    CastSourcePolicy MSE_PLAYER = CastSourcePolicy.of(
            Set.of("browser", "none"),
            Set.of("browser", "none"),
            "browser|none",
            "browser|none");

    void validate(String videoSrc, String audioSrc);

    static CastSourcePolicy of(
            Set<String> videoAllowed,
            Set<String> audioAllowed,
            String videoHint,
            String audioHint) {
        return (videoSrc, audioSrc) -> {
            String v = normalize(videoSrc);
            String a = normalize(audioSrc);
            if (!videoAllowed.contains(v)) {
                throw new IllegalStateException(
                        "Invalid client.video.src='" + videoSrc + "'; expected " + videoHint);
            }
            if (!audioAllowed.contains(a)) {
                throw new IllegalStateException(
                        "Invalid client.audio.src='" + audioSrc + "'; expected " + audioHint);
            }
        };
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
