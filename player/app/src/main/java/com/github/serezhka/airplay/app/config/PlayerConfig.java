package com.github.serezhka.airplay.app.config;

import com.github.serezhka.airplay.app.menu.SystemTrayMenu;
import com.github.serezhka.airplay.player.gstreamer.GstPlayerDefault;
import com.github.serezhka.airplay.player.gstreamer.GstPlayerSwing;
import com.github.serezhka.airplay.player.h264dump.H264Dump;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.AirPlayServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlayerConfig {

    @Bean
    @ConditionalOnProperty(value = "player.implementation", havingValue = "gstreamer")
    public AirPlayConsumer gstreamer(@Value("#{new Boolean('${player.gstreamer.swing}')}") boolean useSwing) {
        return useSwing ? new GstPlayerSwing() : new GstPlayerDefault();
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
    @ConditionalOnProperty(value = "player.tray.enabled", havingValue = "true")
    public SystemTrayMenu systemTrayMenu(ApplicationContext context) {
        return new SystemTrayMenu(context);
    }

    @Bean
    public AirPlayServer airPlayServer(AirPlayConfig airPlayConfig,
                                       AirPlayConsumer airPlayConsumer) {
        return new AirPlayServer(airPlayConfig, airPlayConsumer);
    }
}
