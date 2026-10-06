package com.github.serezhka.airplay.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FfmpegPlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(FfmpegPlayerApp.class, args);
    }
}
