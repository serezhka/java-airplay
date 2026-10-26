package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
import com.github.serezhka.airplay.app.mse.decode.LibavAacDecoder;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless Chromium (Playwright) against the local MSE page: mirror NALs + AAC-ELD→PCM.
 */
class MsePlaywrightChromeTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void chromePlaysMirrorVideoAndDecodedAacEldPcm() throws Exception {
        Path dir = Files.createTempDirectory("mse-pw-");
        for (String name : new String[]{"player.html", "hls.min.js", "jmuxer.min.js"}) {
            try (InputStream in = MsePlaywrightChromeTest.class.getResourceAsStream("/mse/" + name)) {
                Files.write(dir.resolve(name), in.readAllBytes());
            }
        }

        try (MseMediaServer server = new MseMediaServer();
             Playwright playwright = Playwright.create()) {
            server.start(dir);

            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of(
                            "--autoplay-policy=no-user-gesture-required",
                            "--use-fake-ui-for-media-stream",
                            "--use-fake-device-for-media-stream")));
            Page page = browser.newPage();
            page.navigate(server.pageUrl());
            page.waitForFunction("() => window.__mse && typeof window.__mse.mode === 'function'");
            assertTrue(server.awaitReady(20, TimeUnit.SECONDS), "page ready via /control");

            server.startMirror(30);
            page.waitForFunction("() => window.__mse.mode() === 'mirror'", null,
                    new Page.WaitForFunctionOptions().setTimeout(10_000));

            byte[] h264 = PlaybackFixture.h264();
            // Feed a burst of the fixture (Annex-B access units work as opaque NALs for jmuxer).
            int chunk = Math.min(h264.length, 64 * 1024);
            for (int i = 0; i < 8; i++) {
                server.sendNal(slice(h264, 0, chunk));
                Thread.sleep(40);
            }

            page.waitForFunction("() => window.__mse.hasJmuxer() === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(10_000));

            server.configureAudio(44_100, 2);
            try (LibavAacDecoder decoder = new LibavAacDecoder(PlaybackFixture.aacEldStreamInfo())) {
                for (byte[] frame : PlaybackFixture.aacEldFrames()) {
                    decoder.decode(frame, server::sendPcm);
                }
            }

            page.waitForFunction("() => window.__mse.pcmBytesQueued() > 1000", null,
                    new Page.WaitForFunctionOptions().setTimeout(10_000));

            assertEquals("mirror", page.evaluate("() => window.__mse.mode()"));
            Number pcm = (Number) page.evaluate("() => window.__mse.pcmBytesQueued()");
            assertTrue(pcm.longValue() > 1000, "pcm queued=" + pcm);

            browser.close();
        }
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }
}
