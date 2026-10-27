package com.github.serezhka.airplay.app.mse.cast;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Opens a navigable Chromium tab, captures it in-page with {@code getDisplayMedia} +
 * {@code VideoEncoder}, and delivers Annex-B access units via CDP {@code Runtime.addBinding}
 * (no {@code ws://} mixed content on HTTPS pages, no system ffmpeg).
 */
@Slf4j
public final class BrowserCastCaptureSource implements CaptureSource {

    private final BrowserCastSession session;
    private final Consumer<byte[]> onAnnexB;

    public BrowserCastCaptureSource(Consumer<byte[]> onAnnexB) {
        this.onAnnexB = onAnnexB;
        try {
            this.session = BrowserCastSession.acquire();
            session.addVideoSink(onAnnexB);
            if (!session.awaitFirstVideo(45, TimeUnit.SECONDS)) {
                log.warn("No in-tab Annex-B within timeout (check getDisplayMedia / tab picker)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start browser cast capture", e);
        }
    }

    @Override
    public void close() {
        session.removeVideoSink(onAnnexB);
        session.release();
    }
}
