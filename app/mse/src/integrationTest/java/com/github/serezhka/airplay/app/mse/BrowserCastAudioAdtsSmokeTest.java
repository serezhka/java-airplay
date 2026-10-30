package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.cast.BrowserCastAudioSource;
import com.github.serezhka.airplay.app.mse.cast.BrowserCastCaptureSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab audio path: {@code enableAudio} inject + WebCodecs {@code AudioEncoder} → ADTS via CDP binding.
 */
@Tag("mse")
class BrowserCastAudioAdtsSmokeTest {

    @Test
    @Timeout(90)
    void webCodecsBindingYieldsAdts() throws Exception {
        MseCastTestSupport.assumeCastEnvironment();
        MseCastTestSupport.prepareBrowserCastProperties();
        System.setProperty("airplay.mse.cast.enableAudio", "true");

        AtomicInteger adts = new AtomicInteger();
        // Video acquire shares the same Chromium session; audio sink must see ADTS.
        try (BrowserCastCaptureSource video = new BrowserCastCaptureSource(au -> {});
             BrowserCastAudioSource audio = new BrowserCastAudioSource(frame -> {
                 if (frame != null && frame.length > 0) {
                     adts.incrementAndGet();
                 }
             })) {
            long deadline = System.currentTimeMillis() + 45_000;
            while (System.currentTimeMillis() < deadline && adts.get() < 5) {
                Thread.sleep(250);
            }
            assertTrue(adts.get() >= 5,
                    "expected tab AAC-LC ADTS via CDP binding, got " + adts.get());
        }
    }
}
