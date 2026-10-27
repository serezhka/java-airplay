package com.github.serezhka.airplay.app.mse.cast;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opens a real Chromium window (native address bar — no iframe), injects a tab-capture
 * bootstrap via CDP, and streams WebCodecs H.264 Annex-B for that tab only.
 */
@Slf4j
public final class BrowserCastCaptureSource implements CaptureSource {

    private final CastCaptureServer server;
    private final Path userData;
    private final Process browser;
    private final CastCdpSession cdp;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BrowserCastCaptureSource(Consumer<byte[]> onAnnexB) {
        CastCaptureServer startedServer = null;
        Path data = null;
        Process proc = null;
        CastCdpSession session = null;
        try {
            startedServer = new CastCaptureServer();
            startedServer.setAnnexBSink(onAnnexB);
            startedServer.start();
            startedServer.resetLatches();
            data = Files.createTempDirectory("airplay-mse-cast-");
            data.toFile().deleteOnExit();
            proc = startBrowser(data);
            session = CastCdpSession.connect(data, 25_000);
            String home = System.getProperty("airplay.mse.cast.home", "https://www.youtube.com");
            String inject = loadInject(startedServer.videoWsUrl(), startedServer.controlWsUrl());
            session.preparePageAndNavigate(inject, home);
            if (!startedServer.awaitReady(25, TimeUnit.SECONDS)) {
                log.warn("Cast inject did not signal ready (page may still be loading)");
            }
            if (!startedServer.awaitCaptureStarted(45, TimeUnit.SECONDS)) {
                log.warn("Cast tab capture did not start within timeout "
                        + "(allow the browser permission prompt if shown)");
            }
            log.info("MSE browser cast capture started home={}", home);
            this.server = startedServer;
            this.userData = data;
            this.browser = proc;
            this.cdp = session;
        } catch (Exception e) {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
            if (proc != null) {
                proc.destroyForcibly();
            }
            if (startedServer != null) {
                try {
                    startedServer.close();
                } catch (Exception ignored) {
                }
            }
            throw new IllegalStateException("Failed to start browser cast capture", e);
        }
    }

    private static String loadInject(String videoWs, String controlWs) throws IOException {
        try (InputStream in = BrowserCastCaptureSource.class.getResourceAsStream("/mse/cast-inject.js")) {
            if (in == null) {
                throw new IOException("Missing /mse/cast-inject.js");
            }
            String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return js.replace("__VIDEO_WS__", videoWs).replace("__CONTROL_WS__", controlWs);
        }
    }

    private static Process startBrowser(Path userData) throws IOException {
        String bin = resolveBrowser();
        if (bin == null) {
            throw new IllegalStateException(
                    "No Chromium/Chrome for cast (set -Dairplay.mse.browser=)");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("--user-data-dir=" + userData.toAbsolutePath());
        cmd.add("--remote-debugging-port=0");
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-sync");
        cmd.add("--disable-features=TranslateUI");
        cmd.add("--autoplay-policy=no-user-gesture-required");
        cmd.add("--disable-session-crashed-bubble");
        cmd.add("--check-for-update-interval=31536000");
        cmd.add("--window-size=1280,800");
        // Accept getDisplayMedia for the active tab (real YouTube page, not an iframe).
        cmd.add("--auto-accept-this-tab-capture");
        if (Boolean.parseBoolean(System.getProperty("airplay.mse.headless", "false"))) {
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
        }
        // Full Chromium chrome (address bar) — not --app — so the user can navigate freely.
        cmd.add("about:blank");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        log.info("MSE cast Chromium pid={} (CDP)", p.pid());
        return p;
    }

    private static String resolveBrowser() {
        // Prefer dedicated cast browser override, then general MSE browser, then Chrome
        // (H.264 WebCodecs is more reliable than distro Chromium on lab hosts).
        for (String prop : new String[]{"airplay.mse.cast.browser", "airplay.mse.browser"}) {
            String override = System.getProperty(prop);
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            for (String candidate : new String[]{
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Chromium.app/Contents/MacOS/Chromium",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
            }) {
                if (Files.isExecutable(Path.of(candidate))) {
                    return candidate;
                }
            }
        }
        for (String candidate : new String[]{
                "google-chrome-stable", "google-chrome", "chrome",
                "chromium", "chromium-browser", "msedge"
        }) {
            if (which(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean which(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary).redirectErrorStream(true).start();
            boolean ok = p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            cdp.close();
        } catch (Exception ignored) {
        }
        try {
            server.stopCast();
        } catch (Exception ignored) {
        }
        if (browser != null) {
            browser.destroy();
            try {
                if (!browser.waitFor(2, TimeUnit.SECONDS)) {
                    browser.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                browser.destroyForcibly();
            }
        }
        try {
            server.close();
        } catch (Exception ignored) {
        }
    }
}
