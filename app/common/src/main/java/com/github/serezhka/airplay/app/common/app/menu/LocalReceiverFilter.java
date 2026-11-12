package com.github.serezhka.airplay.app.common.app.menu;

import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Drop our own mDNS advertisement from the tray cast picker.
 * <p>
 * Does <b>not</b> touch {@link ServiceDiscovery} itself — self-cast / CI harnesses that
 * call {@code MirrorCaster.discover()} still see the local instance.
 */
final class LocalReceiverFilter {

    private final String serverName;
    private final Set<String> localIpv4;
    private final Set<String> localDeviceIds;

    LocalReceiverFilter(String serverName) {
        this.serverName = serverName == null ? "" : serverName.trim();
        this.localIpv4 = Collections.unmodifiableSet(collectLocalIpv4());
        this.localDeviceIds = Collections.unmodifiableSet(collectLocalMacs());
    }

    boolean isSelf(ServiceDiscovery.Info device) {
        if (device == null) {
            return false;
        }
        String name = device.name() == null ? "" : device.name().trim();
        if (serverName.isEmpty() || !serverName.equals(name)) {
            return false;
        }
        // Same advertised name on a local address (or our MAC as deviceid) → us.
        if (device.address() != null && localIpv4.contains(device.address())) {
            return true;
        }
        String id = normalizeMac(device.deviceId());
        return !id.isEmpty() && localDeviceIds.contains(id);
    }

    static String normalizeMac(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String hex = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
        if (hex.length() != 12) {
            return raw.trim().toUpperCase(Locale.ROOT);
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    private static Set<String> collectLocalIpv4() {
        Set<String> out = new HashSet<>();
        out.add("127.0.0.1");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Set<String> collectLocalMacs() {
        Set<String> out = new HashSet<>();
        out.add("00:00:00:00:00:00"); // loopback advertiser fallback
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length == 0) {
                    continue;
                }
                StringBuilder sb = new StringBuilder(mac.length * 3 - 1);
                for (int i = 0; i < mac.length; i++) {
                    if (i > 0) {
                        sb.append(':');
                    }
                    sb.append(String.format("%02X", mac[i]));
                }
                out.add(sb.toString());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public String toString() {
        return "LocalReceiverFilter{name=" + serverName
                + ", ipv4=" + localIpv4.size()
                + ", macs=" + localDeviceIds.size() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LocalReceiverFilter that)) {
            return false;
        }
        return Objects.equals(serverName, that.serverName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverName);
    }
}
