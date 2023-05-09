package com.github.serezhka.airplay.client.discovery;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class ServiceDiscovery {

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";

    public Set<Info> discover() {
        try (JmDNS jmdns = JmDNS.create(InetAddress.getLoopbackAddress())) { // only loopback for now
            log.info("Searching AirPLay services..");
            return Arrays.stream(jmdns.list(AIRPLAY_SERVICE_TYPE))
                    .filter(serviceInfo -> serviceInfo.getInet4Addresses().length > 0)
                    .map(serviceInfo -> new Info(serviceInfo.getName(),
                            serviceInfo.getInet4Addresses()[0].getHostAddress(), serviceInfo.getPort()))
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record Info(String name, String address, int port) {
    }
}
