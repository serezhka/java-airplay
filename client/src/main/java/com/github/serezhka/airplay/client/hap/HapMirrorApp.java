package com.github.serezhka.airplay.client.hap;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * CLI: HKP3 persistent pair + DataStream mirror from the installed CaptureSource.
 * <pre>
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.hap.HapMirrorApp \
 *       --args='--host &lt;receiver-ip&gt; --port 7000 --pin &lt;pin&gt; --duration 15'
 * </pre>
 */
@Slf4j
public final class HapMirrorApp {

    private HapMirrorApp() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            log.error("HAP mirror failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        log.info("HAP DataStream mirror → {}:{} duration={}s",
                cli.host, cli.port,
                cli.durationSeconds > 0 ? cli.durationSeconds : "until Ctrl+C");

        if (cli.pin == null || cli.pin.isBlank()) {
            throw new IllegalArgumentException("--pin is required (on-screen code from the receiver)");
        }

        String pin = cli.pin.trim().replace("-", "");
        HapSocketControl control = new HapSocketControl(cli.host, cli.port);
        control.setHkpVersion("3");
        control.requestInfo();
        try {
            control.pairPinStart();
        } catch (Exception e) {
            log.info("pair-pin-start skipped: {}", e.toString());
        }
        HapPairing pairing = new HapPairing();
        byte[] shared = pairing.performPersistent(control, pin);
        try (HapMirrorSession session = HapMirrorSession.startDataStream(
                control, cli.host, cli.port, shared)) {
            if (cli.durationSeconds > 0) {
                log.info("Streaming H.264 capture for {}s", cli.durationSeconds);
                TimeUnit.SECONDS.sleep(cli.durationSeconds);
            } else {
                log.info("Streaming H.264 capture; Ctrl+C to stop");
                Thread.currentThread().join();
            }
        }
    }

    private record Cli(String host, int port, String pin, int durationSeconds) {
        static Cli parse(String[] args) {
            String host = null;
            int port = 7000;
            String pin = null;
            int durationSeconds = 0;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--pin" -> pin = args[++i];
                    case "--duration" -> durationSeconds = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
                }
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("--host is required");
            }
            return new Cli(host, port, pin, durationSeconds);
        }
    }
}
