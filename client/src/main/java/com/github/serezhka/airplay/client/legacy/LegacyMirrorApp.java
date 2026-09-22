package com.github.serezhka.airplay.client.legacy;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.video.VideoClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Targeted legacy FairPlay mirror client for AirPlay receivers that still accept
 * pair-setup / pair-verify / fp-setup (older “old protocol” path). Streams
 * GStreamer {@code videotestsrc} H.264 via {@link VideoClient}.
 *
 * <p>No HomeKit / TLV pairing fallback — if the TV rejects legacy pair-setup,
 * this process fails with a clear error.
 *
 * <pre>
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.legacy.LegacyMirrorApp \
 *       --args='--host 192.168.0.101 --port 7000'
 *
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.legacy.LegacyMirrorApp \
 *       --args='--host 192.168.0.101 --port 7000 --duration 15'
 * </pre>
 */
@Slf4j
public final class LegacyMirrorApp {

    /** FairPlay fixture session key (matches lib AirPlayFairPlayTest vectors). */
    private static final byte[] ENCRYPTED_AES_KEY = new byte[]{
            70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
            105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
            -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
            15, -45};
    private static final byte[] AES_KEY = new byte[]{
            116, 39, -113, 75, -84, 63, -70, 20, -55, -65, -37, 125, 86, 89, -128, -6};
    private static final byte[] EIV = Base64.getDecoder().decode("91IdM6RTh4keicMei2GfQA==");
    private static final long STREAM_CONNECTION_ID = -3907568444900622110L;

    private LegacyMirrorApp() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            log.error("Legacy mirror failed: {}", e.getMessage());
            // ControlClient / VideoClient keep Netty threads alive; force exit on failure.
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        log.info("Legacy mirror → {}:{} (duration={}s)", cli.host, cli.port,
                cli.durationSeconds > 0 ? cli.durationSeconds : "until Ctrl+C");

        ControlClient controlClient = new ControlClient(cli.host, cli.port);

        NSDictionary info = controlClient.requestInfo();
        log.info("GET /info ok (keys={})", info.keySet());
        if (log.isDebugEnabled()) {
            log.debug("info:\n{}", info.toXMLPropertyList());
        }

        byte[] sharedSecret = LegacyFairPlayHandshake.perform(controlClient);
        log.info("Legacy pair-verify + fp-setup finished");

        NSDictionary rtspSetup1Request = new NSDictionary();
        rtspSetup1Request.put("ekey", ENCRYPTED_AES_KEY);
        rtspSetup1Request.put("eiv", EIV);
        controlClient.rtspSetup(rtspSetup1Request);

        NSArray videoStreams = new NSArray(1);
        NSDictionary videoStream = new NSDictionary();
        videoStream.put("type", 110);
        videoStream.put("streamConnectionID", STREAM_CONNECTION_ID);
        videoStreams.setValue(0, videoStream);
        NSDictionary rtspSetupVideo = new NSDictionary();
        rtspSetupVideo.put("streams", videoStreams);
        NSDictionary videoSetupResponse = controlClient.rtspSetup(rtspSetupVideo);
        if (videoSetupResponse == null || !videoSetupResponse.containsKey("streams")) {
            throw new IllegalStateException("Video SETUP returned no streams; cannot start videotestsrc mirror.");
        }
        log.info("video SETUP ok");

        int videoPort = ((NSDictionary) ((NSArray) videoSetupResponse.get("streams")).getArray()[0])
                .get("dataPort").toJavaObject(Integer.class);

        FairPlayVideoEncryptor videoEncryptor =
                new FairPlayVideoEncryptor(AES_KEY, sharedSecret, Long.toUnsignedString(STREAM_CONNECTION_ID));
        new VideoClient(cli.host, videoPort, videoEncryptor);

        if (cli.durationSeconds > 0) {
            log.info("Streaming videotestsrc H.264 for {}s", cli.durationSeconds);
            TimeUnit.SECONDS.sleep(cli.durationSeconds);
            log.info("Duration elapsed; exiting");
            System.exit(0);
        }
        log.info("Streaming videotestsrc H.264; Ctrl+C to stop");
        Thread.currentThread().join();
    }

    private record Cli(String host, int port, int durationSeconds) {
        static Cli parse(String[] args) {
            String host = null;
            int port = 7000;
            int durationSeconds = 0;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--duration" -> durationSeconds = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException(
                            "Unknown arg: " + args[i]
                                    + " (expected --host <ip> [--port 7000] [--duration <seconds>])");
                }
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("--host is required (e.g. --host 192.168.0.101)");
            }
            return new Cli(host, port, durationSeconds);
        }
    }
}
