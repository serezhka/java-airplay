package com.github.serezhka.airplay.app.common.app.menu;

import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.Separator;
import dorkbox.systemTray.SystemTray;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DISPLAY-required smoke under {@code integrationTest} (not packaged into release fat jars).
 * <pre>./gradlew :app:common:trayCastMenuSmoke</pre>
 */
public final class TrayCastMenuSmokeMain {

    private TrayCastMenuSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        SystemTray tray = SystemTray.get();
        if (tray == null) {
            System.err.println("FAIL SystemTray null (need DISPLAY / tray host)");
            System.exit(1);
        }
        tray.setStatus("cast-smoke");
        tray.getMenu().add(new Separator());
        tray.getMenu().add(new MenuItem("Exit", e -> {
        }));

        MirrorCaster caster = new MirrorCaster();
        AtomicReference<List<ServiceDiscovery.Info>> last = new AtomicReference<>(List.of());
        CountDownLatch sawDevices = new CountDownLatch(1);

        CastDeviceMenu menu = new CastDeviceMenu(
                tray,
                info -> System.out.println("SELECTED\t" + info));

        menu.install(List.of(), null, CastDeviceMenu.DeviceState.IDLE);
        int emptyChildren = CastDeviceMenu.childCount(menu.menu());
        if (emptyChildren < 1) {
            System.err.println("FAIL empty childCount=" + emptyChildren);
            System.exit(2);
        }
        System.out.println("EMPTY_OK childCount=" + emptyChildren);

        caster.startDiscovery(list -> {
            last.set(list);
            if (!list.isEmpty()) {
                sawDevices.countDown();
            }
        });
        if (!sawDevices.await(12, TimeUnit.SECONDS)) {
            List<ServiceDiscovery.Info> oneshot = caster.discover();
            last.set(oneshot);
            if (oneshot.isEmpty()) {
                System.err.println("FAIL no devices");
                caster.close();
                System.exit(3);
            }
        }
        List<ServiceDiscovery.Info> devices = last.get();
        System.out.println("DISCOVERED=" + devices.size());
        devices.forEach(d -> System.out.println("DEVICE\t" + d));

        menu.install(devices, null, CastDeviceMenu.DeviceState.IDLE);
        TimeUnit.MILLISECONDS.sleep(500);
        int withDevices = CastDeviceMenu.childCount(menu.menu());
        if (withDevices < devices.size()) {
            System.err.println("FAIL childCount=" + withDevices + " expected>=" + devices.size());
            caster.close();
            System.exit(4);
        }
        System.out.println("DEVICES_OK childCount=" + withDevices);

        String firstKey = devices.get(0).key();
        menu.install(devices, firstKey, CastDeviceMenu.DeviceState.CONNECTING);
        TimeUnit.MILLISECONDS.sleep(300);
        menu.install(devices, firstKey, CastDeviceMenu.DeviceState.CONNECTED);
        TimeUnit.MILLISECONDS.sleep(300);
        // Second rebuild — GTK mutation path.
        menu.install(devices, firstKey, CastDeviceMenu.DeviceState.CONNECTED);
        TimeUnit.MILLISECONDS.sleep(300);
        System.out.println("REBUILD_OK childCount=" + CastDeviceMenu.childCount(menu.menu()));
        System.out.println("SMOKE_OK");
        caster.close();
        tray.shutdown();
        System.exit(0);
    }
}
