package com.github.serezhka.airplay.app.mse.cast;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Opens a navigable Chromium window ({@code cast-shell.html}) and streams that
 * tab’s pixels (WebCodecs H.264) as Annex-B access units — not the whole desktop.
 */
@Slf4j
public final class BrowserCastCaptureSource implements CaptureSource {

    private final CastCaptureServer server;
    private final Path userData;
    private final Process browser;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BrowserCastCaptureSource(Consumer<byte[]> onAnnexB) {
        try {
            server = new CastCaptureServer();
            server.setAnnexBSink(onAnnexB);
            server.start();
            server.resetLatches();
            userData = Files.createTempDirectory("airplay-mse-cast-");
            userData.toFile().deleteOnExit();
            browser = startBrowser(server.pageUrl(), userData);
            if (!server.awaitReady(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Cast shell page did not signal ready");
            }
            server.startCast(System.getProperty("airplay.mse.cast.home", "https://www.youtube.com"));
            if (!server.awaitCaptureStarted(45, TimeUnit.SECONDS)) {
                log.warn("Cast tab capture did not start within timeout "
                        + "(allow the browser permission prompt if shown)");
            }
            log.info("MSE browser cast capture started page={}", server.pageUrl());
        } catch (Exception e) {
            closeQuietly();
            throw new IllegalStateException("Failed to start browser cast capture", e);
        }
    }

    private static Process startBrowser(String pageUrl, Path userData) throws IOException {
        String bin = resolveBrowser();
        if (bin == null) {
            throw new IllegalStateException(
                    "No Chromium/Chrome for cast (set -Dairplay.mse.browser=)");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(bin);
        cmd.add("--user-data-dir=" + userData.toAbsolutePath());
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-sync");
        cmd.add("--disable-features=TranslateUI");
        cmd.add("--autoplay-policy=no-user-gesture-required");
        cmd.add("--disable-session-crashed-bubble");
        cmd.add("--check-for-update-interval=31536000");
        cmd.add("--window-size=1280,800");
        // Auto-accept getDisplayMedia for THIS tab (cast shell title = airplay-cast).
        cmd.add("--auto-accept-this-tab-capture");
        cmd.add("--auto-select-tab-capture-source-by-title=airplay-cast");
        if (Boolean.parseBoolean(System.getProperty("airplay.mse.headless", "false"))) {
            // Headless cannot do tab capture meaningfully; still launch for wiring tests.
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
        }
        cmd.add("--app=" + pageUrl);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        log.info("MSE cast Chromium pid={} url={}", p.pid(), pageUrl);
        return p;
    }

    private static String resolveBrowser() {
        String override = System.getProperty("airplay.mse.browser");
        if (override != null && !override.isBlank()) {
            return override;
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
                "chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "chrome", "msedge"
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

    private void closeQuietly() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
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
