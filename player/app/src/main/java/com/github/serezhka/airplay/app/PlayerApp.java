package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.server.AirPlayServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@Slf4j
@RequiredArgsConstructor
@SpringBootApplication
public class PlayerApp {

    private final AirPlayServer airPlayServer;

    public static void main(String[] args) {
        new SpringApplicationBuilder(PlayerApp.class)
                .web(WebApplicationType.NONE)
                .headless(false)
                .run(args);
    }

    @PostConstruct
    private void postConstruct() throws Exception {
        airPlayServer.start();
    }

    @PreDestroy
    private void preDestroy() {
        airPlayServer.stop();
    }
}
