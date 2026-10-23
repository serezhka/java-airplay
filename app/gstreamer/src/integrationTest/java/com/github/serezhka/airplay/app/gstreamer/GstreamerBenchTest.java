package com.github.serezhka.airplay.app.gstreamer;

import com.github.serezhka.airplay.app.common.harness.PlaybackBench;
import com.github.serezhka.airplay.server.Playback;
import org.freedesktop.gstreamer.Gst;
import org.junit.jupiter.api.AfterAll;
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

    @AfterAll
    static void releaseGst() {
        // Native GST worker threads otherwise keep the Gradle test JVM alive on CI.
        try {
            Gst.deinit();
        } catch (Throwable ignored) {
            // already torn down / never inited
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void fiveMinutePlayerBench() throws Exception {
        assumeTrue(PlaybackBench.onPath("gst-launch-1.0") || gstreamerLikelyAvailable(), "GStreamer not available");
        Playback player = new GstPlayer();
        try {
            PlaybackBench.run("gstreamer", player, null);
        } finally {
            player.onVideoSrcDisconnect();
            player.onAudioSrcDisconnect();
        }
    }

    private static boolean gstreamerLikelyAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Files.isDirectory(Path.of("/opt/homebrew/lib")) || Files.isDirectory(Path.of("/usr/local/lib"));
        }
        return PlaybackBench.onPath("gst-launch-1.0");
    }
}
