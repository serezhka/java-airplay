package com.github.serezhka.airplay.client.capture;

import java.util.function.Consumer;

/**
 * Opens a {@link CaptureSource} that pushes Annex-B access units to {@code onAnnexB}.
 * Provided by the runnable app module (GStreamer or FFmpeg), not by {@code client}.
 */
@FunctionalInterface
public interface CaptureSourceFactory {

    CaptureSource open(Consumer<byte[]> onAnnexB);
}
