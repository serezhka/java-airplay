package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.harness.GstLaunchPlayer;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("gstreamer")
class GStreamerPlaybackTest {

    @Test
    void playsSyntheticAirPlayStreamInFullscreen() throws Exception {
        if (useCli()) {
            assumeTrue(cliAvailable(), "gst-launch-1.0 not on PATH");
            GstLaunchPlayer player = new GstLaunchPlayer();
            try {
                player.onVideoFormat(new VideoStreamInfo("playback-test"));
                assertTrue(player.isAlive(), "gst-launch-1.0 did not start");
                PlaybackFixture.play(player);
                assertTrue(player.isAlive(), "gst-launch-1.0 stopped while receiving H264");
            } finally {
                player.onVideoSrcDisconnect();
            }
            return;
        }

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

    private static boolean useCli() {
        return Boolean.parseBoolean(System.getProperty("airplay.gst.cli", "false"))
                || Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_GST_CLI", "false"))
                || System.getenv("CI") != null
                || System.getenv("GITHUB_ACTIONS") != null;
    }

    private static boolean cliAvailable() {
        try {
            Process p = new ProcessBuilder("gst-launch-1.0", "--version").redirectErrorStream(true).start();
            return p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
