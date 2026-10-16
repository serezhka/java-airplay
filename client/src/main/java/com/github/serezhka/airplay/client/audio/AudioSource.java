package com.github.serezhka.airplay.client.audio;

/**
 * Live AAC ADTS producer for audio cast smoke tests. Construction starts capture;
 * {@link #close()} stops it.
 */
public interface AudioSource extends AutoCloseable {

    @Override
    void close();
}
