package com.github.serezhka.airplay.app.mse.app;

import com.github.serezhka.airplay.app.common.app.PlayerApp;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.github.serezhka.airplay.app")
public class MsePlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(MsePlayerApp.class, args);
    }
}
