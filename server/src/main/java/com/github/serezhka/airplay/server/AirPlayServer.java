package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.lib.AirPlayBonjour;
import com.github.serezhka.airplay.server.internal.ControlServer;

import android.content.Context;

public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final ControlServer controlServer;

    public AirPlayServer(Context context, AirPlayConfig airPlayConfig, AirPlayConsumer airPlayConsumer) {
        airPlayBonjour = new AirPlayBonjour(context, airPlayConfig.getServerName());
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
