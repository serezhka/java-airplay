package com.github.serezhka.airplay.client;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.audio.AudioClient;
import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.client.crypto.FairPlayAudioEncryptor;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import com.github.serezhka.airplay.client.legacy.LegacyFairPlayHandshake;
import com.github.serezhka.airplay.client.video.VideoClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Smoke client: legacy pair + FairPlay + mirror video, optional AAC audio.
 * For a TV-targeted video-only entry, see {@link com.github.serezhka.airplay.client.legacy.LegacyMirrorApp}.
 * For HAP TLV (PIN / {@code X-Apple-HKP}) receivers, see {@link com.github.serezhka.airplay.client.hap.HapMirrorApp}.
 *
 * <pre>
 *   ./gradlew :client:run
 *   ./gradlew :client:run --args='--host &lt;receiver-ip&gt; --port 7000'
 * </pre>
 */
@Slf4j
public class App {

    public static void main(String[] args) throws Exception {
        Cli cli = Cli.parse(args);

        String address;
        int port;
        if (cli.host != null) {
            address = cli.host;
            port = cli.port;
            log.info("Using {}:{}", address, port);
        } else {
            ServiceDiscovery discovery = new ServiceDiscovery();
            var airplayServices = discovery.discover();
            if (airplayServices.isEmpty()) {
                log.info("No airplay services found! Exiting..");
                System.exit(0);
            }
            Map<Integer, ServiceDiscovery.Info> discovered = airplayServices.stream()
                    .collect(HashMap::new, (map, service) -> map.put(map.size(), service), Map::putAll);
            log.info("Discovered services: ");
            discovered.forEach((idx, service) -> log.info("{} - {}", idx, service.name()));
            log.info("Select 0 - {}: ", discovered.size() - 1);
            int idx = new Scanner(System.in).nextInt();
            ServiceDiscovery.Info info = discovered.get(idx);
            log.info("Selected service: {}", info);
            address = info.address();
            port = info.port();
        }

        ControlClient controlClient = new ControlClient(address, port);

        NSDictionary nsDictionary = controlClient.requestInfo();
        log.info("GET /info ok");
        if (log.isDebugEnabled()) {
            log.debug("info:\n{}", nsDictionary.toXMLPropertyList());
        }

        LegacyFairPlayHandshake.Result fp = LegacyFairPlayHandshake.perform(controlClient);
        log.info("Pair-verify + fp-setup finished");

        // Real senders put 16-byte NSData (base64 in XML dumps). AES-CBC IV must be 16 bytes.
        byte[] eiv = Base64.getDecoder().decode("91IdM6RTh4keicMei2GfQA==");
        NSDictionary rtspSetup1Request = new NSDictionary();
        rtspSetup1Request.put("ekey", LegacyFairPlayHandshake.FIXTURE_EKEY);
        rtspSetup1Request.put("eiv", eiv);
        controlClient.rtspSetup(rtspSetup1Request);

        long streamConnectionID = -3907568444900622110L;
        NSArray videoStreams = new NSArray(1);
        NSDictionary videoStream = new NSDictionary();
        videoStream.put("type", 110);
        videoStream.put("streamConnectionID", streamConnectionID);
        videoStreams.setValue(0, videoStream);
        NSDictionary rtspSetupVideo = new NSDictionary();
        rtspSetupVideo.put("streams", videoStreams);
        NSDictionary videoSetupResponse = controlClient.rtspSetup(rtspSetupVideo);
        log.info("video SETUP ok");
        if (log.isDebugEnabled()) {
            log.debug("video setup:\n{}", videoSetupResponse.toXMLPropertyList());
        }

        NSArray audioStreams = new NSArray(1);
        NSDictionary audioStream = new NSDictionary();
        audioStream.put("type", 96);
        audioStream.put("ct", 4); // AAC
        audioStream.put("audioFormat", 0x400000); // AAC_LC_44100_2
        audioStream.put("spf", 1024);
        audioStreams.setValue(0, audioStream);
        NSDictionary rtspSetupAudio = new NSDictionary();
        rtspSetupAudio.put("streams", audioStreams);
        NSDictionary audioSetupResponse = controlClient.rtspSetup(rtspSetupAudio);
        log.info("audio SETUP ok");
        if (log.isDebugEnabled()) {
            log.debug("audio setup:\n{}", audioSetupResponse.toXMLPropertyList());
        }

        FairPlayVideoEncryptor videoEncryptor =
                new FairPlayVideoEncryptor(fp.aesKey(), fp.sharedSecret(),
                        Long.toUnsignedString(streamConnectionID));
        FairPlayAudioEncryptor audioEncryptor =
                new FairPlayAudioEncryptor(fp.aesKey(), eiv, fp.sharedSecret());

        int videoPort = ((NSDictionary) ((NSArray) videoSetupResponse.get("streams")).getArray()[0])
                .get("dataPort").toJavaObject(Integer.class);
        int audioPort = ((NSDictionary) ((NSArray) audioSetupResponse.get("streams")).getArray()[0])
                .get("dataPort").toJavaObject(Integer.class);

        new VideoClient(address, videoPort, videoEncryptor);
        new AudioClient(address, audioPort, audioEncryptor);
        log.info("Streaming video+audio; Ctrl+C to stop");
        Thread.currentThread().join();
    }

    private record Cli(String host, int port) {
        static Cli parse(String[] args) {
            String host = null;
            int port = 7000;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
                }
            }
            return new Cli(host, port);
        }
    }
}
