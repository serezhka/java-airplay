package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser;
import com.github.serezhka.airplay.server.internal.ControlServer;

public class AirPlayServer {

    private final MdnsAdvertiser airPlayBonjour;
    private final ControlServer controlServer;

    public AirPlayServer(AirPlayConfig airPlayConfig, Playback airPlayConsumer) {
        airPlayBonjour = new MdnsAdvertiser(airPlayConfig.getServerName());
        controlServer = new ControlServer(airPlayConfig, airPlayConsumer);
    }

    public void start() throws Exception {
        controlServer.start();
        airPlayBonjour.start(controlServer.getPort());
    }

    public void stop() {
        airPlayBonjour.stop();
        controlServer.stop();
    }
}
