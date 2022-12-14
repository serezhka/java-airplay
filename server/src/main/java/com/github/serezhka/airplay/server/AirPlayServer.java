package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AirPlayBonjour;
import com.github.serezhka.airplay.server.internal.ControlServer;

public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final AirplayDataConsumer airplayDataConsumer;
    private final ControlServer controlServer;
    private final AirPlayConfig airPlayConfig;

    public AirPlayServer(AirPlayConfig airPlayConfig, AirplayDataConsumer airplayDataConsumer) {
        this.airPlayConfig = airPlayConfig;
        airPlayBonjour = new AirPlayBonjour(airPlayConfig.getServerName());
        this.airplayDataConsumer = airplayDataConsumer;
        controlServer = new ControlServer(airPlayConfig, airplayDataConsumer);
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
