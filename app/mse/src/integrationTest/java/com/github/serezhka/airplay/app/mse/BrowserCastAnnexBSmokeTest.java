package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.cast.BrowserCastCaptureSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cast capture only (no mirror/TV): CDP binding + in-tab WebCodecs must emit Annex-B.
 * CI: Linux {@code xvfb-run} + {@code -Dairplay.mse.cast.autoCapture=true}; Windows headed/headless Chrome.
 */
@Tag("mse")
class BrowserCastAnnexBSmokeTest {

    @Test
    @Timeout(90)
    void webCodecsBindingYieldsAnnexB() throws Exception {
        MseCastTestSupport.assumeCastEnvironment();
        assumeTrue(BrowserCastCaptureSource.class.getResource("/mse/cast-inject.js") != null);
        MseCastTestSupport.prepareBrowserCastProperties();

        AtomicInteger aus = new AtomicInteger();
        try (BrowserCastCaptureSource src = new BrowserCastCaptureSource(au -> {
            if (au != null && au.length > 0) {
                aus.incrementAndGet();
            }
        })) {
            long deadline = System.currentTimeMillis() + 45_000;
            while (System.currentTimeMillis() < deadline && aus.get() < 8) {
                Thread.sleep(250);
            }
            assertTrue(aus.get() >= 8,
                    "expected WebCodecs Annex-B via CDP binding, got " + aus.get());
        }
    }
}
