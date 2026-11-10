package com.github.serezhka.airplay.client.discovery;

import com.github.serezhka.airplay.protocol.discovery.AirPlayFeatures;
import com.github.serezhka.airplay.protocol.discovery.AirPlayStatusFlags;
import lombok.extern.slf4j.Slf4j;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lenient multicast DNS PTR browse for {@code _airplay._tcp.local}.
 * <p>
 * Some TV announcements (instance names with {@code "}, odd compression) are dropped by
 * JmDNS/avahi while still answering a plain PTR query — use this as a heal path.
 */
@Slf4j
final class AirplayMdnsProbe {

    private static final byte[] PTR_QUERY = buildPtrQuery();

    private AirplayMdnsProbe() {
    }

    record Found(
            String name,
            String address,
            int port,
            String deviceId,
            long features,
            long flags,
            boolean passwordRequired) {
    }

    static List<Found> browse(InetAddress bind, int waitMs) {
        if (!(bind instanceof Inet4Address)) {
            return List.of();
        }
        Map<String, Found> byKey = new LinkedHashMap<>();
        try (DatagramSocket sock = new DatagramSocket(new InetSocketAddress(bind, 0))) {
            sock.setSoTimeout(Math.max(200, Math.min(waitMs, 1500)));
            try {
                NetworkInterface nif = NetworkInterface.getByInetAddress(bind);
                if (nif != null) {
                    sock.setOption(java.net.StandardSocketOptions.IP_MULTICAST_IF, nif);
                }
            } catch (Exception ignored) {
                // best-effort
            }
            InetSocketAddress mcast = new InetSocketAddress("224.0.0.251", 5353);
            byte[] buf = new byte[8 * 1024];
            long deadline = System.currentTimeMillis() + Math.max(500, waitMs);
            sock.send(new DatagramPacket(PTR_QUERY, PTR_QUERY.length, mcast));
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(pkt);
                } catch (SocketTimeoutException e) {
                    sock.send(new DatagramPacket(PTR_QUERY, PTR_QUERY.length, mcast));
                    continue;
                }
                if (!(pkt.getAddress() instanceof Inet4Address from)) {
                    continue;
                }
                for (Found f : parseResponse(pkt.getData(), pkt.getLength(), from.getHostAddress())) {
                    byKey.putIfAbsent(f.address() + ":" + f.port(), f);
                }
            }
        } catch (Exception e) {
            log.debug("mDNS probe on {} failed: {}", bind.getHostAddress(), e.toString());
        }
        return List.copyOf(byKey.values());
    }

    static List<Found> parseResponse(byte[] data, int length, String packetSourceIp) {
        if (data == null || length < 12) {
            return List.of();
        }
        try {
            DnsMessage msg = DnsMessage.parse(data, length);
            Map<String, Integer> srvPortByInstance = new LinkedHashMap<>();
            Map<String, String> aByHost = new LinkedHashMap<>();
            Map<String, Map<String, String>> txtByInstance = new LinkedHashMap<>();
            List<String> instances = new ArrayList<>();

            for (DnsMessage.Record rr : msg.answers()) {
                String lower = rr.name().toLowerCase();
                switch (rr.type()) {
                    case 12 -> { // PTR
                        if (lower.contains("_airplay._tcp")) {
                            String target = rr.ptrTarget();
                            if (target != null && !target.isBlank()) {
                                instances.add(stripDot(target));
                            }
                        }
                    }
                    case 33 -> { // SRV
                        int port = rr.srvPort();
                        if (port > 0) {
                            srvPortByInstance.put(stripDot(rr.name()), port);
                        }
                    }
                    case 1 -> { // A
                        String ip = rr.aIpv4();
                        if (ip != null) {
                            aByHost.put(stripDot(rr.name()).toLowerCase(), ip);
                        }
                    }
                    case 16 -> { // TXT
                        txtByInstance.put(stripDot(rr.name()), rr.txtMap());
                    }
                    default -> {
                    }
                }
            }

            List<Found> out = new ArrayList<>();
            for (String instance : instances) {
                int port = srvPortByInstance.getOrDefault(instance, 7000);
                Map<String, String> txt = txtByInstance.getOrDefault(instance, Map.of());
                String deviceId = first(txt, "deviceid", "deviceId");
                long features = AirPlayFeatures.parse(first(txt, "features", "ft"));
                long flags = AirPlayStatusFlags.parse(first(txt, "flags", "sf"));
                boolean pw = "true".equalsIgnoreCase(trim(first(txt, "pw")))
                        || AirPlayStatusFlags.passwordRequired(flags)
                        || AirPlayStatusFlags.pinRequired(flags);
                String address = packetSourceIp;
                // Prefer A for SRV target when present in the same packet.
                for (Map.Entry<String, String> e : aByHost.entrySet()) {
                    address = e.getValue();
                    break;
                }
                String display = instance;
                int at = display.toLowerCase().indexOf("._airplay._tcp");
                if (at > 0) {
                    display = display.substring(0, at);
                }
                out.add(new Found(display, address, port, deviceId == null ? "" : deviceId, features, flags, pw));
            }

            // Samsung sometimes answers without a clean PTR in the parsed set but still ships TXT.
            if (out.isEmpty() && !txtByInstance.isEmpty()) {
                for (Map.Entry<String, Map<String, String>> e : txtByInstance.entrySet()) {
                    Map<String, String> txt = e.getValue();
                    if (!txt.containsKey("deviceid") && !txt.containsKey("deviceId")) {
                        continue;
                    }
                    if (!txt.containsKey("features") && !txt.containsKey("ft")) {
                        continue;
                    }
                    String display = e.getKey();
                    int at = display.toLowerCase().indexOf("._airplay._tcp");
                    if (at > 0) {
                        display = display.substring(0, at);
                    }
                    int port = srvPortByInstance.getOrDefault(e.getKey(), 7000);
                    long features = AirPlayFeatures.parse(first(txt, "features", "ft"));
                    long flags = AirPlayStatusFlags.parse(first(txt, "flags", "sf"));
                    boolean pw = "true".equalsIgnoreCase(trim(first(txt, "pw")))
                            || AirPlayStatusFlags.passwordRequired(flags)
                            || AirPlayStatusFlags.pinRequired(flags);
                    out.add(new Found(
                            display,
                            packetSourceIp,
                            port,
                            first(txt, "deviceid", "deviceId") == null
                                    ? ""
                                    : first(txt, "deviceid", "deviceId"),
                            features,
                            flags,
                            pw));
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("mDNS parse from {}: {}", packetSourceIp, e.toString());
            return List.of();
        }
    }

    private static String first(Map<String, String> txt, String... keys) {
        for (String k : keys) {
            String v = txt.get(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
            for (Map.Entry<String, String> e : txt.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && e.getValue() != null && !e.getValue().isBlank()) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String stripDot(String s) {
        if (s == null) {
            return "";
        }
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static byte[] buildPtrQuery() {
        // Header: id=0, flags=0, qdcount=1
        ByteBuffer q = ByteBuffer.allocate(64);
        q.putShort((short) 0);
        q.putShort((short) 0);
        q.putShort((short) 1);
        q.putShort((short) 0);
        q.putShort((short) 0);
        q.putShort((short) 0);
        for (String label : new String[]{"_airplay", "_tcp", "local"}) {
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            q.put((byte) b.length);
            q.put(b);
        }
        q.put((byte) 0);
        q.putShort((short) 12); // PTR
        q.putShort((short) 1); // IN
        byte[] out = new byte[q.position()];
        q.flip();
        q.get(out);
        return out;
    }

    /** Minimal DNS message reader for mDNS AirPlay answers. */
    private static final class DnsMessage {
        private final List<Record> answers;

        private DnsMessage(List<Record> answers) {
            this.answers = answers;
        }

        List<Record> answers() {
            return answers;
        }

        static DnsMessage parse(byte[] data, int length) {
            ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
            buf.getShort(); // id
            buf.getShort(); // flags
            int qd = Short.toUnsignedInt(buf.getShort());
            int an = Short.toUnsignedInt(buf.getShort());
            int ns = Short.toUnsignedInt(buf.getShort());
            int ar = Short.toUnsignedInt(buf.getShort());
            for (int i = 0; i < qd; i++) {
                skipName(buf);
                buf.getShort();
                buf.getShort();
            }
            List<Record> records = new ArrayList<>();
            int total = an + ns + ar;
            for (int i = 0; i < total && buf.remaining() >= 10; i++) {
                String name = readName(buf, data);
                int type = Short.toUnsignedInt(buf.getShort());
                buf.getShort(); // class
                buf.getInt(); // ttl
                int rdlen = Short.toUnsignedInt(buf.getShort());
                if (rdlen < 0 || buf.remaining() < rdlen) {
                    break;
                }
                int rdataStart = buf.position();
                byte[] rdata = new byte[rdlen];
                buf.get(rdata);
                records.add(new Record(name, type, rdata, data, rdataStart));
            }
            return new DnsMessage(records);
        }

        private static void skipName(ByteBuffer buf) {
            while (true) {
                if (!buf.hasRemaining()) {
                    return;
                }
                int len = Byte.toUnsignedInt(buf.get());
                if (len == 0) {
                    return;
                }
                if ((len & 0xC0) == 0xC0) {
                    if (buf.hasRemaining()) {
                        buf.get();
                    }
                    return;
                }
                if (buf.remaining() < len) {
                    return;
                }
                buf.position(buf.position() + len);
            }
        }

        private static String readName(ByteBuffer buf, byte[] packet) {
            StringBuilder sb = new StringBuilder();
            int jumps = 0;
            int pos = buf.position();
            boolean consumed = false;
            while (jumps < 10) {
                if (pos >= packet.length) {
                    break;
                }
                int len = Byte.toUnsignedInt(packet[pos]);
                if (len == 0) {
                    pos++;
                    if (!consumed) {
                        buf.position(pos);
                    }
                    break;
                }
                if ((len & 0xC0) == 0xC0) {
                    if (pos + 1 >= packet.length) {
                        break;
                    }
                    int ptr = ((len & 0x3F) << 8) | Byte.toUnsignedInt(packet[pos + 1]);
                    if (!consumed) {
                        buf.position(pos + 2);
                        consumed = true;
                    }
                    pos = ptr;
                    jumps++;
                    continue;
                }
                pos++;
                if (pos + len > packet.length) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(new String(packet, pos, len, StandardCharsets.UTF_8));
                pos += len;
                if (!consumed) {
                    buf.position(pos);
                }
            }
            return sb.toString();
        }

        private record Record(String name, int type, byte[] rdata, byte[] packet, int rdataOffset) {
            String ptrTarget() {
                if (type != 12 || rdata.length == 0) {
                    return null;
                }
                ByteBuffer b = ByteBuffer.wrap(packet);
                b.position(rdataOffset);
                return readName(b, packet);
            }

            int srvPort() {
                if (type != 33 || rdata.length < 6) {
                    return -1;
                }
                return ((rdata[4] & 0xff) << 8) | (rdata[5] & 0xff);
            }

            String aIpv4() {
                if (type != 1 || rdata.length < 4) {
                    return null;
                }
                return (rdata[0] & 0xff) + "." + (rdata[1] & 0xff) + "."
                        + (rdata[2] & 0xff) + "." + (rdata[3] & 0xff);
            }

            Map<String, String> txtMap() {
                Map<String, String> out = new LinkedHashMap<>();
                if (type != 16) {
                    return out;
                }
                int i = 0;
                while (i < rdata.length) {
                    int len = rdata[i] & 0xff;
                    i++;
                    if (len <= 0 || i + len > rdata.length) {
                        break;
                    }
                    String s = new String(rdata, i, len, StandardCharsets.UTF_8);
                    i += len;
                    int eq = s.indexOf('=');
                    if (eq > 0) {
                        out.put(s.substring(0, eq), s.substring(eq + 1));
                    }
                }
                return out;
            }
        }
    }
}
