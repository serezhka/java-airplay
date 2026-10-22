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
import java.awt.Frame;
import java.awt.Window;
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
 * Tray: Cast submenu + fixed Disconnect + Exit.
 * <p>
 * Stopping cast must not tear down the receiver. Root {@code Disconnect} / {@code Exit}
 * are never rebuilt (GTK AppIndicator is unsafe to mutate during a click). Cast-submenu
 * rebuilds are deferred. An off-screen AWT peer keeps {@code AWTAutoShutdown} from
 * calling {@code System.exit} after dialogs / menu churn.
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
    private final MenuItem disconnectItem;
    private final Frame awtKeepAlive;
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
            disconnectItem = null;
            awtKeepAlive = null;
            return;
        }

        awtKeepAlive = newAwtKeepAlive();

        systemTray.setImage(Objects.requireNonNull(getClass().getResource("/menu/tray_icon.png")));
        // Do not call setStatus — on GTK AppIndicator each status string becomes a
        // non-removable disabled menu row ("java-airplay", "Connecting…", …).

        deviceMenu = new CastDeviceMenu(
                systemTray,
                device -> castExecutor.execute(() -> onDeviceClick(device)));

        // Fixed root entries — never removed when the Cast submenu is replaced.
        disconnectItem = new MenuItem("Disconnect", e -> castExecutor.execute(this::stopCast));
        disconnectItem.setEnabled(false);
        systemTray.getMenu().add(disconnectItem);
        systemTray.getMenu().add(new Separator());
        systemTray.getMenu().add(new MenuItem("Exit", e -> exitApp()));

        scheduleRebuild();

        mirrorCaster.startDiscovery(list -> {
            devices.set(List.copyOf(list));
            log.info("Discovery update: {} device(s)", list.size());
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
            refreshDisconnectItem();
            return;
        }
        log.info("Stopping cast — AirPlay receiver keeps running");
        mirrorCaster.disconnect();
        activeKey.set(null);
        deviceState.set(CastDeviceMenu.DeviceState.IDLE);
        refreshDisconnectItem();
        // Never rebuild the Cast submenu synchronously inside a tray click: GTK can
        // deliver the event to Exit / tear down AWT peers and kill the JVM.
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
        refreshDisconnectItem();
        scheduleRebuild();

        try {
            mirrorCaster.connect(device, this::promptPinOnEdt);
            deviceState.set(CastDeviceMenu.DeviceState.CONNECTED);
            refreshDisconnectItem();
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
        refreshDisconnectItem();
        scheduleRebuild();
    }

    private void refreshDisconnectItem() {
        if (disconnectItem == null) {
            return;
        }
        boolean connected = mirrorCaster.isConnected()
                || deviceState.get() == CastDeviceMenu.DeviceState.CONNECTED;
        disconnectItem.setEnabled(connected);
    }

    private void rebuildMenuNow() {
        if (deviceMenu == null) {
            return;
        }
        deviceMenu.install(
                new ArrayList<>(devices.get()),
                activeKey.get(),
                deviceState.get());
        refreshDisconnectItem();
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
        disposeAwtKeepAlive();
        if (systemTray != null) {
            systemTray.shutdown();
        }
        SpringApplication.exit(context, () -> 0);
        System.exit(0);
    }

    /**
     * Holds an AWT peer for the life of the app so disposing a PIN dialog or
     * rebuilding the tray menu cannot trigger {@code AWTAutoShutdown → System.exit}.
     */
    private static Frame newAwtKeepAlive() {
        Frame frame = new Frame("java-airplay");
        frame.setType(Window.Type.UTILITY);
        frame.setUndecorated(true);
        frame.setBounds(-200, -200, 1, 1);
        frame.setVisible(true);
        return frame;
    }

    private void disposeAwtKeepAlive() {
        if (awtKeepAlive == null) {
            return;
        }
        try {
            awtKeepAlive.setVisible(false);
            awtKeepAlive.dispose();
        } catch (Exception ignored) {
        }
    }
}
