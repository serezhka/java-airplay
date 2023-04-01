package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName = "DroidPlay";
    private int width = 1080;
    private int height = 1920;
    private int fps = 24;
}
