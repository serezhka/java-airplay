package com.github.serezhka.airplay.app;

import com.github.serezhka.airplay.player.dump.DumpConfig;
import com.github.serezhka.airplay.player.ffmpeg.FFmpegPlayer;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FfmpegPlaybackConfig {

    @Bean
    public Playback playback(AirPlayConfig airPlayConfig, DumpConfig dumpConfig) {
        return PlaybackFactory.withDump(new FFmpegPlayer(airPlayConfig.getFps()), dumpConfig);
    }
}
