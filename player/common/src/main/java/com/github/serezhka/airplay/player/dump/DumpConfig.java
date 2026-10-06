package com.github.serezhka.airplay.player.dump;

import lombok.Data;

@Data
public class DumpConfig {

    private boolean enabled = false;
    private String directory = "dumps";
    private boolean protocol = true;
    private boolean video = true;
    private boolean audio = true;
    private boolean playlist = true;
    private boolean artwork = true;
    private int videoFps = 60;
}
