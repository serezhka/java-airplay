package com.github.serezhka.airplay.app.gstreamer.app;

import com.github.serezhka.airplay.app.common.app.PlaybackFactory;
import com.github.serezhka.airplay.app.common.dump.DumpConfig;
import com.github.serezhka.airplay.app.gstreamer.GstPlayer;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GstreamerPlaybackConfig {

    @Bean
    public Playback playback(AirPlayConfig airPlayConfig, DumpConfig dumpConfig) {
        return PlaybackFactory.withDump(new GstPlayer(airPlayConfig.getFps()), dumpConfig);
    }
}
