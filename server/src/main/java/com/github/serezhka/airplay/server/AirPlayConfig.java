package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName;
    private int airplayPort;
    private int airtunesPort;
    private int width;
    private int height;
    private int fps;
}
