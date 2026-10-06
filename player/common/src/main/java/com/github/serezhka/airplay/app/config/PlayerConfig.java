package com.github.serezhka.airplay.app.config;

import com.github.serezhka.airplay.app.menu.SystemTrayMenu;
import com.github.serezhka.airplay.player.dump.DumpConfig;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.Playback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlayerConfig {

    @Bean
    @ConfigurationProperties(prefix = "airplay")
    public AirPlayConfig airPlayConfig(@Value("${airplay.hls.enabled:true}") boolean hlsEnabled) {
        AirPlayConfig config = new AirPlayConfig();
        config.setHlsEnabled(hlsEnabled);
        return config;
    }

    @Bean
    @ConfigurationProperties(prefix = "dump")
    public DumpConfig dumpConfig() {
        return new DumpConfig();
    }

    @Bean
    @ConditionalOnProperty(value = "player.tray.enabled", havingValue = "true")
    public SystemTrayMenu systemTrayMenu(ApplicationContext context) {
        return new SystemTrayMenu(context);
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig, Playback playback) {
        return new AirPlayServer(airPlayConfig, playback);
    }
}
