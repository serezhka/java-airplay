package com.github.serezhka.airplay.app.ffmpeg.process;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * In-process IPv4 DNS for the <em>ffplay child only</em> (not a host OS tweak).
 * <p>
 * Some CDN edges reset IPv6 from this JVM host; ffplay has no IPv4-only switch, so
 * {@link FfplayProcess} sets {@code LD_PRELOAD} on that child to a small helper
 * that resolves names as IPv4. Compiles on Linux when {@code cc} is available;
 * otherwise segment fetches stay dual-stack.
 */
@Slf4j
final class Ipv4Getaddrinfo {

    private static final Object LOCK = new Object();
    private static volatile Path library;
    private static volatile boolean unavailable;

    private Ipv4Getaddrinfo() {
    }

    static void apply(ProcessBuilder processBuilder) {
        Path so = library();
        if (so == null) {
            return;
        }
        Map<String, String> env = processBuilder.environment();
        String existing = env.get("LD_PRELOAD");
        env.put("LD_PRELOAD", existing == null || existing.isBlank() ? so.toString() : so + ":" + existing);
    }

    private static Path library() {
        if (!isLinux() || unavailable) {
            return library;
        }
        Path cached = library;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (library != null || unavailable) {
                return library;
            }
            try {
                library = compile();
                log.info("HLS segment DNS forced to IPv4 ({})", library);
            } catch (Exception e) {
                unavailable = true;
                log.warn("IPv4 DNS helper unavailable, segment fetches stay dual-stack: {}", e.toString());
            }
            return library;
        }
    }

    private static Path compile() throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "airplay-ipv4");
        Files.createDirectories(dir);
        Path source = dir.resolve("force_ipv4_getaddrinfo.c");
        Path so = dir.resolve("libforce_ipv4_getaddrinfo.so");
        try (InputStream in = Ipv4Getaddrinfo.class.getResourceAsStream("/native/force_ipv4_getaddrinfo.c")) {
            if (in == null) {
                throw new IllegalStateException("missing /native/force_ipv4_getaddrinfo.c");
            }
            Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
        }
        Process cc = new ProcessBuilder(
                "cc", "-shared", "-fPIC", "-o", so.toString(), source.toString(), "-ldl")
                .redirectErrorStream(true)
                .start();
        String output = new String(cc.getInputStream().readAllBytes());
        if (!cc.waitFor(20, TimeUnit.SECONDS) || cc.exitValue() != 0 || !Files.isRegularFile(so)) {
            throw new IllegalStateException("cc failed: " + output.trim());
        }
        return so;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
