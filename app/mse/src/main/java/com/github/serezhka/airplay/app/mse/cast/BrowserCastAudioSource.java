package com.github.serezhka.airplay.app.mse.cast;

import com.github.serezhka.airplay.client.audio.AudioSource;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/** Tab audio AAC-LC (ADTS) from the same {@link BrowserCastSession} as browser video cast. */
@Slf4j
public final class BrowserCastAudioSource implements AudioSource {

    private final BrowserCastSession session;
    private final Consumer<byte[]> onAdts;

    public BrowserCastAudioSource(Consumer<byte[]> onAdts) {
        this.onAdts = onAdts;
        try {
            this.session = BrowserCastSession.acquire();
            session.addAudioSink(onAdts);
            log.info("MSE browser tab audio sink registered");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start browser cast audio", e);
        }
    }

    @Override
    public void close() {
        session.removeAudioSink(onAdts);
        session.release();
    }
}
