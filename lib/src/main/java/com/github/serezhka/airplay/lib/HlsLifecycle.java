package com.github.serezhka.airplay.lib;

public final class HlsLifecycle {

    private static volatile Runnable onEnded = () -> {};

    private HlsLifecycle() {
    }

    public static void setOnEnded(Runnable callback) {
        onEnded = callback == null ? () -> {} : callback;
    }

    public static void notifyEnded() {
        onEnded.run();
    }
}
