package com.github.serezhka.airplay.lib;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Registers airplay/airtunes service mdns
 */
@Slf4j
@RequiredArgsConstructor
public class AirPlayBonjour {

    private static final String AIRPLAY_SERVICE_TYPE = "._airplay._tcp.local";
    private static final String AIRTUNES_SERVICE_TYPE = "._raop._tcp.local";

    private final String serverName;

    private final List<JmDNS> jmDNSList = new ArrayList<>();

    public void start(int airTunesPort) throws Exception {
        NetworkInterface.networkInterfaces()
                .filter(networkInterfaceFilter())
                .flatMap(NetworkInterface::inetAddresses)
                .filter(inetAddressFilter())
                .forEach(inetAddress -> {
                    try {
                        byte[] hardwareAddress = NetworkInterface.getByInetAddress(inetAddress).getHardwareAddress();
                        String mac = hardwareAddressBytesToString(hardwareAddress);

                        JmDNS jmDNS = JmDNS.create(inetAddress);
                        jmDNS.registerService(ServiceInfo.create(serverName + AIRPLAY_SERVICE_TYPE,
                                serverName, airTunesPort, 0, 0, airPlayMDNSProps(mac)));
                        log.info("{} service is registered on address {}, port {}", serverName + AIRPLAY_SERVICE_TYPE,
                                inetAddress.getHostAddress(), airTunesPort);

                        String airTunesServerName = mac.replaceAll(":", "") + "@" + serverName;
                        jmDNS.registerService(ServiceInfo.create(airTunesServerName + AIRTUNES_SERVICE_TYPE,
                                airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps()));
                        log.info("{} service is registered on address {}, port {}", airTunesServerName + AIRTUNES_SERVICE_TYPE,
                                inetAddress.getHostAddress(), airTunesPort);

                        jmDNSList.add(jmDNS);
                    } catch (IOException e) {
                        log.error(e.getMessage());
                    }
                });
    }

    public void stop() {
        for (final JmDNS jmDNS : jmDNSList) {
            jmDNS.unregisterAllServices();
        }
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        // Bit 27 (legacy pairing) off: clients skip pair-pin-start and go FairPlay → SETUP.
        // Legacy Pairing code remains for senders that still use it.
        airPlayMDNSProps.put("features", "0x527FFEE6,0x0");
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x44");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV3,2");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps() {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "1,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("ek", "1");
        airTunesMDNSProps.put("ft", "0x527FFEE6,0x0");
        airTunesMDNSProps.put("am", "AppleTV3,2");
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("sm", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x44");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", "f3769a660475d27b4f6040381d784645e13e21c53e6d2da6a8c3d757086fc336");
        return airTunesMDNSProps;
    }

    private Predicate<NetworkInterface> networkInterfaceFilter() {
        return networkInterface -> {
            try {
                return /*!networkInterface.isLoopback() &&*/ !networkInterface.isPointToPoint() && networkInterface.isUp();
            } catch (SocketException e) {
                return false;
            }
        };
    }

    private Predicate<InetAddress> inetAddressFilter() {
        return inetAddress -> inetAddress instanceof Inet4Address /*|| inetAddress instanceof Inet6Address*/;
    }

    private String hardwareAddressBytesToString(byte[] mac) {
        if (mac == null) {
            return "00:00:00:00:00:00"; // loopback
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? ":" : ""));
        }
        return sb.toString().toUpperCase();
    }
}
