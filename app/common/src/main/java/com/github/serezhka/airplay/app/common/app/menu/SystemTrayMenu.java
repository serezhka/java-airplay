package com.github.serezhka.airplay.app.common.app.menu;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.Separator;
import dorkbox.systemTray.SystemTray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tray: Cast submenu + Exit.
 * Click a device to connect; click again to disconnect. Stopping cast must not
 * tear down the receiver — Exit stays fixed on the root menu, and Cast-submenu
 * rebuilds are deferred off the GTK click path.
 */
@Slf4j
public class SystemTrayMenu {

    private final ApplicationContext context;
    private final MirrorCaster mirrorCaster;
    private final boolean castEnabled;
    private final LocalReceiverFilter localFilter;
    private final ExecutorService castExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "airplay-cast");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService uiDebounce = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "airplay-tray-debounce");
        t.setDaemon(true);
        return t;
    });

    private final SystemTray systemTray;
    private final CastDeviceMenu deviceMenu;
    private final AtomicReference<List<ServiceDiscovery.Info>> devices =
            new AtomicReference<>(List.of());
    private final AtomicReference<String> activeKey = new AtomicReference<>();
    private final AtomicReference<CastDeviceMenu.DeviceState> deviceState =
            new AtomicReference<>(CastDeviceMenu.DeviceState.IDLE);
    private final AtomicBoolean rebuildScheduled = new AtomicBoolean();

    public SystemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster) {
        this(context, mirrorCaster, null);
    }

    public SystemTrayMenu(ApplicationContext context, MirrorCaster mirrorCaster, String serverName) {
        this.context = context;
        this.mirrorCaster = mirrorCaster;
        this.castEnabled = mirrorCaster.isCastMediaEnabled();
        this.localFilter = new LocalReceiverFilter(serverName);
        // Same FlatLaf skin on every OS (dialogs + Swing tray peers). Follows OS light/dark.
        installModernSwingLook();
        systemTray = SystemTray.get();
        if (systemTray == null) {
            log.warn("Unable to load SystemTray!");
            deviceMenu = null;
            return;
        }

        systemTray.setImage(Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png")));
        // Do not call setStatus — on GTK AppIndicator each status string becomes a
        // non-removable disabled menu row ("java-airplay", "Connecting…", …).

        deviceMenu = new CastDeviceMenu(
                systemTray,
                device -> castExecutor.execute(() -> onDeviceClick(device)));

        // Exit is fixed on the root menu — never rebuilt (GTK peer safety).
        systemTray.getMenu().add(new Separator());
        systemTray.getMenu().add(new MenuItem("Exit", e -> exitApp()));

        scheduleRebuild();

        if (!castEnabled) {
            log.info("Tray Cast grayed out (client.video.src/audio.src both none); "
                    + "outbound AirPlay discovery skipped");
            return;
        }

        mirrorCaster.startDiscovery(list -> {
            List<ServiceDiscovery.Info> visible = list.stream()
                    .filter(d -> !localFilter.isSelf(d))
                    .toList();
            devices.set(List.copyOf(visible));
            log.info("Discovery update: {} device(s) {} (filtered self)",
                    visible.size(), summarizeDevices(visible));
            // HAP session can end (receiver disconnect, Chrome exit) without a tray click — keep UI in sync.
            if (deviceState.get() == CastDeviceMenu.DeviceState.CONNECTED && !mirrorCaster.isConnected()) {
                clearActive();
            }
            scheduleRebuild();
        });
    }

    /**
     * End the outbound cast session only. Discovery, tray, and the AirPlay receiver stay up.
     */
    private void stopCast() {
        boolean wasCasting = mirrorCaster.isConnected()
                || deviceState.get() != CastDeviceMenu.DeviceState.IDLE;
        if (!wasCasting) {
            return;
        }
        log.info("Stopping cast — AirPlay receiver keeps running");
        mirrorCaster.disconnect();
        activeKey.set(null);
        deviceState.set(CastDeviceMenu.DeviceState.IDLE);
        // Never rebuild the Cast submenu synchronously inside a tray click: GTK can
        // deliver the event to Exit while the menu is being replaced.
        scheduleRebuild();
    }

    private void scheduleRebuild() {
        if (deviceMenu == null) {
            return;
        }
        if (!rebuildScheduled.compareAndSet(false, true)) {
            return;
        }
        uiDebounce.schedule(() -> {
            rebuildScheduled.set(false);
            SwingUtilities.invokeLater(this::rebuildMenuNow);
        }, 400, TimeUnit.MILLISECONDS);
    }

    private void onDeviceClick(ServiceDiscovery.Info device) {
        String key = device.key();
        String current = activeKey.get();
        CastDeviceMenu.DeviceState state = deviceState.get();

        if (state == CastDeviceMenu.DeviceState.CONNECTED
                && key.equals(current)
                && mirrorCaster.isConnected()) {
            stopCast();
            return;
        }

        if (state == CastDeviceMenu.DeviceState.CONNECTING) {
            return;
        }

        if (mirrorCaster.isConnected()) {
            mirrorCaster.disconnect();
        }

        activeKey.set(key);
        deviceState.set(CastDeviceMenu.DeviceState.CONNECTING);
        scheduleRebuild();

        try {
            mirrorCaster.connect(device, this::promptPinOnEdt);
            deviceState.set(CastDeviceMenu.DeviceState.CONNECTED);
            scheduleRebuild();
        } catch (MirrorCaster.CastCancelledException cancelled) {
            log.info("{}", cancelled.getMessage());
            clearActive();
        } catch (Exception ex) {
            log.warn("Cast to {} failed: {}", device, ex.toString());
            mirrorCaster.disconnect();
            clearActive();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Cast failed:\n" + ex.getMessage(),
                    "Cast",
                    JOptionPane.ERROR_MESSAGE));
        }
    }

    private static String summarizeDevices(List<ServiceDiscovery.Info> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            ServiceDiscovery.Info d = list.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(CastDeviceMenu.sanitizeTrayLabel(d.name()))
                    .append('@')
                    .append(d.address())
                    .append(':')
                    .append(d.port());
        }
        return sb.append(']').toString();
    }

    private void clearActive() {
        activeKey.set(null);
        deviceState.set(CastDeviceMenu.DeviceState.IDLE);
        scheduleRebuild();
    }

    private void rebuildMenuNow() {
        if (deviceMenu == null) {
            return;
        }
        deviceMenu.install(
                new ArrayList<>(devices.get()),
                activeKey.get(),
                deviceState.get(),
                castEnabled);
    }

    private String promptPinOnEdt(ServiceDiscovery.Info device) throws Exception {
        AtomicReference<String> pin = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            String entered = (String) JOptionPane.showInputDialog(
                    null,
                    "Enter the PIN shown on " + device.name(),
                    "Cast PIN",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    null,
                    "");
            pin.set(entered);
        });
        return pin.get();
    }

    private void exitApp() {
        log.info("Exit requested — shutting down receiver");
        try {
            mirrorCaster.close();
        } catch (Exception ignored) {
        }
        castExecutor.shutdownNow();
        uiDebounce.shutdownNow();
        if (systemTray != null) {
            systemTray.shutdown();
        }
        SpringApplication.exit(context, () -> 0);
        System.exit(0);
    }

    private static void installModernSwingLook() {
        try {
            boolean dark = prefersOsDark();
            if (dark) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("PopupMenu.borderInsets", new java.awt.Insets(4, 4, 4, 4));
            log.info("Tray Swing LAF: Flat{}Laf (system dark={})", dark ? "Dark" : "Light", dark);
        } catch (Exception e) {
            log.debug("FlatLaf not applied: {}", e.toString());
        }
    }

    /** Best-effort OS light/dark; default light if the probe fails. */
    private static boolean prefersOsDark() {
        try {
            if (SystemInfo.isMacOS) {
                return runCapture("defaults", "read", "-g", "AppleInterfaceStyle")
                        .toLowerCase()
                        .contains("dark");
            }
            if (SystemInfo.isWindows) {
                // AppsUseLightTheme = 0x0 → dark apps.
                String out = runCapture("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out.contains("0x0");
            }
            // GNOME / many desktops; ignore failures on pure XFCE/KDE without gsettings.
            String scheme = runCapture("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme.contains("prefer-dark")) {
                return true;
            }
            String theme = runCapture("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
            return theme.toLowerCase().contains("dark");
        } catch (Exception e) {
            return false;
        }
    }

    private static String runCapture(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        p.waitFor(2, TimeUnit.SECONDS);
        return sb.toString();
    }
}
