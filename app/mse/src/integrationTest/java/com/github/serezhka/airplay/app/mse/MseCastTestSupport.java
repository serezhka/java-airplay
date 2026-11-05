package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.cast.BrowserCastCaptureSource;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Shared assumptions / properties for browser-cast integration smokes (Linux xvfb + Windows CI). */
final class MseCastTestSupport {

    private MseCastTestSupport() {
    }

    static void assumeCastEnvironment() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        String display = System.getenv("DISPLAY");
        assumeTrue(windows || (display != null && !display.isBlank()),
                "Need DISPLAY (xvfb-run) or Windows desktop for browser cast");

        String browser = System.getProperty("airplay.mse.cast.browser",
                System.getProperty("airplay.mse.browser", ""));
        if (browser.isBlank()) {
            browser = MseBrowsers.resolveForCast();
        }
        assumeTrue(browser != null && MseBrowsers.isUsable(browser),
                "cast browser not found (set -Dairplay.mse.cast.browser=): " + browser);
    }

    /**
     * Cast Chromium flags only ({@code airplay.mse.cast.headless}); playback uses
     * {@code airplay.mse.headless} from CI or the host session.
     */
    static void prepareBrowserCastProperties() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        String display = System.getenv("DISPLAY");
        boolean hasDisplay = display != null && !display.isBlank();
        if (windows && !hasDisplay) {
            System.setProperty("airplay.mse.cast.headless", "true");
        }
        System.setProperty("airplay.mse.cast.autoCapture", "true");
        System.setProperty("airplay.mse.cast.home", "https://example.com");
    }

    static void resetSharedCastSession() {
        BrowserCastCaptureSource.resetSharedSessionForTests();
    }
}
