package com.github.serezhka.airplay.client.audio;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Registry for {@link AudioSourceFactory}. Apps install via Spring when
 * {@code client.audio.src=audiotest}; {@link com.github.serezhka.airplay.client.cast.MirrorCaster}
 * opens a source only when cast audio is enabled.
 */
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
                    "No AudioSourceFactory installed. Set client.audio.src=audiotest "
                            + "in a receiver app, or call AudioSources.install(...).");
        }
        return factory.open(onAdtsFrame);
    }
}
