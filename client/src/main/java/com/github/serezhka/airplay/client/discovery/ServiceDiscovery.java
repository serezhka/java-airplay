package com.github.serezhka.airplay.client.discovery;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Browse {@code _airplay._tcp} on up, non-loopback IPv4 interfaces.
 */
@Slf4j
public class ServiceDiscovery {

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";

    public List<Info> discover() {
        return discover(Duration.ofSeconds(3));
    }

    public List<Info> discover(Duration timeout) {
        long waitMs = Math.max(500, timeout.toMillis());
        Map<String, Info> byKey = new LinkedHashMap<>();
        List<InetAddress> binds = ipv4Binds();
        if (binds.isEmpty()) {
            log.warn("No IPv4 interfaces for AirPlay browse");
            return List.of();
        }
        for (InetAddress bind : binds) {
            try (JmDNS jmdns = JmDNS.create(bind)) {
                log.info("Browsing {} on {}", AIRPLAY_SERVICE_TYPE, bind.getHostAddress());
                // Give responders time; list() alone can return too early on some stacks.
                TimeUnit.MILLISECONDS.sleep(Math.min(waitMs, 1500));
                ServiceInfo[] found = jmdns.list(AIRPLAY_SERVICE_TYPE, waitMs);
                for (ServiceInfo serviceInfo : found) {
                    Inet4Address[] v4 = serviceInfo.getInet4Addresses();
                    if (v4.length == 0) {
                        continue;
                    }
                    Info info = new Info(
                            serviceInfo.getName(),
                            v4[0].getHostAddress(),
                            serviceInfo.getPort());
                    byKey.putIfAbsent(info.address() + ":" + info.port(), info);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Browse on {} failed: {}", bind.getHostAddress(), e.toString());
            }
        }
        List<Info> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparing(Info::name, String.CASE_INSENSITIVE_ORDER));
        log.info("Discovered {} AirPlay service(s): {}", out.size(), out);
        return out;
    }

    private static List<InetAddress> ipv4Binds() {
        List<InetAddress> out = new ArrayList<>();
        try {
            NetworkInterface.networkInterfaces()
                    .filter(ni -> {
                        try {
                            return ni.isUp() && !ni.isLoopback() && !ni.isVirtual();
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress())
                    .forEach(out::add);
        } catch (Exception e) {
            log.warn("Enumerating interfaces failed: {}", e.toString());
        }
        return out;
    }

    public record Info(String name, String address, int port) {
        @Override
        public String toString() {
            return name + " (" + address + ":" + port + ")";
        }
    }
}
