package com.github.serezhka.airplay.client.hap;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * HKP3 pair-setup through M6, write {@code dt-creds.json} for pair-verify / DataStream.
 * <pre>
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.hap.PairDumpCredsApp \
 *       --args='--host &lt;receiver-ip&gt; --pin &lt;pin&gt; --device-id &lt;device-id&gt; --out /path/to/dt-creds.json'
 * </pre>
 */
public final class PairDumpCredsApp {

    private PairDumpCredsApp() {
    }

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = 7000;
        String pin = null;
        String deviceId = null;
        Path out = null;
        Path pinFile = null;
        boolean pinStart = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--pin" -> pin = args[++i];
                case "--pin-file" -> pinFile = Path.of(args[++i]);
                case "--device-id" -> deviceId = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--no-pin-start" -> pinStart = false;
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("--host is required");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("--device-id is required (creds JSON key, e.g. deviceid)");
        }
        if (out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if ((pin == null || pin.isBlank()) && pinFile == null) {
            throw new IllegalArgumentException("--pin or --pin-file required");
        }

        HapSocketControl control = new HapSocketControl(host, port);
        control.setHkpVersion("3");
        control.requestInfo();
        if (pinStart) {
            control.pairPinStart();
            System.out.println("pair-pin-start ok");
        }
        if (pin == null || pin.isBlank()) {
            System.out.println("waiting for " + pinFile);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                if (Files.exists(pinFile) && Files.size(pinFile) > 0) {
                    pin = Files.readString(pinFile).trim().replace("-", "");
                    break;
                }
                Thread.sleep(150);
            }
            if (pin == null || pin.isBlank()) {
                throw new IllegalStateException("timeout waiting for PIN in " + pinFile);
            }
        }

        HapPairing pairing = new HapPairing();
        try {
            pairing.performPersistent(control, pin.trim().replace("-", ""));
            System.out.println("pair-setup M6 + pair-verify ok pairingId=" + pairing.getPairingId());
        } catch (Exception e) {
            // M6 may have registered keys even if pair-verify fails — still dump seed.
            if (pairing.getAccessoryLtpk() == null) {
                throw e;
            }
            System.out.println("pair-verify failed after M6 (" + e.getMessage()
                    + "); writing creds anyway");
        }

        byte[] seed = ((EdDSAPrivateKey) pairing.getEd25519KeyPair().getPrivate()).getSeed();
        String accessoryId = pairing.getAccessoryPairingId() == null
                ? ""
                : new String(pairing.getAccessoryPairingId(), StandardCharsets.UTF_8);
        String accessoryLtpk = pairing.getAccessoryLtpk() == null
                ? ""
                : Base64.getEncoder().encodeToString(pairing.getAccessoryLtpk());

        String json = "{\n  \"" + deviceId + "\": {\n"
                + "    \"pairing_id\": \"" + pairing.getPairingId() + "\",\n"
                + "    \"ed25519_seed\": \"" + Base64.getEncoder().encodeToString(seed) + "\",\n"
                + "    \"accessory_pairing_id\": \"" + accessoryId + "\",\n"
                + "    \"accessory_ltpk\": \"" + accessoryLtpk + "\"\n"
                + "  }\n}\n";
        Files.writeString(out, json);
        System.out.println("wrote " + out.toAbsolutePath());
        control.close();
    }
}
