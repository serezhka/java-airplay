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
 * H.264 from the installed {@code CaptureSource} via {@link VideoClient}.
 *
 * <p>No HomeKit / TLV pairing fallback — if the receiver rejects legacy pair-setup,
 * this process fails with a clear error.
 *
 * <pre>
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.legacy.LegacyMirrorApp \
 *       --args='--host &lt;receiver-ip&gt; --port 7000'
 *
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.legacy.LegacyMirrorApp \
 *       --args='--host &lt;receiver-ip&gt; --port 7000 --duration 15'
 * </pre>
 */
@Slf4j
public final class LegacyMirrorApp {

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

        LegacyFairPlayHandshake.Result fp = LegacyFairPlayHandshake.perform(controlClient);
        log.info("Legacy pair-verify + fp-setup finished");

        NSDictionary rtspSetup1Request = new NSDictionary();
        rtspSetup1Request.put("ekey", LegacyFairPlayHandshake.FIXTURE_EKEY);
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
            throw new IllegalStateException("Video SETUP returned no streams; cannot start mirror capture.");
        }
        log.info("video SETUP ok");

        int videoPort = ((NSDictionary) ((NSArray) videoSetupResponse.get("streams")).getArray()[0])
                .get("dataPort").toJavaObject(Integer.class);

        FairPlayVideoEncryptor videoEncryptor =
                new FairPlayVideoEncryptor(fp.aesKey(), fp.sharedSecret(),
                        Long.toUnsignedString(STREAM_CONNECTION_ID));
        new VideoClient(cli.host, videoPort, videoEncryptor);

        if (cli.durationSeconds > 0) {
            log.info("Streaming H.264 capture for {}s", cli.durationSeconds);
            TimeUnit.SECONDS.sleep(cli.durationSeconds);
            log.info("Duration elapsed; exiting");
            System.exit(0);
        }
        log.info("Streaming H.264 capture; Ctrl+C to stop");
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
                throw new IllegalArgumentException("--host is required (e.g. --host <receiver-ip>)");
            }
            return new Cli(host, port, durationSeconds);
        }
    }
}
