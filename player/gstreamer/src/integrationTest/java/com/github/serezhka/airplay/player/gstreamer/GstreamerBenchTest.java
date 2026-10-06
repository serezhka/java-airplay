package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.player.harness.GstLaunchPlayer;
import com.github.serezhka.airplay.player.harness.PlaybackBench;
import com.github.serezhka.airplay.server.Playback;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("bench")
class GstreamerBenchTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void fiveMinutePlayerBench() throws Exception {
        assumeTrue(PlaybackBench.onPath("gst-launch-1.0") || gstreamerLikelyAvailable(), "GStreamer not available");
        if (useGstCli()) {
            assumeTrue(PlaybackBench.onPath("gst-launch-1.0"), "gst-launch-1.0 not on PATH");
            GstLaunchPlayer gst = new GstLaunchPlayer();
            PlaybackBench.run("gstreamer", gst, gst::pid);
            return;
        }
        Playback player = new GstPlayer();
        PlaybackBench.run("gstreamer", player, null);
    }

    private static boolean useGstCli() {
        if (Boolean.parseBoolean(System.getProperty("airplay.gst.cli", "false"))) {
            return true;
        }
        if (Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_GST_CLI", "false"))) {
            return true;
        }
        return System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null;
    }

    private static boolean gstreamerLikelyAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Files.isDirectory(Path.of("/opt/homebrew/lib")) || Files.isDirectory(Path.of("/usr/local/lib"));
        }
        return PlaybackBench.onPath("gst-launch-1.0");
    }
}
