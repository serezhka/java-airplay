package com.github.serezhka.airplay.app.common.app.config;

import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.app.common.app.menu.SystemTrayMenu;
import com.github.serezhka.airplay.app.common.dump.DumpConfig;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.Playback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(ClientCastProperties.class)
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

    @Bean(destroyMethod = "close")
    public MirrorCaster mirrorCaster(AirPlayConfig airPlayConfig, ClientCastProperties cast) {
        if (!cast.videoEnabled() && !cast.audioEnabled()) {
            log.info("Cast media disabled (client.video.src=none and client.audio.src=none); "
                    + "receiver/discovery still run");
        }
        return new MirrorCaster(
                airPlayConfig.isMdnsLoopback(),
                cast.videoEnabled(),
                cast.audioEnabled());
    }

    @Bean
    @ConditionalOnProperty(value = "airplay.tray-enabled", havingValue = "true", matchIfMissing = true)
    public SystemTrayMenu systemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster) {
        return new SystemTrayMenu(context, mirrorCaster);
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig, Playback playback) {
        return new AirPlayServer(airPlayConfig, playback);
    }
}
