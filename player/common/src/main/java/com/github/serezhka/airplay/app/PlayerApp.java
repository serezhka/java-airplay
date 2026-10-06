package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.server.AirPlayServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlayerApp {

    private final AirPlayServer airPlayServer;

    public static void launch(Class<?> application, String[] args) {
        new SpringApplicationBuilder(application)
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
