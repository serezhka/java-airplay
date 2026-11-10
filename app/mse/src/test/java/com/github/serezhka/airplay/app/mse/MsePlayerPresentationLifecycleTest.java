package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSE must keep Chromium across mirror↔HLS stream switches and only tear it down on
 * {@link MsePlayer#onPresentationEnded()}. Uses a missing browser binary so the test
 * stays hermetic (no real Chrome).
 */
class MsePlayerPresentationLifecycleTest {

    @BeforeEach
    void forceMissingBrowser() {
        System.setProperty("airplay.mse.browser", "/nonexistent/mse-browser-for-lifecycle-test");
    }

    @AfterEach
    void clearBrowserProp() {
        System.clearProperty("airplay.mse.browser");
    }

    @Test
    @Timeout(30)
    void playlistRemovedDoesNotClearMirrorArmForRemirror() throws Exception {
        try (MsePlayer player = new MsePlayer(30)) {
            player.onVideoFormat(new VideoStreamInfo("lifecycle-mirror"));
            assertTrue(player.isMirrorActive());

            player.onVideoSrcDisconnect();
            assertFalse(player.isMirrorActive());

            player.onPlaylist("http://127.0.0.1/playlist/master.m3u8?session=t");
            assertTrue(player.isHlsActive());
            assertFalse(player.isMirrorActive());

            player.onPlaylistRemoved();
            assertFalse(player.isHlsActive());
            // Remirror on the same AirPlay session.
            player.onVideoFormat(new VideoStreamInfo("lifecycle-remirror"));
            assertTrue(player.isMirrorActive());
        }
    }

    @Test
    @Timeout(30)
    void presentationEndedStopsMirrorAndHls() throws Exception {
        try (MsePlayer player = new MsePlayer(30)) {
            player.onPlaylist("http://127.0.0.1/playlist/master.m3u8?session=t");
            assertTrue(player.isHlsActive());

            player.onPresentationEnded();
            // onPresentationEnded runs on the browser executor — wait briefly.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && player.isHlsActive()) {
                Thread.sleep(20);
            }
            assertFalse(player.isHlsActive());
            assertFalse(player.isMirrorActive());
        }
    }

    @Test
    @Timeout(30)
    void videoDisconnectDoesNotBlockLaterHls() throws Exception {
        try (MsePlayer player = new MsePlayer(30)) {
            player.onVideoFormat(new VideoStreamInfo("handoff"));
            player.onVideoSrcDisconnect();
            player.onPlaylist("http://127.0.0.1/playlist/master.m3u8?session=t");
            assertTrue(player.isHlsActive());
            assertFalse(player.isMirrorActive());
        }
    }
}
