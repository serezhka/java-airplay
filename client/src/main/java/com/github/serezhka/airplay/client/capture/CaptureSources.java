package com.github.serezhka.airplay.client.capture;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Process-wide {@link CaptureSourceFactory}. Spring apps install via a {@code @Bean};
 * standalone CLIs can {@link #install} or rely on {@link ServiceLoader}.
 */
public final class CaptureSources {

    private static final AtomicReference<CaptureSourceFactory> FACTORY = new AtomicReference<>();

    private CaptureSources() {
    }

    public static void install(CaptureSourceFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        FACTORY.set(factory);
    }

    public static CaptureSource open(Consumer<byte[]> onAnnexB) {
        CaptureSourceFactory factory = FACTORY.get();
        if (factory == null) {
            factory = ServiceLoader.load(CaptureSourceFactory.class).findFirst().orElse(null);
            if (factory != null) {
                FACTORY.compareAndSet(null, factory);
                factory = FACTORY.get();
            }
        }
        if (factory == null) {
            throw new IllegalStateException(
                    "No CaptureSourceFactory installed. Run a receiver app that registers one "
                            + "(tray / bootRun / fat jar), or call CaptureSources.install(...).");
        }
        return factory.open(onAnnexB);
    }
}
