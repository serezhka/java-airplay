package com.github.serezhka.airplay.client;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.audio.AudioClient;
import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.client.crypto.FairPlayAudioEncryptor;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.discovery.ServiceDiscovery;
import com.github.serezhka.airplay.client.video.VideoClient;
import lombok.extern.slf4j.Slf4j;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * Smoke client: legacy pair + FairPlay + mirror video, optional AAC audio.
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
        log.info("info:\n{}", nsDictionary.toXMLPropertyList());

        byte[] pairSetupResponseBytes = controlClient.pairSetup();
        log.info("pair-setup response: {} bytes", pairSetupResponseBytes.length);
        if (pairSetupResponseBytes.length < 32) {
            throw new IllegalStateException(
                    "Receiver rejected legacy POST /pair-setup (got " + pairSetupResponseBytes.length
                            + " bytes). Modern AirPlay TVs need HomeKit-style TLV pairing; this smoke client is legacy FairPlay-only.");
        }

        KeyPair keyPair = new KeyPairGenerator().generateKeyPair();
        Curve25519 curve25519 = Curve25519.getInstance(Curve25519.BEST);
        Curve25519KeyPair curve25519KeyPair = curve25519.generateKeyPair();
        byte[] pairVerify1Request = new byte[68];
        pairVerify1Request[0] = 1;
        System.arraycopy(curve25519KeyPair.getPublicKey(), 0, pairVerify1Request, 4, 32);
        System.arraycopy(((EdDSAPublicKey) keyPair.getPublic()).getAbyte(), 0, pairVerify1Request, 36, 32);
        byte[] pairVerify1Response = controlClient.pairVerify(pairVerify1Request);
        if (pairVerify1Response.length < 96) {
            throw new IllegalStateException(
                    "Receiver rejected legacy POST /pair-verify (got " + pairVerify1Response.length
                            + " bytes). Expected 96-byte M2 from a legacy FairPlay receiver.");
        }

        byte[] atvPublicKey = Arrays.copyOfRange(pairVerify1Response, 0, 32);
        byte[] sharedSecret = curve25519.calculateAgreement(atvPublicKey, curve25519KeyPair.getPrivateKey());

        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        sha512Digest.update("Pair-Verify-AES-Key".getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(sharedSecret);
        byte[] sharedSecretSha512AesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);
        sha512Digest.update("Pair-Verify-AES-IV".getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(sharedSecret);
        byte[] sharedSecretSha512AesIV = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        Cipher aesCtr128Encrypt = Cipher.getInstance("AES/CTR/NoPadding");
        aesCtr128Encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecretSha512AesKey, "AES"),
                new IvParameterSpec(sharedSecretSha512AesIV));
        aesCtr128Encrypt.update(Arrays.copyOfRange(pairVerify1Response, 32, 96));

        EdDSAEngine edDSAEngine = new EdDSAEngine();
        edDSAEngine.initSign(keyPair.getPrivate());
        byte[] dataToSign = new byte[64];
        System.arraycopy(curve25519KeyPair.getPublicKey(), 0, dataToSign, 0, 32);
        System.arraycopy(atvPublicKey, 0, dataToSign, 32, 32);
        byte[] signature = aesCtr128Encrypt.update(edDSAEngine.signOneShot(dataToSign));

        byte[] pairVerify2Request = new byte[68];
        System.arraycopy(signature, 0, pairVerify2Request, 4, 64);
        controlClient.pairVerify(pairVerify2Request);
        log.info("Pair-verify finished");

        byte[] fairPlaySetup1Request = new byte[]{70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69};
        controlClient.fpSetup(fairPlaySetup1Request);
        byte[] fairPlaySetup2Request = new byte[]{70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6, -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123, -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128, -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76, 54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92, 1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76, -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50, -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};
        controlClient.fpSetup(fairPlaySetup2Request);

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
        log.info("video setup:\n{}", videoSetupResponse.toXMLPropertyList());

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
        log.info("audio setup:\n{}", audioSetupResponse.toXMLPropertyList());

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
