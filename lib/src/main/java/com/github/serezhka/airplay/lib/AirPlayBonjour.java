package com.github.serezhka.airplay.lib;

import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmmDNS;
import javax.jmdns.ServiceInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers airplay/airtunes service mdns
 */
@Slf4j
public class AirPlayBonjour {

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";

    private final String serverName;

    private ServiceInfo airPlayService;
    private ServiceInfo airTunesService;

    public AirPlayBonjour(String serverName) {
        this.serverName = serverName;
    }

    public void start(int airTunesPort) throws Exception {
        airPlayService = ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                serverName, airTunesPort, 0, 0, airPlayMDNSProps());
        JmmDNS.Factory.getInstance().registerService(airPlayService);
        log.info("{} service is registered on port {}", serverName + AIRPLAY_SERVICE_TYPE, airTunesPort);

        String airTunesServerName = "010203040506@" + serverName;
        airTunesService = ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps());
        JmmDNS.Factory.getInstance().registerService(airTunesService);
        log.info("{} service is registered on port {}", airTunesServerName + AIRTUNES_SERVICE_TYPE, airTunesPort);
    }

    public void stop() {
        JmmDNS.Factory.getInstance().unregisterService(airPlayService);
        log.info("{} service is unregistered", airPlayService.getName());
        JmmDNS.Factory.getInstance().unregisterService(airTunesService);
        log.info("{} service is unregistered", airTunesService.getName());
    }

    private Map<String, String> airPlayMDNSProps() {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", "01:02:03:04:05:06");
        airPlayMDNSProps.put("features", "0x5A7FFFE4,0x1E"); // 0x5A7FFFF7
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x4");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV3,2");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        airPlayMDNSProps.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        airPlayMDNSProps.put("rmodel", "PC1.0");
        airPlayMDNSProps.put("rrv", "1.01");
        airPlayMDNSProps.put("rsv", "1.00");
        airPlayMDNSProps.put("pcversion", "1715");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "0,1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", "0x5A7FFFF7,0x1E");
        airTunesMDNSProps.put("am", "AppleTV3,2");
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("rhd", "5.6.0.0");
        airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x4");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "3"); // 65537
        airTunesMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        return airTunesMDNSProps;
    }
}
