package com.github.serezhka.airplay.client.hap;

import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.video.VideoClient;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirror via Python SETUP bridge ({@code samsung_setup_json.py}) + Java
 * {@link VideoClient}. Pure-Java control still hangs on Samsung phase2 SETUP
 * despite matching ChaCha vectors and {@code plistlib} bodies; Python control
 * remains the measured-working path for SETUP/RECORD.
 *
 * <pre>
 *   ./gradlew :client:run -PmainClass=com.github.serezhka.airplay.client.hap.HapMirrorApp \
 *       --args='--host 192.168.0.101 --port 7000 --pin 3939 --duration 15'
 * </pre>
 */
@Slf4j
public final class HapMirrorApp {

    private static final Pattern JSON_INT = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern JSON_HEX = Pattern.compile("\"(ekey|eiv)\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    private static final Pattern JSON_OK = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");

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
        log.info("HAP mirror (Python SETUP bridge) → {}:{} duration={}s",
                cli.host, cli.port,
                cli.durationSeconds > 0 ? cli.durationSeconds : "until Ctrl+C");

        if (cli.pin == null || cli.pin.isBlank()) {
            throw new IllegalArgumentException("--pin is required (Samsung transient: 3939)");
        }

        Path script = findSetupScript();
        int holdSec = Math.max(cli.durationSeconds + 10, 20);
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().addAll(pythonCommand(
                script.toAbsolutePath().toString(),
                "--host", cli.host,
                "--port", String.valueOf(cli.port),
                "--pin", cli.pin.trim().replace("-", ""),
                "--with-ekey"
        ));
        pb.environment().put("SAMSUNG_SETUP_HOLD_SEC", String.valueOf(holdSec));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process proc = pb.start();
        String jsonLine;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            jsonLine = br.readLine();
        }
        if (jsonLine == null || jsonLine.isBlank()) {
            proc.destroyForcibly();
            throw new IllegalStateException("samsung_setup_json.py produced no output");
        }
        log.info("SETUP bridge → {}", jsonLine);
        if (!jsonOk(jsonLine)) {
            proc.destroyForcibly();
            throw new IllegalStateException("SETUP bridge failed: " + jsonLine);
        }

        int dataPort = requireInt(jsonLine, "dataPort");
        long streamConnectionId = requireLong(jsonLine, "streamConnectionID");
        byte[] ekey = requireHex(jsonLine, "ekey", 16);
        // Empty ECDH secret → raw-master AES-CTR schedule.
        FairPlayVideoEncryptor videoEncryptor = new FairPlayVideoEncryptor(
                ekey, new byte[0], Long.toUnsignedString(streamConnectionId));
        new VideoClient(cli.host, dataPort, videoEncryptor);
        log.info("VideoClient → {}:{} streamConnectionID={}",
                cli.host, dataPort, Long.toUnsignedString(streamConnectionId));

        try {
            if (cli.durationSeconds > 0) {
                log.info("Streaming videotestsrc H.264 for {}s", cli.durationSeconds);
                TimeUnit.SECONDS.sleep(cli.durationSeconds);
                log.info("Duration elapsed; exiting");
            } else {
                log.info("Streaming videotestsrc H.264; Ctrl+C to stop");
                Thread.currentThread().join();
            }
        } finally {
            proc.destroy();
            proc.waitFor(3, TimeUnit.SECONDS);
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }
    }

    private static boolean jsonOk(String json) {
        Matcher m = JSON_OK.matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    private static int requireInt(String json, String key) {
        return Math.toIntExact(requireLong(json, key));
    }

    private static long requireLong(String json, String key) {
        Matcher m = JSON_INT.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                return Long.parseLong(m.group(2));
            }
        }
        throw new IllegalStateException("missing " + key + " in " + json);
    }

    private static byte[] requireHex(String json, String key, int len) {
        Matcher m = JSON_HEX.matcher(json);
        while (m.find()) {
            if (key.equals(m.group(1))) {
                String hex = m.group(2);
                if (hex.length() != len * 2) {
                    throw new IllegalStateException(key + " length " + hex.length());
                }
                byte[] out = new byte[len];
                for (int i = 0; i < len; i++) {
                    out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            }
        }
        throw new IllegalStateException("missing " + key + " in " + json);
    }

    private static Path findSetupScript() {
        Path[] candidates = {
                Path.of("client/scripts/samsung_setup_json.py"),
                Path.of("scripts/samsung_setup_json.py"),
                Path.of("../scripts/samsung_setup_json.py")
        };
        for (Path p : candidates) {
            if (p.toFile().isFile()) {
                return p;
            }
        }
        // Gradle :client:run cwd is often client/
        Path fromUserDir = Path.of(System.getProperty("user.dir"), "scripts", "samsung_setup_json.py");
        if (fromUserDir.toFile().isFile()) {
            return fromUserDir;
        }
        Path fromModule = Path.of(System.getProperty("user.dir"), "client", "scripts", "samsung_setup_json.py");
        if (fromModule.toFile().isFile()) {
            return fromModule;
        }
        throw new IllegalStateException("cannot find samsung_setup_json.py (cwd="
                + System.getProperty("user.dir") + ")");
    }

    private static java.util.List<String> pythonCommand(String... args) {
        String py = python3();
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        // Gradle JVM may be x86_64 under Rosetta; force arm64 for Homebrew/Framework Python.
        if (new java.io.File("/usr/bin/arch").canExecute() && !py.equals("python3")) {
            cmd.add("/usr/bin/arch");
            cmd.add("-arm64");
        }
        cmd.add(py);
        cmd.addAll(java.util.Arrays.asList(args));
        return cmd;
    }

    private static String python3() {
        String[] candidates = {
                "/Library/Frameworks/Python.framework/Versions/3.14/bin/python3",
                "/opt/homebrew/bin/python3",
                "/usr/local/bin/python3",
                "python3"
        };
        for (String c : candidates) {
            if ("python3".equals(c)) {
                return c;
            }
            if (new java.io.File(c).canExecute()) {
                return c;
            }
        }
        return "python3";
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
