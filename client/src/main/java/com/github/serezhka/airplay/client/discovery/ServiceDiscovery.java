package com.github.serezhka.airplay.client.discovery;

import com.github.serezhka.airplay.protocol.discovery.AirPlayFeatures;
import com.github.serezhka.airplay.protocol.discovery.AirPlayStatusFlags;
import lombok.extern.slf4j.Slf4j;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Browse {@code _airplay._tcp} on up IPv4 interfaces.
 * Loopback only when constructed with {@code includeLoopback=true} (self-cast / CI).
 * Supports one-shot {@link #discover()} and a continuous {@link #start(Consumer)} watch.
 * <p>
 * Some receivers (notably TVs whose instance name contains {@code "}) flap in JmDNS:
 * {@code serviceRemoved} fires while the service is still on the LAN. We keep entries
 * until several periodic browses miss them, and heal a poisoned long-lived cache with
 * a short-lived {@link JmDNS} when that happens.
 */
@Slf4j
public class ServiceDiscovery implements AutoCloseable {

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";
    /** Consecutive refresh misses before dropping a previously resolved device. */
    private static final int REMOVE_AFTER_MISSES = 3;

    private final boolean includeLoopback;
    private final AtomicBoolean watching = new AtomicBoolean();
    private final List<JmDNS> jmdnsHandles = new CopyOnWriteArrayList<>();
    private final Map<String, Info> devices = new LinkedHashMap<>();
    private final Map<String, Integer> missCounts = new HashMap<>();
    private volatile Consumer<List<Info>> listener;
    private ScheduledExecutorService refresher;

    public ServiceDiscovery() {
        this(false);
    }

    public ServiceDiscovery(boolean includeLoopback) {
        this.includeLoopback = includeLoopback;
    }

    public List<Info> discover() {
        return discover(Duration.ofSeconds(3));
    }

    public List<Info> discover(Duration timeout) {
        long waitMs = Math.max(500, timeout.toMillis());
        Map<String, Info> byKey = new LinkedHashMap<>();
        List<InetAddress> binds = ipv4Binds(includeLoopback);
        if (binds.isEmpty()) {
            log.warn("No IPv4 interfaces for AirPlay browse");
            return List.of();
        }
        for (InetAddress bind : binds) {
            try (JmDNS jmdns = JmDNS.create(bind)) {
                log.info("Browsing {} on {}", AIRPLAY_SERVICE_TYPE, bind.getHostAddress());
                TimeUnit.MILLISECONDS.sleep(Math.min(waitMs, 1500));
                ServiceInfo[] found = jmdns.list(AIRPLAY_SERVICE_TYPE, waitMs);
                for (ServiceInfo serviceInfo : found) {
                    mergeInto(byKey, serviceInfo, includeLoopback);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Browse on {} failed: {}", bind.getHostAddress(), e.toString());
            }
        }
        return sorted(byKey);
    }

    public synchronized void start(Consumer<List<Info>> onChange) {
        if (watching.getAndSet(true)) {
            this.listener = onChange;
            publish();
            return;
        }
        this.listener = onChange;
        List<InetAddress> binds = ipv4Binds(includeLoopback);
        if (binds.isEmpty()) {
            log.warn("No IPv4 interfaces for AirPlay watch");
            publish();
            return;
        }
        for (InetAddress bind : binds) {
            try {
                JmDNS jmdns = JmDNS.create(bind);
                jmdns.addServiceListener(AIRPLAY_SERVICE_TYPE, new ServiceListener() {
                    @Override
                    public void serviceAdded(ServiceEvent event) {
                        // Some TVs (e.g. Samsung) need longer than the JmDNS default to fill A/AAAA.
                        jmdns.requestServiceInfo(event.getType(), event.getName(), 3_000);
                    }

                    @Override
                    public void serviceRemoved(ServiceEvent event) {
                        // Do not drop from the tray on a single remove: TVs often flap here
                        // while still advertising (JmDNS TTL / quoted instance names).
                        String name = event.getName();
                        log.debug("mDNS serviceRemoved (deferred): {}", name);
                        if (name != null && !name.isBlank()) {
                            jmdns.requestServiceInfo(event.getType(), name, true, 3_000);
                        }
                    }

                    @Override
                    public void serviceResolved(ServiceEvent event) {
                        upsertService(event.getInfo());
                    }
                });
                jmdnsHandles.add(jmdns);
                log.info("Watching {} on {}", AIRPLAY_SERVICE_TYPE, bind.getHostAddress());
            } catch (Exception e) {
                log.warn("Watch on {} failed: {}", bind.getHostAddress(), e.toString());
            }
        }
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "airplay-discovery-refresh");
            t.setDaemon(true);
            return t;
        });
        refresher.scheduleAtFixedRate(this::refreshAll, 2, 8, TimeUnit.SECONDS);
        refreshAll();
    }

    private void refreshAll() {
        if (!watching.get()) {
            return;
        }
        Set<String> seenKeys = new HashSet<>();
        for (JmDNS jmdns : jmdnsHandles) {
            try {
                ServiceInfo[] found = jmdns.list(AIRPLAY_SERVICE_TYPE, 3_000);
                for (ServiceInfo info : found) {
                    seenKeys.addAll(upsertService(info));
                }
            } catch (Exception e) {
                log.debug("Periodic browse: {}", e.toString());
            }
        }
        boolean needHeal;
        synchronized (devices) {
            needHeal = !seenKeys.containsAll(devices.keySet());
        }
        if (needHeal) {
            // Long-lived JmDNS cache can drop a still-live TV; a fresh browser recovers it.
            seenKeys.addAll(ephemeralBrowse());
        }
        boolean changed = false;
        synchronized (devices) {
            for (String key : seenKeys) {
                missCounts.remove(key);
            }
            List<String> drop = new ArrayList<>();
            for (String key : devices.keySet()) {
                if (seenKeys.contains(key)) {
                    continue;
                }
                int misses = missCounts.merge(key, 1, Integer::sum);
                if (misses >= REMOVE_AFTER_MISSES) {
                    drop.add(key);
                }
            }
            for (String key : drop) {
                Info gone = devices.remove(key);
                missCounts.remove(key);
                changed = true;
                log.info("Discovery drop after {} misses: {}", REMOVE_AFTER_MISSES, gone);
            }
        }
        if (changed || !seenKeys.isEmpty()) {
            publish();
        }
    }

    /** @return keys upserted (address:port) */
    private Set<String> upsertService(ServiceInfo serviceInfo) {
        if (serviceInfo == null) {
            return Set.of();
        }
        synchronized (devices) {
            Map<String, Info> scratch = new LinkedHashMap<>();
            mergeInto(scratch, serviceInfo, includeLoopback);
            if (scratch.isEmpty()) {
                return Set.of();
            }
            boolean changed = false;
            for (Map.Entry<String, Info> e : scratch.entrySet()) {
                missCounts.remove(e.getKey());
                Info prev = devices.put(e.getKey(), e.getValue());
                if (prev == null || !prev.equals(e.getValue())) {
                    changed = true;
                }
            }
            if (changed) {
                publish();
            }
            return Set.copyOf(scratch.keySet());
        }
    }

    private Set<String> ephemeralBrowse() {
        Set<String> keys = new HashSet<>();
        for (InetAddress bind : ipv4Binds(includeLoopback)) {
            try (JmDNS fresh = JmDNS.create(bind)) {
                ServiceInfo[] found = fresh.list(AIRPLAY_SERVICE_TYPE, 2_500);
                for (ServiceInfo info : found) {
                    keys.addAll(upsertService(info));
                }
            } catch (Exception e) {
                log.debug("Ephemeral browse on {}: {}", bind.getHostAddress(), e.toString());
            }
        }
        if (!keys.isEmpty()) {
            log.info("Discovery healed {} device(s) via ephemeral browse", keys.size());
        }
        return keys;
    }

    private void publish() {
        Consumer<List<Info>> l = listener;
        if (l == null) {
            return;
        }
        List<Info> snapshot;
        synchronized (devices) {
            snapshot = sorted(devices);
        }
        try {
            l.accept(snapshot);
        } catch (Exception e) {
            log.warn("Discovery listener failed: {}", e.toString());
        }
    }

    @Override
    public synchronized void close() {
        watching.set(false);
        listener = null;
        if (refresher != null) {
            refresher.shutdownNow();
            refresher = null;
        }
        for (JmDNS jmdns : jmdnsHandles) {
            try {
                jmdns.close();
            } catch (Exception ignored) {
            }
        }
        jmdnsHandles.clear();
        synchronized (devices) {
            devices.clear();
            missCounts.clear();
        }
    }

    private static void mergeInto(Map<String, Info> byKey, ServiceInfo serviceInfo, boolean includeLoopback) {
        if (serviceInfo == null) {
            return;
        }
        int port = serviceInfo.getPort();
        if (port <= 0) {
            return;
        }
        String deviceId = firstTxt(serviceInfo, "deviceid", "deviceId");
        long features = AirPlayFeatures.parse(firstTxt(serviceInfo, "features", "ft"));
        long flags = AirPlayStatusFlags.parse(firstTxt(serviceInfo, "flags", "sf"));
        boolean pwTxt = "true".equalsIgnoreCase(trim(firstTxt(serviceInfo, "pw")));
        boolean passwordRequired = pwTxt
                || AirPlayStatusFlags.passwordRequired(flags)
                || AirPlayStatusFlags.pinRequired(flags);
        for (Inet4Address addr : ipv4Addresses(serviceInfo, includeLoopback)) {
            Info info = new Info(
                    serviceInfo.getName(),
                    addr.getHostAddress(),
                    port,
                    passwordRequired,
                    deviceId == null ? "" : deviceId.trim(),
                    features,
                    flags);
            byKey.putIfAbsent(info.address() + ":" + info.port(), info);
        }
    }

    /**
     * Collect IPv4 peers for a resolved service. Prefer {@link ServiceInfo#getInet4Addresses()};
     * fall back to {@link ServiceInfo#getInetAddresses()} and host-name lookup — some receivers
     * leave the typed arrays empty until a late resolve.
     */
    private static List<Inet4Address> ipv4Addresses(ServiceInfo serviceInfo, boolean includeLoopback) {
        LinkedHashMap<String, Inet4Address> byHost = new LinkedHashMap<>();
        Inet4Address[] v4 = serviceInfo.getInet4Addresses();
        if (v4 != null) {
            for (Inet4Address addr : v4) {
                acceptIpv4(byHost, addr, includeLoopback);
            }
        }
        if (byHost.isEmpty()) {
            InetAddress[] any = serviceInfo.getInetAddresses();
            if (any != null) {
                for (InetAddress addr : any) {
                    if (addr instanceof Inet4Address inet4) {
                        acceptIpv4(byHost, inet4, includeLoopback);
                    }
                }
            }
        }
        if (byHost.isEmpty()) {
            for (String host : hostCandidates(serviceInfo)) {
                try {
                    InetAddress resolved = InetAddress.getByName(host);
                    if (resolved instanceof Inet4Address inet4) {
                        acceptIpv4(byHost, inet4, includeLoopback);
                    }
                } catch (Exception ignored) {
                    // keep trying other candidates
                }
            }
        }
        return List.copyOf(byHost.values());
    }

    private static void acceptIpv4(
            Map<String, Inet4Address> byHost, Inet4Address addr, boolean includeLoopback) {
        if (addr == null) {
            return;
        }
        if (addr.isLoopbackAddress() && !includeLoopback) {
            return;
        }
        byHost.putIfAbsent(addr.getHostAddress(), addr);
    }

    private static List<String> hostCandidates(ServiceInfo serviceInfo) {
        List<String> out = new ArrayList<>();
        String[] hosts = serviceInfo.getHostAddresses();
        if (hosts != null) {
            for (String h : hosts) {
                if (h != null && !h.isBlank()) {
                    out.add(h.trim());
                }
            }
        }
        String server = serviceInfo.getServer();
        if (server != null && !server.isBlank()) {
            out.add(server.trim());
        }
        return out;
    }

    private static String firstTxt(ServiceInfo info, String... keys) {
        for (String key : keys) {
            String v = info.getPropertyString(key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static List<Info> sorted(Map<String, Info> byKey) {
        List<Info> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparing(Info::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static List<InetAddress> ipv4Binds(boolean includeLoopback) {
        List<InetAddress> out = new ArrayList<>();
        try {
            NetworkInterface.networkInterfaces()
                    .filter(ni -> {
                        try {
                            if (!ni.isUp() || ni.isVirtual()) {
                                return false;
                            }
                            if (ni.isLoopback()) {
                                return includeLoopback;
                            }
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(a -> a instanceof Inet4Address)
                    .filter(a -> includeLoopback || !a.isLoopbackAddress())
                    .forEach(out::add);
        } catch (Exception e) {
            log.warn("Enumerating interfaces failed: {}", e.toString());
        }
        return out;
    }

    /**
     * @param features         parsed mDNS {@code features} mask (0 if unknown)
     * @param statusFlags      parsed mDNS {@code flags} mask (0 if unknown)
     */
    public record Info(
            String name,
            String address,
            int port,
            boolean passwordRequired,
            String deviceId,
            long features,
            long statusFlags) {

        public Info(String name, String address, int port) {
            this(name, address, port, false, "", 0L, 0L);
        }

        public Info(String name, String address, int port, boolean passwordRequired) {
            this(name, address, port, passwordRequired, "", 0L, 0L);
        }

        public Info(String name, String address, int port, boolean passwordRequired, String deviceId) {
            this(name, address, port, passwordRequired, deviceId, 0L, 0L);
        }

        public String key() {
            return address + ":" + port;
        }

        /** HomeKit-oriented receivers (no legacy pairing bit). */
        public boolean prefersHomeKitPairing() {
            return AirPlayFeatures.prefersHomeKitPairing(features);
        }

        @Override
        public String toString() {
            return name + " (" + address + ":" + port + ")";
        }
    }
}
