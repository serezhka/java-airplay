package com.github.serezhka.airplay.client.capture;

/**
 * Live H.264 Annex-B producer for mirror cast. Construction starts capture;
 * {@link #close()} stops it.
 */
public interface CaptureSource extends AutoCloseable {

    @Override
    void close();
}
