package com.github.serezhka.airplay.app.common.app.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/**
 * Tray / CLI cast sender sources ({@code client.video.src}, {@code client.audio.src}).
 */
@Data
@ConfigurationProperties(prefix = "client")
public class ClientCastProperties {

    private static final Set<String> VIDEO_SRC = Set.of("videotest", "screencapture", "browser", "none");
    private static final Set<String> AUDIO_SRC = Set.of("audiotest", "browser", "none");

    private final Video video = new Video();
    private final Audio audio = new Audio();

    @PostConstruct
    void validate() {
        String v = normalize(video.getSrc());
        String a = normalize(audio.getSrc());
        if (!VIDEO_SRC.contains(v)) {
            throw new IllegalStateException(
                    "Invalid client.video.src='" + video.getSrc()
                            + "'; expected videotest|screencapture|browser|none");
        }
        if (!AUDIO_SRC.contains(a)) {
            throw new IllegalStateException(
                    "Invalid client.audio.src='" + audio.getSrc()
                            + "'; expected audiotest|browser|none");
        }
        video.setSrc(v);
        audio.setSrc(a);
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
        /** {@code videotest} | {@code screencapture} | {@code browser} | {@code none} */
        private String src = "videotest";
    }

    @Data
    public static class Audio {
        /** {@code audiotest} | {@code browser} (MSE tab capture) | {@code none} */
        private String src = "audiotest";
    }
}
