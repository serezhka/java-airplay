package com.github.serezhka.airplay.client.cast;

import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;

import java.util.List;

/**
 * Headless smoke: print discovered AirPlay receivers and exit non-zero if none.
 * <pre>./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.cast.CastDiscoverMain</pre>
 */
public final class CastDiscoverMain {

    private CastDiscoverMain() {
    }

    public static void main(String[] args) {
        List<ServiceDiscovery.Info> devices = new ServiceDiscovery().discover();
        System.out.println("DISCOVERED=" + devices.size());
        for (ServiceDiscovery.Info d : devices) {
            System.out.println("DEVICE\t" + d.name() + "\t" + d.address() + "\t" + d.port());
        }
        if (devices.isEmpty()) {
            System.exit(2);
        }
    }
}
