package com.github.serezhka.airplay.app.ffmpeg.app;

import com.github.serezhka.airplay.app.common.app.PlayerApp;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.github.serezhka.airplay.app")
public class FfmpegPlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(FfmpegPlayerApp.class, args);
    }
}
