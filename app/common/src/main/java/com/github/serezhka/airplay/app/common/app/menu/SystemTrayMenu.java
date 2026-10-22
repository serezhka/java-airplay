package com.github.serezhka.airplay.app.common.app.menu;

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
        this.context = context;
        this.mirrorCaster = mirrorCaster;
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

        mirrorCaster.startDiscovery(list -> {
            devices.set(List.copyOf(list));
            log.info("Discovery update: {} device(s): {}", list.size(), list);
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
                deviceState.get());
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
}
