package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.player.dump.DumpConfig;
import com.github.serezhka.airplay.player.gstreamer.GstPlayer;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GstreamerPlaybackConfig {

    @Bean
    public Playback playback(AirPlayConfig airPlayConfig, DumpConfig dumpConfig) {
        int fps = Math.max(1, airPlayConfig.getFps());
        return PlaybackFactory.withDump(new GstPlayer(fps), dumpConfig);
    }
}
