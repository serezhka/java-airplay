package com.github.serezhka.airplay.app.config;

import com.github.serezhka.airplay.app.menu.SystemTrayMenu;
import com.github.serezhka.airplay.player.dump.DumpConfig;
import com.github.serezhka.airplay.player.dump.DumpPlayer;
import com.github.serezhka.airplay.player.dump.DumpingAirPlayConsumer;
import com.github.serezhka.airplay.player.ffmpeg.FFmpegPlayer;
import com.github.serezhka.airplay.player.gstreamer.GstPlayer;
import com.github.serezhka.airplay.player.vlc.VlcPlayer;
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
    public AirPlayConsumer airPlayConsumer(
            @Value("${player.implementation:gstreamer}") String implementation,
            AirPlayConfig airPlayConfig,
            DumpConfig dumpConfig) {
        int fps = Math.max(1, airPlayConfig.getFps());
        AirPlayConsumer player = switch (implementation) {
            case "gstreamer" -> new GstPlayer(fps);
            case "ffmpeg" -> new FFmpegPlayer(fps);
            case "vlc" -> new VlcPlayer();
            default -> throw new IllegalArgumentException(
                    "Unknown player.implementation '" + implementation + "'. Use gstreamer, ffmpeg, or vlc.");
        };
        if (!dumpConfig.isEnabled()) {
            return player;
        }
        return new DumpingAirPlayConsumer(player, new DumpPlayer(dumpConfig));
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
