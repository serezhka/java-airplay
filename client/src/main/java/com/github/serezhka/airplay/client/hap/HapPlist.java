package com.github.serezhka.airplay.client.hap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Encode binary plists the way CPython {@code plistlib} does (sorted keys, Apple int
 * widths). Samsung's SETUP parser has been observed to hang on {@code dd-plist}
 * binary output for streams SETUP while accepting the same logical dict from
 * {@code plistlib}.
 */
public final class HapPlist {

    private HapPlist() {
    }

    /**
     * @param jsonObject JSON object/array text (numbers as JSON numbers, bools as true/false)
     * @return binary plist bytes
     */
    public static byte[] binaryFromJson(String jsonObject) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().addAll(pythonCommand(
                "-c",
                "import json,plistlib,sys,io;"
                        + "buf=io.BytesIO();"
                        + "plistlib.dump(json.load(sys.stdin), buf, fmt=plistlib.FMT_BINARY);"
                        + "sys.stdout.buffer.write(buf.getvalue())"
        ));
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            os.write(jsonObject.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream(); InputStream err = p.getErrorStream()) {
            in.transferTo(stdout);
            err.transferTo(stderr);
        }
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("plistlib dump timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("plistlib dump failed: "
                    + stderr.toString(StandardCharsets.UTF_8));
        }
        byte[] out = stdout.toByteArray();
        if (out.length < 8 || out[0] != 'b' || out[1] != 'p') {
            throw new IllegalStateException("plistlib returned non-bplist (" + out.length + "B)");
        }
        return out;
    }

    /** Streams SETUP body matching the measured Python probe. */
    public static byte[] streamsSetupType110(long streamConnectionId, int latencyMs, int fps)
            throws Exception {
        long sid = streamConnectionId & Long.MAX_VALUE;
        String json = String.format(Locale.ROOT,
                "{\"streams\":[{\"fps\":%d,\"latencyMs\":%d,\"streamConnectionID\":%d,"
                        + "\"timestampInfo\":[{\"name\":\"SubSu\"},{\"name\":\"BePxT\"},"
                        + "{\"name\":\"AfPxT\"},{\"name\":\"BefEn\"},{\"name\":\"EmEnc\"}],"
                        + "\"type\":110,\"usingScreen\":true}]}",
                fps, latencyMs, sid);
        return binaryFromJson(json);
    }

    public static byte[] phase1(String deviceId, String sessionUuid, int timingPort, int eventPort)
            throws Exception {
        String json = String.format(Locale.ROOT,
                "{\"deviceID\":\"%s\",\"eventPort\":%d,\"sessionUUID\":\"%s\","
                        + "\"timingPort\":%d,\"timingProtocol\":\"NTP\"}",
                deviceId, eventPort, sessionUuid, timingPort);
        return binaryFromJson(json);
    }

    private static java.util.List<String> pythonCommand(String... args) {
        String py = python3();
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
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
}
