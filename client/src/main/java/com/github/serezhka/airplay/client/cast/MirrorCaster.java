package com.github.serezhka.airplay.client.cast;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.audio.AudioClient;
import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.client.crypto.FairPlayAudioEncryptor;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import com.github.serezhka.airplay.client.hap.HapMirrorSession;
import com.github.serezhka.airplay.client.hap.HapPairing;
import com.github.serezhka.airplay.client.hap.HapSocketControl;
import com.github.serezhka.airplay.client.legacy.LegacyFairPlayHandshake;
import com.github.serezhka.airplay.client.legacy.LegacyPairingRejectedException;
import com.github.serezhka.airplay.client.video.VideoClient;
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Discover AirPlay receivers and stream from installed {@code CaptureSource} /
 * {@code AudioSource} factories ({@code client.video.src} / {@code client.audio.src}).
 */
@Slf4j
public final class MirrorCaster implements AutoCloseable {

    @FunctionalInterface
    public interface PinPrompt {
        /**
         * Ask the user for the on-screen PIN.
         *
         * @return PIN digits, or {@code null} if cancelled
         */
        String prompt(ServiceDiscovery.Info device) throws Exception;
    }

    private static final byte[] EIV = Base64.getDecoder().decode("91IdM6RTh4keicMei2GfQA==");
    /** Fixture stream id paired with {@link LegacyFairPlayHandshake#FIXTURE_EKEY}. */
    private static final long STREAM_CONNECTION_ID = -3907568444900622110L;

    private final ServiceDiscovery discovery;
    private final CastCredsStore credsStore;
    private final boolean castVideo;
    private final boolean castAudio;
    private final AtomicReference<Session> session = new AtomicReference<>();

    public MirrorCaster() {
        this(false, true, true);
    }

    /** @param mdnsLoopback include loopback in discovery (self-cast / CI) */
    public MirrorCaster(boolean mdnsLoopback) {
        this(mdnsLoopback, true, true);
    }

    public MirrorCaster(boolean mdnsLoopback, boolean castVideo, boolean castAudio) {
        this(new CastCredsStore(), mdnsLoopback, castVideo, castAudio);
    }

    public MirrorCaster(CastCredsStore credsStore) {
        this(credsStore, false, true, true);
    }

    public MirrorCaster(
            CastCredsStore credsStore, boolean mdnsLoopback, boolean castVideo, boolean castAudio) {
        if (!castVideo && !castAudio) {
            throw new IllegalArgumentException(
                    "At least one of client.video.src / client.audio.src must be enabled");
        }
        this.credsStore = credsStore;
        this.discovery = new ServiceDiscovery(mdnsLoopback);
        this.castVideo = castVideo;
        this.castAudio = castAudio;
    }

    public List<ServiceDiscovery.Info> discover() {
        return discovery.discover(Duration.ofSeconds(3));
    }

    public void startDiscovery(Consumer<List<ServiceDiscovery.Info>> onChange) {
        discovery.start(onChange);
    }

    public Optional<ServiceDiscovery.Info> connectedDevice() {
        Session s = session.get();
        return s == null ? Optional.empty() : Optional.of(s.device());
    }

    public boolean isConnected() {
        return session.get() != null;
    }

    /**
     * Explicit path: blank PIN → capability auto; non-blank → HKP persistent + DataStream.
     */
    public synchronized void connect(ServiceDiscovery.Info device, String pin) throws Exception {
        disconnect();
        String normalized = pin == null ? "" : pin.trim().replace("-", "");
        if (normalized.isEmpty()) {
            session.set(connectAuto(device, null));
        } else {
            session.set(connectWithPin(device, normalized));
        }
        log.info("Casting → {} (video={}, audio={})", device, castVideo, castAudio);
    }

    /**
     * Capability-based connect: HomeKit when advertised / password required; else try
     * legacy FairPlay and fall back to HKP (saved identity or PIN prompt).
     */
    public synchronized void connect(ServiceDiscovery.Info device, PinPrompt pinPrompt) throws Exception {
        disconnect();
        session.set(connectAuto(device, pinPrompt));
        log.info("Casting → {} (video={}, audio={})", device, castVideo, castAudio);
    }

    public synchronized void disconnect() {
        Session s = session.getAndSet(null);
        if (s != null) {
            s.close();
            log.info("Cast disconnected from {}", s.device());
        }
    }

    @Override
    public void close() {
        disconnect();
        discovery.close();
    }

    private Session connectAuto(ServiceDiscovery.Info device, PinPrompt pinPrompt) throws Exception {
        boolean tryHomeKitFirst = device.passwordRequired() || device.prefersHomeKitPairing();
        if (tryHomeKitFirst) {
            return connectHomeKit(device, pinPrompt);
        }
        try {
            Session legacy = connectLegacy(device);
            log.info("{}: legacy FairPlay mirror", device.name());
            return legacy;
        } catch (LegacyPairingRejectedException e) {
            log.info("{} rejected legacy pairing — HKP: {}", device.name(), e.getMessage());
            return connectHomeKit(device, pinPrompt);
        }
    }

    private Session connectHomeKit(ServiceDiscovery.Info device, PinPrompt pinPrompt) throws Exception {
        Optional<CastCredsStore.Entry> saved = resolveCreds(device);
        if (saved.isPresent()) {
            try {
                Session s = connectWithIdentity(device, saved.get());
                log.info("{}: pair-verify + DataStream (saved identity)", device.name());
                return s;
            } catch (Exception e) {
                log.info("{} saved pair-verify failed: {}", device.name(), e.toString());
            }
        }
        if (pinPrompt == null) {
            throw new IllegalStateException(
                    "PIN required by " + device.name() + " but no prompt available");
        }
        return pairAndConnect(device, pinPrompt);
    }

    private Session connectLegacy(ServiceDiscovery.Info device) throws Exception {
        ControlClient control = new ControlClient(device.address(), device.port());
        VideoClient video = null;
        AudioClient audio = null;
        try {
            control.requestInfo();
            LegacyFairPlayHandshake.Result fp = LegacyFairPlayHandshake.perform(control);

            NSDictionary phase1 = new NSDictionary();
            phase1.put("ekey", LegacyFairPlayHandshake.FIXTURE_EKEY);
            phase1.put("eiv", EIV);
            control.rtspSetup(phase1);

            if (castVideo) {
                NSArray videoStreams = new NSArray(1);
                NSDictionary videoStream = new NSDictionary();
                videoStream.put("type", AirPlayStreamType.SCREEN);
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
                        fp.aesKey(), fp.sharedSecret(), Long.toUnsignedString(STREAM_CONNECTION_ID));
                video = new VideoClient(device.address(), videoPort, encryptor);
            }

            if (castAudio) {
                NSArray audioStreams = new NSArray(1);
                NSDictionary audioStream = new NSDictionary();
                audioStream.put("type", AirPlayStreamType.AUDIO_REALTIME);
                audioStream.put("ct", 4); // AAC
                audioStream.put("audioFormat", 0x400000); // AAC_LC_44100_2
                audioStream.put("spf", 1024);
                audioStreams.setValue(0, audioStream);
                NSDictionary audioReq = new NSDictionary();
                audioReq.put("streams", audioStreams);
                NSDictionary audioSetup = control.rtspSetup(audioReq);
                if (audioSetup == null || !audioSetup.containsKey("streams")) {
                    throw new IllegalStateException("legacy audio SETUP returned no streams");
                }
                int audioPort = ((NSDictionary) ((NSArray) audioSetup.get("streams")).getArray()[0])
                        .get("dataPort").toJavaObject(Integer.class);
                FairPlayAudioEncryptor audioEncryptor =
                        new FairPlayAudioEncryptor(fp.aesKey(), EIV, fp.sharedSecret());
                audio = new AudioClient(device.address(), audioPort, audioEncryptor);
            }

            return new Session(device, control, video, audio, null);
        } catch (Exception e) {
            if (audio != null) {
                audio.close();
            }
            if (video != null) {
                video.close();
            }
            control.close();
            throw e;
        }
    }

    private Optional<CastCredsStore.Entry> resolveCreds(ServiceDiscovery.Info device) {
        if (device.deviceId() != null && !device.deviceId().isBlank()) {
            return credsStore.find(device.deviceId());
        }
        return Optional.empty();
    }

    private Session pairAndConnect(ServiceDiscovery.Info device, PinPrompt pinPrompt) throws Exception {
        HapSocketControl control = new HapSocketControl(device.address(), device.port());
        boolean transferred = false;
        try {
            control.setHkpVersion("3");
            control.requestInfo();
            try {
                control.pairPinStart();
                log.info("pair-pin-start ok on {}", device.name());
            } catch (Exception e) {
                log.info("pair-pin-start not available on {}: {}", device, e.toString());
            }
            String pin = pinPrompt.prompt(device);
            if (pin == null) {
                throw new CastCancelledException("PIN entry cancelled for " + device.name());
            }
            String normalized = pin.trim().replace("-", "");
            if (normalized.isEmpty()) {
                throw new IllegalStateException("PIN required by " + device.name());
            }
            Session s = finishPersistentAndStream(control, device, normalized);
            transferred = true;
            return s;
        } catch (CastCancelledException e) {
            throw e;
        } catch (Exception e) {
            throw mapPairingError(e);
        } finally {
            if (!transferred) {
                try {
                    control.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Session connectWithPin(ServiceDiscovery.Info device, String pin) throws Exception {
        HapSocketControl control = new HapSocketControl(device.address(), device.port());
        boolean transferred = false;
        try {
            control.setHkpVersion("3");
            control.requestInfo();
            try {
                control.pairPinStart();
            } catch (Exception ignored) {
            }
            Session s = finishPersistentAndStream(control, device, pin);
            transferred = true;
            return s;
        } catch (Exception e) {
            throw mapPairingError(e);
        } finally {
            if (!transferred) {
                try {
                    control.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Session finishPersistentAndStream(
            HapSocketControl control, ServiceDiscovery.Info device, String pin) throws Exception {
        HapPairing pairing = new HapPairing();
        byte[] shared = pairing.performPersistent(control, pin);
        String deviceId = device.deviceId() == null || device.deviceId().isBlank()
                ? device.address()
                : device.deviceId();
        credsStore.save(new CastCredsStore.Entry(
                deviceId,
                pairing.getPairingId(),
                pairing.ed25519Seed(),
                pairing.getAccessoryPairingId(),
                pairing.getAccessoryLtpk()));
        log.info("Saved cast creds for {} → {}", deviceId, credsStore.path());
        HapMirrorSession hap = HapMirrorSession.startDataStream(
                control, device.address(), device.port(), shared, castVideo, castAudio);
        return new Session(device, null, null, null, hap);
    }

    private Session connectWithIdentity(ServiceDiscovery.Info device, CastCredsStore.Entry entry)
            throws Exception {
        HapSocketControl control = new HapSocketControl(device.address(), device.port());
        boolean transferred = false;
        try {
            control.setHkpVersion("3");
            control.requestInfo();
            HapPairing pairing = new HapPairing(
                    entry.pairingId(),
                    entry.ed25519Seed(),
                    entry.accessoryPairingId(),
                    entry.accessoryLtpk());
            boolean verifyAccessory = entry.accessoryLtpk() != null && entry.accessoryPairingId() != null;
            byte[] shared = pairing.pairVerify(control, verifyAccessory);
            control.enableControlEncryption(shared);
            HapMirrorSession hap = HapMirrorSession.startDataStream(
                    control, device.address(), device.port(), shared, castVideo, castAudio);
            transferred = true;
            return new Session(device, null, null, null, hap);
        } finally {
            if (!transferred) {
                try {
                    control.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Exception mapPairingError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("authentication")
                || lower.contains("server proof")
                || (lower.contains("m4") && lower.contains("error"))) {
            return new IllegalStateException(
                    "Wrong PIN or pairing failed. Check the code on the receiver and try again.", e);
        }
        return e;
    }

    public static final class CastCancelledException extends IllegalStateException {
        public CastCancelledException(String message) {
            super(message);
        }
    }

    private record Session(
            ServiceDiscovery.Info device,
            ControlClient control,
            VideoClient video,
            AudioClient audio,
            HapMirrorSession hap) implements AutoCloseable {
        @Override
        public void close() {
            if (hap != null) {
                hap.close();
                return;
            }
            if (audio != null) {
                audio.close();
            }
            if (video != null) {
                video.close();
            }
            if (control != null) {
                control.close();
            }
        }
    }
}
