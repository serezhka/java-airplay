package com.github.serezhka.airplay.player.ffmpeg;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("ffmpeg")
class FFmpegPlaybackTest {

    @Test
    void playsSyntheticAirPlayStreamWithFfplayOnPath() throws Exception {
        FFmpegPlayer player = new FFmpegPlayer();

        try {
            player.onVideoFormat(new VideoStreamInfo("playback-test"));
            assertTrue(player.isVideoProcessAlive(), "ffplay did not start");
            PlaybackFixture.play(player);
            assertTrue(player.isVideoProcessAlive(), "ffplay stopped while receiving H264");
        } finally {
            player.onVideoSrcDisconnect();
        }

        assertFalse(player.isVideoProcessAlive(), "ffplay was not stopped after disconnect");
    }
}
