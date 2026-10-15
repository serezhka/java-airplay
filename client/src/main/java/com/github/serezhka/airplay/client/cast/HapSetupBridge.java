package com.github.serezhka.airplay.client.cast;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs {@code samsung_setup_json.py} (pair + SETUP + RECORD), keeps the process alive
 * so the receiver control session stays open while Java streams video.
 */
@Slf4j
final class HapSetupBridge implements AutoCloseable {

    private static final Pattern JSON_INT = Pattern.compile("\"(\\w+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern JSON_HEX = Pattern.compile("\"(ekey|eiv)\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    private static final Pattern JSON_OK = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");

    private final Process process;
    private final int dataPort;
    private final long streamConnectionId;
    private final byte[] ekey;

    private HapSetupBridge(Process process, int dataPort, long streamConnectionId, byte[] ekey) {
        this.process = process;
        this.dataPort = dataPort;
        this.streamConnectionId = streamConnectionId;
        this.ekey = ekey;
    }

    static HapSetupBridge start(String host, int port, String pin) throws Exception {
        Path script = findSetupScript();
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().addAll(pythonCommand(
                script.toAbsolutePath().toString(),
                "--host", host,
                "--port", String.valueOf(port),
                "--pin", pin,
                "--with-ekey"
        ));
        pb.environment().put("SAMSUNG_SETUP_HOLD_SEC", "3600");
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
        log.info("HAP SETUP bridge → {}", jsonLine);
        if (!jsonOk(jsonLine)) {
            proc.destroyForcibly();
            throw new IllegalStateException("SETUP bridge failed: " + jsonLine);
        }
        return new HapSetupBridge(
                proc,
                requireInt(jsonLine, "dataPort"),
                requireLong(jsonLine, "streamConnectionID"),
                requireHex(jsonLine, "ekey", 16));
    }

    int dataPort() {
        return dataPort;
    }

    long streamConnectionId() {
        return streamConnectionId;
    }

    byte[] ekey() {
        return ekey;
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
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
                Path.of(System.getProperty("user.dir"), "scripts", "samsung_setup_json.py"),
                Path.of(System.getProperty("user.dir"), "client", "scripts", "samsung_setup_json.py")
        };
        for (Path p : candidates) {
            if (p.toFile().isFile()) {
                return p;
            }
        }
        throw new IllegalStateException("cannot find samsung_setup_json.py (cwd="
                + System.getProperty("user.dir") + ")");
    }

    private static List<String> pythonCommand(String... args) {
        String py = python3();
        ArrayList<String> cmd = new ArrayList<>();
        if (new java.io.File("/usr/bin/arch").canExecute() && !py.equals("python3")) {
            cmd.add("/usr/bin/arch");
            cmd.add("-arm64");
        }
        cmd.add(py);
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    private static String python3() {
        for (String c : new String[]{
                "/usr/bin/python3",
                "/opt/homebrew/bin/python3",
                "/usr/local/bin/python3",
                "python3"
        }) {
            if ("python3".equals(c) || new java.io.File(c).canExecute()) {
                return c;
            }
        }
        return "python3";
    }
}
