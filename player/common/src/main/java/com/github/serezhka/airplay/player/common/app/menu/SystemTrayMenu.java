package com.github.serezhka.airplay.player.common.app.menu;

import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import dorkbox.systemTray.Entry;
import dorkbox.systemTray.Menu;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.Separator;
import dorkbox.systemTray.SystemTray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tray: Quit, Cast to… (browse / connect / PIN), Disconnect.
 * <p>
 * Status text goes only through {@link SystemTray#setStatus} (a separate MenuItem
 * would duplicate the same line in the menu).
 */
@Slf4j
public class SystemTrayMenu {

    private final ApplicationContext context;
    private final MirrorCaster mirrorCaster;
    private final ExecutorService castExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "airplay-cast");
        t.setDaemon(true);
        return t;
    });

    private final SystemTray systemTray;
    private final Menu castMenu;
    private final MenuItem disconnectItem;
    /** Device rows + placeholder under Cast to… (Refresh/separator stay). */
    private final List<Entry> deviceEntries = new ArrayList<>();

    public SystemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster) {
        this.context = context;
        this.mirrorCaster = mirrorCaster;
        systemTray = SystemTray.get();
        if (systemTray == null) {
            log.warn("Unable to load SystemTray!");
            castMenu = null;
            disconnectItem = null;
            return;
        }

        systemTray.setImage(Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png")));
        systemTray.setStatus("java-airplay");

        castMenu = systemTray.getMenu().add(new Menu("Cast to…"));
        castMenu.add(new MenuItem("Refresh…", e -> castExecutor.execute(this::refreshDevices)));
        castMenu.add(new Separator());
        showDevices(List.of());

        disconnectItem = new MenuItem("Disconnect cast", e -> castExecutor.execute(() -> {
            mirrorCaster.disconnect();
            SwingUtilities.invokeLater(this::refreshStatus);
        }));
        disconnectItem.setEnabled(false);
        systemTray.getMenu().add(disconnectItem);

        systemTray.getMenu().add(new Separator());
        systemTray.getMenu().add(new MenuItem("Quit", event -> {
            castExecutor.execute(mirrorCaster::disconnect);
            castExecutor.shutdownNow();
            systemTray.shutdown();
            SpringApplication.exit(context, () -> 0);
            System.exit(0);
        }));
    }

    private void showDevices(List<ServiceDiscovery.Info> devices) {
        if (castMenu == null) {
            return;
        }
        for (Entry entry : deviceEntries) {
            entry.remove();
        }
        deviceEntries.clear();

        if (devices.isEmpty()) {
            MenuItem empty = new MenuItem("(no devices — Refresh)");
            empty.setEnabled(false);
            deviceEntries.add(castMenu.add(empty));
            return;
        }
        for (ServiceDiscovery.Info device : devices) {
            ServiceDiscovery.Info target = device;
            deviceEntries.add(castMenu.add(new MenuItem(target.toString(),
                    e -> castExecutor.execute(() -> connectTo(target)))));
        }
        log.info("Tray cast menu lists {} device(s)", devices.size());
    }

    private void refreshDevices() {
        setStatus("Searching…");
        try {
            List<ServiceDiscovery.Info> devices = mirrorCaster.discover();
            log.info("Tray refresh got {} device(s): {}", devices.size(), devices);
            SwingUtilities.invokeLater(() -> {
                showDevices(devices);
                refreshStatus();
                if (devices.isEmpty()) {
                    JOptionPane.showMessageDialog(null,
                            "No AirPlay devices found on the LAN.",
                            "Cast",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            });
        } catch (Exception ex) {
            log.warn("Discovery failed: {}", ex.toString());
            SwingUtilities.invokeLater(() -> {
                refreshStatus();
                JOptionPane.showMessageDialog(null,
                        "Discovery failed:\n" + ex.getMessage(),
                        "Cast",
                        JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    private void connectTo(ServiceDiscovery.Info device) {
        setStatus("Connecting to " + device.name() + "…");
        try {
            AtomicReference<String> pinChoice = new AtomicReference<>();
            AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> {
                int needPin = JOptionPane.showConfirmDialog(null,
                        "Does " + device.name() + " require a PIN?\n"
                                + "Yes = show PIN on TV (if supported), then enter it.\n"
                                + "No = legacy path (this project's receiver).",
                        "Cast",
                        JOptionPane.YES_NO_CANCEL_OPTION);
                if (needPin == JOptionPane.CANCEL_OPTION || needPin == JOptionPane.CLOSED_OPTION) {
                    cancelled.set(true);
                    return;
                }
                if (needPin == JOptionPane.YES_OPTION) {
                    new Thread(() -> mirrorCaster.requestPinDisplay(device), "airplay-pin-start").start();
                    String pin = (String) JOptionPane.showInputDialog(
                            null,
                            "Enter the PIN shown on " + device.name(),
                            "Cast PIN",
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            null,
                            "");
                    if (pin == null) {
                        cancelled.set(true);
                        return;
                    }
                    pinChoice.set(pin.trim());
                } else {
                    pinChoice.set("");
                }
            });
            if (Boolean.TRUE.equals(cancelled.get())) {
                SwingUtilities.invokeLater(this::refreshStatus);
                return;
            }
            mirrorCaster.connect(device, pinChoice.get());
            SwingUtilities.invokeLater(this::refreshStatus);
        } catch (Exception ex) {
            log.warn("Cast to {} failed: {}", device, ex.toString());
            mirrorCaster.disconnect();
            SwingUtilities.invokeLater(() -> {
                refreshStatus();
                JOptionPane.showMessageDialog(null,
                        "Cast failed:\n" + ex.getMessage(),
                        "Cast",
                        JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    private void refreshStatus() {
        if (disconnectItem == null || systemTray == null) {
            return;
        }
        if (mirrorCaster.isConnected()) {
            String name = mirrorCaster.connectedDevice().map(ServiceDiscovery.Info::name).orElse("device");
            disconnectItem.setEnabled(true);
            systemTray.setStatus("Casting → " + name);
        } else {
            disconnectItem.setEnabled(false);
            systemTray.setStatus("java-airplay");
        }
    }

    private void setStatus(String text) {
        if (systemTray != null) {
            systemTray.setStatus(text);
        }
    }
}
