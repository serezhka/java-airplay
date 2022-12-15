package com.github.serezhka.airplay.app.config;

import com.github.serezhka.airplay.player.gstreamer.GstPlayer;
import com.github.serezhka.airplay.player.h264dump.H264Dump;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.AirPlayServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlayerConfig {

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "gstreamer")
    public AirPlayConsumer gstreamer() {
        return new GstPlayer();
    }

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "h264-dump", matchIfMissing = true)
    public AirPlayConsumer h264dump() throws Exception {
        return new H264Dump();
    }

    @Bean
    @ConfigurationProperties(prefix = "airplay")
    public AirPlayConfig airPlayConfig() {
        return new AirPlayConfig();
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig,
                                       AirPlayConsumer airPlayConsumer) {
        return new AirPlayServer(airPlayConfig, airPlayConsumer);
    }
}
