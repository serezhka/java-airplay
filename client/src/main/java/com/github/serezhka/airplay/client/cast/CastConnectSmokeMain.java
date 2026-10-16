package com.github.serezhka.airplay.client.cast;

import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Discover, pick a target (arg or first non-self LAN device / self), connect and
 * stream capture for a few seconds, disconnect.
 * <pre>
 *   ./gradlew :client:run -PmainClass=...CastConnectSmokeMain --args='--seconds 5'
 *   ./gradlew :client:run -PmainClass=...CastConnectSmokeMain \
 *       --args='--host &lt;receiver-ip&gt; --port 7000 --seconds 5'
 * </pre>
 */
public final class CastConnectSmokeMain {

    private CastConnectSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = 7000;
        int seconds = 5;
        String pin = "";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--pin" -> pin = args[++i];
                default -> throw new IllegalArgumentException("unknown " + args[i]);
            }
        }

        MirrorCaster caster = new MirrorCaster();
        ServiceDiscovery.Info target;
        if (host != null) {
            target = new ServiceDiscovery.Info(host, host, port);
        } else {
            List<ServiceDiscovery.Info> devices = caster.discover();
            System.out.println("DISCOVERED=" + devices.size());
            devices.forEach(d -> System.out.println("DEVICE\t" + d));
            target = devices.stream()
                    .filter(d -> !d.address().startsWith("127."))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no LAN AirPlay devices"));
        }

        System.out.println("CONNECT\t" + target + "\tpinEmpty=" + pin.isBlank());
        caster.connect(target, pin);
        System.out.println("CONNECTED=" + caster.isConnected());
        TimeUnit.SECONDS.sleep(seconds);
        caster.disconnect();
        System.out.println("DISCONNECTED ok");
    }
}
