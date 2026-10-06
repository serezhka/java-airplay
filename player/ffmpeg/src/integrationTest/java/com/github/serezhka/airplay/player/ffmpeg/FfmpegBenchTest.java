package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.player.harness.PlaybackBench;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("bench")
class FfmpegBenchTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void fiveMinutePlayerBench() throws Exception {
        assumeTrue(PlaybackBench.onPath("ffplay"), "ffplay not on PATH");
        FFmpegPlayer player = new FFmpegPlayer(30);
        PlaybackBench.run("ffmpeg", player, player::videoProcessPid);
    }
}
