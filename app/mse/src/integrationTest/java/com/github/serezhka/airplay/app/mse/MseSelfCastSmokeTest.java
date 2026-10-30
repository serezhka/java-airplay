package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.mse.cast.BrowserCastCaptureSource;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import com.github.serezhka.airplay.client.cast.MirrorCaster;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * In-process self-cast: browser tab capture → MirrorCaster → local {@link MsePlayer}.
 */
@Tag("mse")
class MseSelfCastSmokeTest {

    @Test
    @Timeout(120)
    void browserCastReachesLocalMseMirror() throws Exception {
        MseCastTestSupport.assumeCastEnvironment();
        MseCastTestSupport.prepareBrowserCastProperties();

        AtomicInteger receivedAus = new AtomicInteger();
        CaptureSources.install(onAnnexB -> new BrowserCastCaptureSource(au -> {
            receivedAus.incrementAndGet();
            onAnnexB.accept(au);
        }));

        AirPlayConfig cfg = new AirPlayConfig();
        cfg.setServerName("JavaAirPlay-MSE-SelfCast");
        cfg.setMdnsLoopback(true);
        cfg.setHlsEnabled(false);
        cfg.setFps(30);

        MsePlayer player = new MsePlayer(30);
        AirPlayServer server = new AirPlayServer(cfg, player);
        try (MirrorCaster caster = new MirrorCaster(true, true, false)) {
            server.start();
            Thread.sleep(1500);

            List<ServiceDiscovery.Info> devices = caster.discover();
            ServiceDiscovery.Info self = devices.stream()
                    .filter(d -> d.name() != null && d.name().contains("SelfCast"))
                    .findFirst()
                    .orElse(null);
            assumeTrue(self != null, "self mDNS instance not found: " + devices);

            caster.connect(self, "");
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (receivedAus.get() > 10 && player.isMirrorActive()) {
                    break;
                }
                Thread.sleep(500);
            }
            assertTrue(receivedAus.get() > 10,
                    "expected Annex-B from browser cast, got " + receivedAus.get());
            assertTrue(player.isMirrorActive(), "MSE mirror never became active");
            Thread.sleep(2000);
            caster.disconnect();
            player.onVideoSrcDisconnect();
        } finally {
            server.stop();
            player.close();
        }
    }
}
