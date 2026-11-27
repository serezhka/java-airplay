package com.github.serezhka.airplay.app.common.support;

/**
 * Default size for the receive video window when {@code airplay.fullscreen=false}.
 * Capability {@code airplay.width}/{@code height} stay the advertised mirror size.
 */
public final class PlayerWindowDefaults {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;

    private PlayerWindowDefaults() {
    }
}
