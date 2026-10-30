package com.github.serezhka.airplay.app.mse;

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
     * Headed under Xvfb; headless+fake-UI on Windows CI (no DISPLAY).
     * Always secure-context home + autoCapture for getDisplayMedia.
     */
    static void prepareBrowserCastProperties() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        String display = System.getenv("DISPLAY");
        boolean hasDisplay = display != null && !display.isBlank();
        System.setProperty("airplay.mse.headless", windows && !hasDisplay ? "true" : "false");
        System.setProperty("airplay.mse.cast.autoCapture", "true");
        System.setProperty("airplay.mse.cast.home", "https://example.com");
    }
}
