package com.github.serezhka.airplay.client.cast;

import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prefer saved pair-verify (no PIN). If credentials are missing, expect a PIN prompt path.
 */
public final class CastAutoConnectSmokeMain {

    private CastAutoConnectSmokeMain() {
    }

    public static void main(String[] args) throws Exception {
        MirrorCaster caster = new MirrorCaster();
        List<ServiceDiscovery.Info> devices = caster.discover();
        System.out.println("DISCOVERED=" + devices.size());
        devices.forEach(d -> System.out.println(
                "DEVICE\t" + d + "\tpw=" + d.passwordRequired() + "\tid=" + d.deviceId()));

        ServiceDiscovery.Info target = devices.stream()
                .filter(d -> d.port() == 7000)
                .filter(d -> !d.address().startsWith("127."))
                .findFirst()
                .orElse(null);
        if (target == null) {
            System.out.println("TARGET_SKIP (no :7000 device on LAN)");
            System.out.println("SMOKE_OK");
            caster.close();
            System.exit(0);
            return;
        }

        AtomicBoolean prompted = new AtomicBoolean();
        try {
            caster.connect(target, device -> {
                prompted.set(true);
                System.out.println("PIN_PROMPT\t" + device);
                return null; // cancel if we unexpectedly need a PIN in saved-creds runs
            });
            System.out.println("CONNECT_OK connected=" + caster.isConnected()
                    + " prompted=" + prompted.get());
            TimeUnit.SECONDS.sleep(3);
        } catch (MirrorCaster.CastCancelledException cancelled) {
            System.out.println("NEEDS_PIN prompted=" + prompted.get() + " (" + cancelled.getMessage() + ")");
            if (!prompted.get()) {
                System.err.println("FAIL expected PIN prompt when creds missing");
                System.exit(2);
            }
            System.out.println("SMOKE_OK");
            caster.close();
            System.exit(0);
            return;
        } catch (Exception e) {
            System.err.println("FAIL " + e);
            e.printStackTrace(System.err);
            System.exit(3);
            return;
        } finally {
            caster.disconnect();
            caster.close();
        }
        System.out.println("SMOKE_OK");
        System.exit(0);
    }
}
