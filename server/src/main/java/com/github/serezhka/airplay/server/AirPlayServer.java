package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AirPlayBonjour;
import com.github.serezhka.airplay.server.internal.ControlServer;

public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final ControlServer controlServer;
    private final AirPlayConfig airPlayConfig;

    public AirPlayServer(AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer) {
        this.airPlayConfig = airPlayConfig;
        airPlayBonjour = new AirPlayBonjour(airPlayConfig.getServerName());
        controlServer = new ControlServer(airPlayConfig, airPlayConsumer);
    }

    public void start() throws Exception {
        airPlayBonjour.start(airPlayConfig.getAirplayPort(), airPlayConfig.getAirtunesPort());
        new Thread(controlServer).start();
    }

    public void stop() {
        airPlayBonjour.stop();
    }

    // TODO On client connected / disconnected
}
