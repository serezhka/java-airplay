package com.github.serezhka.airplay.app.gstreamer.app;

import com.github.serezhka.airplay.app.common.app.PlayerApp;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.github.serezhka.airplay.app")
public class GstreamerPlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(GstreamerPlayerApp.class, args);
    }
}
