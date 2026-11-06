package com.github.serezhka.airplay.app.common.app.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;

import java.util.Locale;

/**
 * Tray / CLI cast sender sources ({@code client.video.src}, {@code client.audio.src}).
 * Allowed values depend on the player jar ({@link CastSourcePolicy}).
 */
@Data
public class ClientCastProperties {

    private final CastSourcePolicy castSourcePolicy;
    private final Video video = new Video();
    private final Audio audio = new Audio();

    public ClientCastProperties(CastSourcePolicy castSourcePolicy) {
        this.castSourcePolicy = castSourcePolicy;
    }

    @PostConstruct
    void validate() {
        castSourcePolicy.validate(video.getSrc(), audio.getSrc());
        video.setSrc(normalize(video.getSrc()));
        audio.setSrc(normalize(audio.getSrc()));
    }

    public boolean videoEnabled() {
        return !"none".equals(video.getSrc());
    }

    public boolean audioEnabled() {
        return !"none".equals(audio.getSrc());
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    @Data
    public static class Video {
        /** {@code videotest} | {@code screencapture} | {@code none} (MSE jar: {@code browser}) */
        private String src = "videotest";
    }

    @Data
    public static class Audio {
        /** {@code audiotest} | {@code none} (MSE jar: {@code browser} tab capture) */
        private String src = "audiotest";
    }
}
