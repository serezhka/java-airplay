package com.github.serezhka.airplay.client.audio;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class AudioSources {

    private static final AtomicReference<AudioSourceFactory> FACTORY = new AtomicReference<>();

    private AudioSources() {
    }

    public static void install(AudioSourceFactory factory) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        FACTORY.set(factory);
    }

    public static AudioSource open(Consumer<byte[]> onAdtsFrame) {
        AudioSourceFactory factory = FACTORY.get();
        if (factory == null) {
            factory = ServiceLoader.load(AudioSourceFactory.class).findFirst().orElse(null);
            if (factory != null) {
                FACTORY.compareAndSet(null, factory);
                factory = FACTORY.get();
            }
        }
        if (factory == null) {
            throw new IllegalStateException(
                    "No AudioSourceFactory installed. Run the GStreamer or FFmpeg app, "
                            + "or call AudioSources.install(...).");
        }
        return factory.open(onAdtsFrame);
    }
}
