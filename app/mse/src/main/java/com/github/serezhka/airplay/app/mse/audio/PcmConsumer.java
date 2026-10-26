package com.github.serezhka.airplay.app.mse.audio;

/** Interleaved little-endian PCM s16 consumer (Web Audio / tests). */
@FunctionalInterface
public interface PcmConsumer {
    void write(byte[] s16le, int offset, int length);
}
