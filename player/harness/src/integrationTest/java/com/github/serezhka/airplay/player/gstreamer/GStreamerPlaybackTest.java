package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gstreamer")
class GStreamerPlaybackTest {

    @Test
    void playsSyntheticAirPlayStreamInFullscreen() throws Exception {
        GstPlayer player = new GstPlayer();
        assertTrue(player.isFullscreenConfigured(), "Selected GStreamer sink is not configured for fullscreen");

        try {
            player.onVideoFormat(new VideoStreamInfo("playback-test"));
            PlaybackFixture.play(player);
            assertTrue(player.isVideoPipelinePlaying(), "GStreamer pipeline stopped while receiving H264");
        } finally {
            player.onVideoSrcDisconnect();
        }
    }
}
