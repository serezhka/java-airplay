package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
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
 * Headless Chromium (Playwright) against the local MSE page: mirror NALs + AAC-ELD via fdk-aac WASM.
 */
class MsePlaywrightChromeTest {

    private static final String[] ASSETS = {
            "player.html", "hls.min.js", "jmuxer.min.js",
            "fdk_aac.js", "fdk_aac.wasm", "FDK_AAC_NOTICE.txt"
    };

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void chromePlaysMirrorVideoAndDecodesAacEldInWasm() throws Exception {
        Path dir = Files.createTempDirectory("mse-pw-");
        for (String name : ASSETS) {
            try (InputStream in = MsePlaywrightChromeTest.class.getResourceAsStream("/mse/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath /mse/" + name);
                }
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

            // Same ASC as iOS AirPlay mirror / GStreamer aac-eld.
            server.configureAac("aac-eld", 44_100, 2, hex("f8e85000"));
            page.waitForFunction("() => window.__mse.fdkOpen() === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(15_000));

            for (byte[] frame : PlaybackFixture.aacEldFrames()) {
                server.sendAac(frame, 0, frame.length);
            }

            page.waitForFunction("() => window.__mse.pcmBytesQueued() > 1000", null,
                    new Page.WaitForFunctionOptions().setTimeout(15_000));

            assertEquals("mirror", page.evaluate("() => window.__mse.mode()"));
            Number pcm = (Number) page.evaluate("() => window.__mse.pcmBytesQueued()");
            Number frames = (Number) page.evaluate("() => window.__mse.aacFramesDecoded()");
            assertTrue(pcm.longValue() > 1000, "pcm queued=" + pcm);
            assertTrue(frames.longValue() > 0, "aac frames=" + frames);

            browser.close();
        }
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
