package com.github.serezhka.airplay.app.common.app.menu;

import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.SystemTray;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Tray "Cast" submenu: discovered AirPlay devices (rebuild by <b>replacing</b> the submenu).
 * <p>
 * GTK AppIndicator peers throw {@code IndexOutOfBoundsException} if children are
 * removed/added on an already-attached menu. Populate an unbound {@link Menu}, then
 * {@code parent.add(menu, index)} once so {@code bind()} attaches children cleanly.
 * Exit stays on the root menu and is never rebuilt here.
 */
@Slf4j
public final class CastDeviceMenu {

    public enum DeviceState {
        IDLE,
        CONNECTING,
        CONNECTED
    }

    private final SystemTray systemTray;
    private final Consumer<ServiceDiscovery.Info> onDeviceClick;
    private volatile Menu castMenu;
    private volatile String lastFingerprint = "";

    public CastDeviceMenu(SystemTray systemTray, Consumer<ServiceDiscovery.Info> onDeviceClick) {
        this.systemTray = systemTray;
        this.onDeviceClick = onDeviceClick;
    }

    /**
     * @return {@code true} if the native menu was rebuilt
     */
    public synchronized boolean install(
            List<ServiceDiscovery.Info> devices,
            String activeKey,
            DeviceState state) {
        String fingerprint = fingerprint(devices, activeKey, state);
        if (fingerprint.equals(lastFingerprint) && castMenu != null) {
            return false;
        }

        Menu previous = castMenu;
        castMenu = null;
        if (previous != null) {
            try {
                previous.remove();
            } catch (Exception e) {
                log.debug("Cast submenu remove: {}", e.toString());
            }
        }

        Menu menu = new Menu("Cast");
        if (devices.isEmpty()) {
            MenuItem empty = new MenuItem("(searching…)");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            for (ServiceDiscovery.Info device : devices) {
                ServiceDiscovery.Info target = device;
                MenuItem item = new MenuItem(
                        labelFor(device, activeKey, state),
                        e -> onDeviceClick.accept(target));
                if (state == DeviceState.CONNECTING
                        && activeKey != null
                        && activeKey.equals(device.key())) {
                    item.setEnabled(false);
                }
                menu.add(item);
            }
        }

        // Index 0: above the fixed Separator + Exit on the root menu.
        systemTray.getMenu().add(menu, 0);
        castMenu = menu;
        lastFingerprint = fingerprint;
        log.info("Cast submenu installed: {} device(s), state={} active={}",
                devices.size(), state, activeKey);
        return true;
    }

    public Menu menu() {
        return castMenu;
    }

    public static int childCount(Menu menu) {
        return menu == null ? -1 : menu.getEntries().size();
    }

    static String labelFor(ServiceDiscovery.Info device, String activeKey, DeviceState state) {
        String name = device.name();
        if (activeKey != null && activeKey.equals(device.key())) {
            return switch (state) {
                case CONNECTING -> "⚙  " + name + "…";
                case CONNECTED -> "✓  " + name;
                default -> name;
            };
        }
        return name;
    }

    private static String fingerprint(
            List<ServiceDiscovery.Info> devices, String activeKey, DeviceState state) {
        String keys = devices.stream()
                .map(d -> d.key() + "|" + d.name())
                .collect(Collectors.joining(";"));
        return keys + "#" + Objects.toString(activeKey, "") + "#" + state;
    }
}
