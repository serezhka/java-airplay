package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.internal.AudioControlServer;
import com.github.serezhka.airplay.server.internal.AudioServer;
import com.github.serezhka.airplay.server.internal.VideoServer;
import lombok.Getter;

@Getter
public class Session {

    private final AirPlay airPlay;
    private final VideoServer videoServer;
    private final AudioServer audioServer;
    private final AudioControlServer audioControlServer;

    Session() {
        airPlay = new AirPlay();
        videoServer = new VideoServer(airPlay);
        audioServer = new AudioServer(airPlay);
        audioControlServer = new AudioControlServer();
    }
}
