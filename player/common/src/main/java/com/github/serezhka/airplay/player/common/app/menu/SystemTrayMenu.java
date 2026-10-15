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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tray: Quit, Cast to… (browse / connect / PIN), Disconnect.
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
    private final MenuItem statusItem;

    public SystemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster) {
        this.context = context;
        this.mirrorCaster = mirrorCaster;
        systemTray = SystemTray.get();
        if (systemTray == null) {
            log.warn("Unable to load SystemTray!");
            // Keep fields assigned for null-safe no-op
            castMenu = null;
            disconnectItem = null;
            statusItem = null;
            return;
        }

        systemTray.setImage(Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png")));
        systemTray.setStatus("java-airplay");

        statusItem = new MenuItem("Not casting");
        statusItem.setEnabled(false);
        systemTray.getMenu().add(statusItem);

        castMenu = systemTray.getMenu().add(new Menu("Cast to…"));
        rebuildCastMenu(List.of());

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

    private void rebuildCastMenu(List<ServiceDiscovery.Info> devices) {
        if (castMenu == null) {
            return;
        }
        clearMenu(castMenu);
        castMenu.add(new MenuItem("Refresh…", e -> castExecutor.execute(this::refreshDevices)));
        castMenu.add(new Separator());
        if (devices.isEmpty()) {
            MenuItem empty = new MenuItem("(no devices — Refresh)");
            empty.setEnabled(false);
            castMenu.add(empty);
            return;
        }
        for (ServiceDiscovery.Info device : devices) {
            castMenu.add(new MenuItem(device.toString(), e -> castExecutor.execute(() -> connectTo(device))));
        }
    }

    private static void clearMenu(Menu menu) {
        Entry entry;
        while ((entry = menu.getFirst()) != null) {
            entry.remove();
        }
    }

    private void refreshDevices() {
        setStatus("Searching…");
        try {
            List<ServiceDiscovery.Info> devices = mirrorCaster.discover();
            SwingUtilities.invokeLater(() -> {
                rebuildCastMenu(devices);
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
                    // Request PIN on background… but we're on EDT; kick request after dialog prep.
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
        if (statusItem == null || disconnectItem == null) {
            return;
        }
        if (mirrorCaster.isConnected()) {
            String name = mirrorCaster.connectedDevice().map(ServiceDiscovery.Info::name).orElse("device");
            statusItem.setText("Casting → " + name);
            disconnectItem.setEnabled(true);
            systemTray.setStatus("Casting → " + name);
        } else {
            statusItem.setText("Not casting");
            disconnectItem.setEnabled(false);
            systemTray.setStatus("java-airplay");
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            if (statusItem != null) {
                statusItem.setText(text);
            }
            if (systemTray != null) {
                systemTray.setStatus(text);
            }
        });
    }
}
