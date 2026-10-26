package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
import com.github.serezhka.airplay.app.mse.decode.LibavAacDecoder;
import com.github.serezhka.airplay.app.mse.decode.LibavAlacDecoder;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LibavAacEldDecodeTest {

    @Test
    void decodesAacEldFixtureToPcm() throws Exception {
        AtomicInteger pcmBytes = new AtomicInteger();
        try (LibavAacDecoder decoder = new LibavAacDecoder(PlaybackFixture.aacEldStreamInfo())) {
            for (byte[] frame : PlaybackFixture.aacEldFrames()) {
                decoder.decode(frame, (buf, off, len) -> pcmBytes.addAndGet(len));
            }
        }
        assertTrue(pcmBytes.get() > 1000, "expected decoded PCM, got " + pcmBytes.get());
    }

    @Test
    void decodesAlacFixtureToPcm() throws Exception {
        AtomicInteger pcmBytes = new AtomicInteger();
        try (LibavAlacDecoder decoder = new LibavAlacDecoder(PlaybackFixture.alacStreamInfo())) {
            for (byte[] frame : PlaybackFixture.alacFrames()) {
                decoder.decode(frame, (buf, off, len) -> pcmBytes.addAndGet(len));
            }
        }
        assertTrue(pcmBytes.get() > 1000, "expected decoded PCM, got " + pcmBytes.get());
    }
}
