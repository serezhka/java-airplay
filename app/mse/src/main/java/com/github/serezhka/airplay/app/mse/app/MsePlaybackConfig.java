package com.github.serezhka.airplay.app.mse.app;

import com.github.serezhka.airplay.app.common.app.PlaybackFactory;
import com.github.serezhka.airplay.app.common.dump.DumpConfig;
import com.github.serezhka.airplay.app.mse.MsePlayer;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MsePlaybackConfig {

    @Bean
    public Playback playback(AirPlayConfig airPlayConfig, DumpConfig dumpConfig) {
        return PlaybackFactory.withDump(new MsePlayer(airPlayConfig.getFps()), dumpConfig);
    }
}
