package com.github.serezhka.airplay.player.harness;

import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.client.control.ControlClient;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.internal.ControlServer;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("loopback")
class LoopbackControlTest {

    @Test
    void infoAndLegacyPairingAgainstLocalControlServer() throws Exception {
        AirPlayConfig config = new AirPlayConfig();
        config.setServerName("harness-loopback");
        config.setWidth(1280);
        config.setHeight(720);
        config.setFps(30);

        RecordingConsumer consumer = new RecordingConsumer();
        ControlServer controlServer = new ControlServer(config, consumer);
        controlServer.start();
        try {
            ControlClient client = new ControlClient("127.0.0.1", controlServer.getPort());
            NSDictionary info = client.requestInfo();
            assertNotNull(info.get("features"));
            assertNotNull(info.get("name"));

            byte[] pairSetup = client.pairSetup();
            assertTrue(pairSetup.length >= 32, "pair-setup should return Ed25519 public key");

            KeyPair keyPair = new KeyPairGenerator().generateKeyPair();
            Curve25519 curve25519 = Curve25519.getInstance(Curve25519.BEST);
            Curve25519KeyPair curve25519KeyPair = curve25519.generateKeyPair();
            byte[] pairVerify1Request = new byte[68];
            pairVerify1Request[0] = 1;
            System.arraycopy(curve25519KeyPair.getPublicKey(), 0, pairVerify1Request, 4, 32);
            System.arraycopy(((EdDSAPublicKey) keyPair.getPublic()).getAbyte(), 0, pairVerify1Request, 36, 32);
            byte[] pairVerify1Response = client.pairVerify(pairVerify1Request);
            assertTrue(pairVerify1Response.length >= 96, "pair-verify M2 should be 96 bytes");

            byte[] atvPublicKey = Arrays.copyOfRange(pairVerify1Response, 0, 32);
            byte[] sharedSecret = curve25519.calculateAgreement(atvPublicKey, curve25519KeyPair.getPrivateKey());
            MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
            sha512Digest.update("Pair-Verify-AES-Key".getBytes(StandardCharsets.UTF_8));
            sha512Digest.update(sharedSecret);
            assertTrue(sha512Digest.digest().length >= 16);
        } finally {
            controlServer.stop();
        }
    }
}
