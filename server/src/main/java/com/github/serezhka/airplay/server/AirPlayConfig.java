package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName;
    private int width;
    private int height;
    private int fps;
    private boolean hlsEnabled;
    private boolean mdnsLoopback;
    /** System tray menu (cast picker + quit). Bound from {@code airplay.tray-enabled}. */
    private boolean trayEnabled = true;
    /**
     * Exclusive fullscreen sink for mirror/HLS. When {@code false}, show a decorated
     * resizable window instead. Bound from {@code airplay.fullscreen} (default true).
     */
    private boolean fullscreen = true;
}
