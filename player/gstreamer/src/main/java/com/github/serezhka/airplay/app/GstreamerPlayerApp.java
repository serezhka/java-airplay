package com.github.serezhka.airplay.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GstreamerPlayerApp {

    public static void main(String[] args) {
        PlayerApp.launch(GstreamerPlayerApp.class, args);
    }
}
