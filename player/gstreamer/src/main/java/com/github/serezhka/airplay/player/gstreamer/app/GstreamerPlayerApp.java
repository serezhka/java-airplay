package com.github.serezhka.airplay.player.gstreamer.app;

import com.github.serezhka.airplay.player.common.app.PlayerApp;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.github.serezhka.airplay.player")
public class GstreamerPlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(GstreamerPlayerApp.class, args);
    }
}
