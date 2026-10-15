package com.github.serezhka.airplay.server;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser;
import com.github.serezhka.airplay.server.internal.ControlServer;

public class AirPlayServer {

    private final MdnsAdvertiser airPlayBonjour;
    private final ControlServer controlServer;

    public AirPlayServer(AirPlayConfig airPlayConfig, Playback airPlayConsumer) {
        PairingIdentity identity = PairingIdentity.generate();
        airPlayBonjour = new MdnsAdvertiser(airPlayConfig.getServerName(), airPlayConfig.isHlsEnabled(),
                identity.publicKeyHex());
        controlServer = new ControlServer(airPlayConfig, airPlayConsumer, identity);
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
