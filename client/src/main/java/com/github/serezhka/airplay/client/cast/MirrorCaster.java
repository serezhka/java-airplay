package com.github.serezhka.airplay.client.cast;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import com.github.serezhka.airplay.client.legacy.LegacyFairPlayHandshake;
import com.github.serezhka.airplay.client.video.VideoClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discover AirPlay receivers and stream GStreamer {@code videotestsrc} H.264 to one of them.
 * <p>
 * Empty PIN → legacy FairPlay path (this project's receiver).
 * Non-empty PIN → HAP path (TV on-screen code / transient).
 */
@Slf4j
public final class MirrorCaster {

    private static final byte[] ENCRYPTED_AES_KEY = new byte[]{
            70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
            105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
            -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
            15, -45};
    private static final byte[] AES_KEY = new byte[]{
            116, 39, -113, 75, -84, 63, -70, 20, -55, -65, -37, 125, 86, 89, -128, -6};
    private static final byte[] EIV = Base64.getDecoder().decode("91IdM6RTh4keicMei2GfQA==");
    private static final long STREAM_CONNECTION_ID = -3907568444900622110L;

    private final ServiceDiscovery discovery = new ServiceDiscovery();
    private final AtomicReference<Session> session = new AtomicReference<>();

    public List<ServiceDiscovery.Info> discover() {
        return discovery.discover(Duration.ofSeconds(3));
    }

    public Optional<ServiceDiscovery.Info> connectedDevice() {
        Session s = session.get();
        return s == null ? Optional.empty() : Optional.of(s.device());
    }

    public boolean isConnected() {
        return session.get() != null;
    }

    /**
     * @param pin null/blank for legacy; otherwise HAP PIN
     */
    public synchronized void connect(ServiceDiscovery.Info device, String pin) throws Exception {
        disconnect();
        String normalized = pin == null ? "" : pin.trim().replace("-", "");
        if (normalized.isEmpty()) {
            session.set(connectLegacy(device));
        } else {
            session.set(connectHap(device, normalized));
        }
        log.info("Casting videotestsrc → {}", device);
    }

    /** Ask the receiver to show a PIN (best-effort). */
    public void requestPinDisplay(ServiceDiscovery.Info device) {
        try (ControlClient client = new ControlClient(device.address(), device.port())) {
            client.pairPinStart();
        } catch (Exception e) {
            log.info("pair-pin-start not available on {}: {}", device, e.toString());
        }
    }

    public synchronized void disconnect() {
        Session s = session.getAndSet(null);
        if (s != null) {
            s.close();
            log.info("Cast disconnected from {}", s.device());
        }
    }

    private Session connectLegacy(ServiceDiscovery.Info device) throws Exception {
        ControlClient control = new ControlClient(device.address(), device.port());
        try {
            control.requestInfo();
            byte[] sharedSecret = LegacyFairPlayHandshake.perform(control);

            NSDictionary phase1 = new NSDictionary();
            phase1.put("ekey", ENCRYPTED_AES_KEY);
            phase1.put("eiv", EIV);
            control.rtspSetup(phase1);

            NSArray videoStreams = new NSArray(1);
            NSDictionary videoStream = new NSDictionary();
            videoStream.put("type", 110);
            videoStream.put("streamConnectionID", STREAM_CONNECTION_ID);
            videoStreams.setValue(0, videoStream);
            NSDictionary streamsReq = new NSDictionary();
            streamsReq.put("streams", videoStreams);
            NSDictionary videoSetup = control.rtspSetup(streamsReq);
            if (videoSetup == null || !videoSetup.containsKey("streams")) {
                throw new IllegalStateException("legacy video SETUP returned no streams");
            }
            int videoPort = ((NSDictionary) ((NSArray) videoSetup.get("streams")).getArray()[0])
                    .get("dataPort").toJavaObject(Integer.class);

            FairPlayVideoEncryptor encryptor = new FairPlayVideoEncryptor(
                    AES_KEY, sharedSecret, Long.toUnsignedString(STREAM_CONNECTION_ID));
            VideoClient video = new VideoClient(device.address(), videoPort, encryptor);
            return new Session(device, control, video, null);
        } catch (Exception e) {
            control.close();
            throw e;
        }
    }

    private Session connectHap(ServiceDiscovery.Info device, String pin) throws Exception {
        HapSetupBridge bridge = HapSetupBridge.start(device.address(), device.port(), pin);
        try {
            FairPlayVideoEncryptor encryptor = new FairPlayVideoEncryptor(
                    bridge.ekey(), new byte[0], Long.toUnsignedString(bridge.streamConnectionId()));
            VideoClient video = new VideoClient(device.address(), bridge.dataPort(), encryptor);
            return new Session(device, null, video, bridge);
        } catch (Exception e) {
            bridge.close();
            throw e;
        }
    }

    private record Session(
            ServiceDiscovery.Info device,
            ControlClient control,
            VideoClient video,
            HapSetupBridge hapBridge) implements AutoCloseable {
        @Override
        public void close() {
            if (video != null) {
                video.close();
            }
            if (control != null) {
                control.close();
            }
            if (hapBridge != null) {
                hapBridge.close();
            }
        }
    }
}
