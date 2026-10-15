package com.github.serezhka.airplay.player.common.app.config;

import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.player.common.app.menu.SystemTrayMenu;
import com.github.serezhka.airplay.player.common.dump.DumpConfig;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.Playback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlayerConfig {

    @Bean
    @ConfigurationProperties(prefix = "airplay")
    public AirPlayConfig airPlayConfig() {
        return new AirPlayConfig();
    }

    @Bean
    @ConfigurationProperties(prefix = "dump")
    public DumpConfig dumpConfig() {
        return new DumpConfig();
    }

    @Bean
    public MirrorCaster mirrorCaster() {
        return new MirrorCaster();
    }

    @Bean
    @ConditionalOnProperty(value = "player.tray.enabled", havingValue = "true")
    public SystemTrayMenu systemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster) {
        return new SystemTrayMenu(context, mirrorCaster);
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig, Playback playback) {
        return new AirPlayServer(airPlayConfig, playback);
    }
}
