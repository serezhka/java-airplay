package com.github.serezhka.airplay.player.vlc;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("vlc")
class VlcPlaybackTest {

    @Test
    void playsSyntheticAirPlayStream() throws Exception {
        assumeTrue(vlcAvailable(), "VLC native libraries not available");

        VlcPlayer player = new VlcPlayer();
        try {
            player.onVideoFormat(new VideoStreamInfo("playback-test"));
            assertDoesNotThrow(() -> PlaybackFixture.play(player));
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
        try {
            Process p = new ProcessBuilder("vlc", "--version").redirectErrorStream(true).start();
            return p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
