package com.github.serezhka.airplay.player.ffmpeg.app;

import com.github.serezhka.airplay.player.common.app.PlayerApp;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.github.serezhka.airplay.player")
public class FfmpegPlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(FfmpegPlayerApp.class, args);
    }
}
