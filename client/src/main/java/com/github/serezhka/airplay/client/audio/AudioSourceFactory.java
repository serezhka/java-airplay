package com.github.serezhka.airplay.client.audio;

import java.util.function.Consumer;

@FunctionalInterface
public interface AudioSourceFactory {

    AudioSource open(Consumer<byte[]> onAdtsFrame);
}
