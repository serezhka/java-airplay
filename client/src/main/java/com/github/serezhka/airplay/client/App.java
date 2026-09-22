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
import java.util.Set;

/**
 * Smoke client: legacy pair + FairPlay + mirror video, optional AAC audio.
 * For a TV-targeted video-only entry, see {@link com.github.serezhka.airplay.client.legacy.LegacyMirrorApp}.
 * For HAP TLV (PIN / {@code X-Apple-HKP}) receivers, see {@link com.github.serezhka.airplay.client.hap.HapMirrorApp}.
 *
 * <pre>
 *   ./gradlew :client:run
 *   ./gradlew :client:run --args='--host 192.168.0.208 --port 7000'
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
            Set<ServiceDiscovery.Info> airplayServices = discovery.discover();
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

        byte[] sharedSecret = LegacyFairPlayHandshake.perform(controlClient);
        log.info("Pair-verify + fp-setup finished");

        // FairPlay fixture session (matches lib AirPlayFairPlayTest vectors).
        byte[] encryptedAesKey = new byte[]{70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72, 105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72, -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5, 15, -45};
        // Real senders put 16-byte NSData (base64 in XML dumps). AES-CBC IV must be 16 bytes.
        byte[] eiv = Base64.getDecoder().decode("91IdM6RTh4keicMei2GfQA==");
        NSDictionary rtspSetup1Request = new NSDictionary();
        rtspSetup1Request.put("ekey", encryptedAesKey);
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

        byte[] aesKey = new byte[]{116, 39, -113, 75, -84, 63, -70, 20, -55, -65, -37, 125, 86, 89, -128, -6};
        FairPlayVideoEncryptor videoEncryptor =
                new FairPlayVideoEncryptor(aesKey, sharedSecret, Long.toUnsignedString(streamConnectionID));
        FairPlayAudioEncryptor audioEncryptor = new FairPlayAudioEncryptor(aesKey, eiv, sharedSecret);

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
