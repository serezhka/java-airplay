package com.github.serezhka.airplay.player.vlc;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("vlc")
class VlcPlaybackTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void playsSyntheticAirPlayStream() throws Exception {
        assumeTrue(vlcAvailable(), "VLC / cvlc not available");
        System.setProperty("airplay.vlc.headless", "true");

        VlcPlayer player = new VlcPlayer();
        try {
            player.onVideoFormat(new VideoStreamInfo("playback-test"));
            assertTrue(player.isCliAlive(), "cvlc/vlc did not start");
            assertDoesNotThrow(() -> PlaybackFixture.play(player));
            assertTrue(player.isCliAlive(), "cvlc/vlc exited while receiving H264");
        } finally {
            player.onVideoSrcDisconnect();
        }
    }

    private static boolean vlcAvailable() {
        if (Files.isRegularFile(Path.of("/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib"))
                || Files.isRegularFile(Path.of("/usr/lib/x86_64-linux-gnu/libvlc.so.5"))
                || Files.isRegularFile(Path.of("/usr/lib/aarch64-linux-gnu/libvlc.so.5"))
                || Files.isRegularFile(Path.of("/usr/lib/libvlc.so.5"))) {
            return true;
        }
        for (String bin : new String[]{"cvlc", "vlc"}) {
            try {
                Process p = new ProcessBuilder(bin, "--version").redirectErrorStream(true).start();
                if (p.waitFor(3, TimeUnit.SECONDS)) {
                    return true;
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return false;
    }
}
