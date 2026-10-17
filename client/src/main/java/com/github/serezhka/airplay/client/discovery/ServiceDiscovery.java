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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 */
@Slf4j
public class ServiceDiscovery implements AutoCloseable {

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";

    private final boolean includeLoopback;
    private final AtomicBoolean watching = new AtomicBoolean();
    private final List<JmDNS> jmdnsHandles = new CopyOnWriteArrayList<>();
    private final Map<String, Info> devices = new LinkedHashMap<>();
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
                        jmdns.requestServiceInfo(event.getType(), event.getName(), 1500);
                    }

                    @Override
                    public void serviceRemoved(ServiceEvent event) {
                        removeService(event.getInfo());
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
        for (JmDNS jmdns : jmdnsHandles) {
            try {
                ServiceInfo[] found = jmdns.list(AIRPLAY_SERVICE_TYPE, 1500);
                for (ServiceInfo info : found) {
                    upsertService(info);
                }
            } catch (Exception e) {
                log.debug("Periodic browse: {}", e.toString());
            }
        }
        publish();
    }

    private void upsertService(ServiceInfo serviceInfo) {
        if (serviceInfo == null) {
            return;
        }
        synchronized (devices) {
            Map<String, Info> scratch = new LinkedHashMap<>();
            mergeInto(scratch, serviceInfo, includeLoopback);
            if (scratch.isEmpty()) {
                return;
            }
            boolean changed = false;
            for (Map.Entry<String, Info> e : scratch.entrySet()) {
                Info prev = devices.put(e.getKey(), e.getValue());
                if (prev == null || !prev.equals(e.getValue())) {
                    changed = true;
                }
            }
            if (changed) {
                publish();
            }
        }
    }

    private void removeService(ServiceInfo serviceInfo) {
        if (serviceInfo == null) {
            return;
        }
        synchronized (devices) {
            boolean removed = devices.entrySet().removeIf(e ->
                    e.getValue().name().equals(serviceInfo.getName()));
            if (removed) {
                publish();
            }
        }
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
        }
    }

    private static void mergeInto(Map<String, Info> byKey, ServiceInfo serviceInfo, boolean includeLoopback) {
        if (serviceInfo == null) {
            return;
        }
        String deviceId = firstTxt(serviceInfo, "deviceid", "deviceId");
        long features = AirPlayFeatures.parse(firstTxt(serviceInfo, "features", "ft"));
        long flags = AirPlayStatusFlags.parse(firstTxt(serviceInfo, "flags", "sf"));
        boolean pwTxt = "true".equalsIgnoreCase(trim(firstTxt(serviceInfo, "pw")));
        boolean passwordRequired = pwTxt
                || AirPlayStatusFlags.passwordRequired(flags)
                || AirPlayStatusFlags.pinRequired(flags);
        for (InetAddress addr : serviceInfo.getInetAddresses()) {
            if (!(addr instanceof Inet4Address)) {
                continue;
            }
            if (addr.isLoopbackAddress() && !includeLoopback) {
                continue;
            }
            Info info = new Info(
                    serviceInfo.getName(),
                    addr.getHostAddress(),
                    serviceInfo.getPort(),
                    passwordRequired,
                    deviceId == null ? "" : deviceId.trim(),
                    features,
                    flags);
            byKey.putIfAbsent(info.address() + ":" + info.port(), info);
        }
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
