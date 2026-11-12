package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RTSP SETUP calls {@link MsePlayer#onVideoFormat} on the Netty thread. It must return
 * immediately even when Chromium is cold — otherwise the phone waits for the video port
 * and the first picture is delayed by the whole browser launch.
 */
class MsePlayerSetupLatencyTest {

    @Test
    @Timeout(30)
    void onVideoFormatReturnsWithoutBlockingOnChromium() throws Exception {
        // Force "no browser" so ensureBrowserAsync is a no-op; still exercises the SETUP path.
        System.setProperty("airplay.mse.browser", "/nonexistent/mse-browser-for-latency-test");
        try (MsePlayer player = new MsePlayer(30)) {
            long t0 = System.nanoTime();
            player.onVideoFormat(new VideoStreamInfo("latency-probe"));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(player.isMirrorActive(), "mirror should arm immediately");
            assertTrue(ms < 250, "onVideoFormat blocked " + ms + "ms (must not await Chromium)");
        } finally {
            System.clearProperty("airplay.mse.browser");
        }
    }
}
