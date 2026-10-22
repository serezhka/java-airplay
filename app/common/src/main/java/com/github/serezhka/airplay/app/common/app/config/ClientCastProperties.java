package com.github.serezhka.airplay.app.common.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tray / CLI cast sender sources ({@code client.video.src}, {@code client.audio.src}).
 */
@Data
@ConfigurationProperties(prefix = "client")
public class ClientCastProperties {

    private final Video video = new Video();
    private final Audio audio = new Audio();

    public boolean videoEnabled() {
        return !"none".equalsIgnoreCase(video.getSrc());
    }

    public boolean audioEnabled() {
        return !"none".equalsIgnoreCase(audio.getSrc());
    }

    @Data
    public static class Video {
        /** {@code videotest} | {@code screencapture} | {@code none} */
        private String src = "videotest";
    }

    @Data
    public static class Audio {
        /** {@code audiotest} | {@code none} */
        private String src = "audiotest";
    }
}
