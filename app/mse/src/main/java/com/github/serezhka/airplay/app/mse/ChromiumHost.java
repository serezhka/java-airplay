package com.github.serezhka.airplay.app.mse;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches system Chromium/Chrome against the local MSE page (JavaFX WebView has no MSE on Linux).
 * Idle: no window until the first mirror/HLS session (lazy start). With an active
 * stream: fullscreen; the page shows a spinner until the first frame.
 */
@Slf4j
final class ChromiumHost implements AutoCloseable {

    private static final int IDLE_WIDTH = 480;
    private static final int IDLE_HEIGHT = 270;

    private final String pageUrl;
    private final Path userData;
    private final boolean hasDisplay;
    private volatile Process process;
    private volatile boolean presentationActive;
    private volatile String browserBin;

    ChromiumHost(String pageUrl) throws IOException {
        this.pageUrl = pageUrl;
        this.userData = Files.createTempDirectory("airplay-mse-chromium-");
        this.userData.toFile().deleteOnExit();
        // xdotool fullscreen/minimize is Linux/X11-only; macOS/Windows still launch the app window.
        this.hasDisplay = Files.exists(Path.of("/tmp/.X11-unix"))
                || System.getenv("DISPLAY") != null;
        // Resolve early so missing-browser fails before the first AirPlay session, not mid-cast.
        this.browserBin = resolveBrowser();
        // Do not startProcess() here — a black idle window at app boot is pointless.
    }

    /** {@code true} if a Chromium/Chrome binary was found at construction time. */
    boolean browserAvailable() {
        return browserBin != null && !browserBin.isBlank();
    }

    synchronized boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /**
     * Ensure a browser is running. Returns true if a new process was started.
     */
    synchronized boolean ensureAlive() throws IOException {
        if (isAlive()) {
            return false;
        }
        if (!browserAvailable()) {
            throw new IllegalStateException(
                    "No Chromium/Chrome found (needed for MSE). Install a browser or set -Dairplay.mse.browser=");
        }
        log.info("MSE Chromium launching url={}", pageUrl);
        destroyQuietly();
        startProcess();
        if (presentationActive) {
            setPresentationActive(true);
        }
        return true;
    }

    /** Kill and start fresh (e.g. process alive but control WebSocket gone). */
    synchronized void restart() throws IOException {
        log.warn("MSE Chromium restart url={}", pageUrl);
        destroyQuietly();
        startProcess();
        if (presentationActive) {
            setPresentationActive(true);
        }
    }

    /**
     * Fullscreen when {@code active} (mirror/HLS playing); otherwise leave fullscreen and
     * shrink/minimize so an empty black page does not cover the desktop.
     */
    synchronized void setPresentationActive(boolean active) {
        presentationActive = active;
        if (headless() || !hasDisplay || !isAlive()) {
            return;
        }
        try {
            String wid = findWindowId();
            if (wid == null || wid.isBlank()) {
                // Window may not exist yet right after launch — retry a few times.
                for (int i = 0; i < 5 && (wid == null || wid.isBlank()); i++) {
                    Thread.sleep(400);
                    wid = findWindowId();
                }
            }
            if (wid == null || wid.isBlank()) {
                log.debug("MSE Chromium window not found yet (pid={})", process.pid());
                return;
            }
            if (active) {
                runXdotool("windowactivate", "--sync", wid);
                // Older xdotool builds lack `windowstate`; fall back to an explicit size.
                if (!runXdotoolOptional("windowstate", "--add", "FULLSCREEN", wid)) {
                    runXdotool("windowmove", wid, "0", "0");
                    runXdotool("windowsize", wid, "1280", "720");
                }
                log.info("MSE Chromium presentation on wid={}", wid);
            } else {
                runXdotoolOptional("windowstate", "--remove", "FULLSCREEN", wid);
                runXdotool("windowsize", wid, String.valueOf(IDLE_WIDTH), String.valueOf(IDLE_HEIGHT));
                runXdotoolOptional("windowminimize", wid);
                log.debug("MSE Chromium presentation off wid={}", wid);
            }
        } catch (Exception e) {
            log.debug("MSE Chromium window state change failed: {}", e.toString());
        }
    }

    private static boolean headless() {
        return Boolean.parseBoolean(System.getProperty("airplay.mse.headless", "false"));
    }

    private void startProcess() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(browserBin);
        cmd.add("--user-data-dir=" + userData.toAbsolutePath());
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-sync");
        cmd.add("--disable-features=TranslateUI");
        cmd.add("--autoplay-policy=no-user-gesture-required");
        cmd.add("--disable-session-crashed-bubble");
        cmd.add("--check-for-update-interval=31536000");
        cmd.add("--window-size=" + IDLE_WIDTH + "," + IDLE_HEIGHT);
        if (headless()) {
            // CI / harness: no window manager. Still loads MSE + WebAudio.
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
        }
        if (Boolean.parseBoolean(System.getProperty("airplay.mse.chrome.noSandbox", "false"))) {
            cmd.add("--no-sandbox");
            cmd.add("--disable-dev-shm-usage");
        }
        // Never --start-fullscreen: empty black fullscreen is worse than a tiny idle window.
        cmd.add("--app=" + pageUrl);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();
        log.info("MSE Chromium started pid={} url={}", process.pid(), pageUrl);
    }

    private String findWindowId() throws IOException, InterruptedException {
        // Prefer the page title. xdotool --pid often returns Chrome's 10x10 stub first;
        // fullscreening that leaves the real airplay-mse window at 480x270 forever.
        String byName = firstXdotoolLine("search", "--name", "airplay-mse");
        if (byName != null && !byName.isBlank()) {
            return byName;
        }
        List<String> byPid = allXdotoolLines("search", "--pid", String.valueOf(process.pid()));
        if (byPid.isEmpty()) {
            return null;
        }
        // Last id is usually the real content window when name search is not ready yet.
        return byPid.get(byPid.size() - 1);
    }

    private static String firstXdotoolLine(String... args) throws IOException, InterruptedException {
        List<String> lines = allXdotoolLines(args);
        return lines.isEmpty() ? null : lines.get(0);
    }

    private static List<String> allXdotoolLines(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("xdotool");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
        return lines;
    }

    private static void runXdotool(String... args) throws IOException, InterruptedException {
        if (!runXdotoolOptional(args)) {
            throw new IOException("xdotool failed: " + String.join(" ", args));
        }
    }

    /** @return false if the command is missing/fails (e.g. no {@code windowstate} on older xdotool) */
    private static boolean runXdotoolOptional(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("xdotool");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String err;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            err = r.readLine();
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return false;
        }
        if (p.exitValue() != 0) {
            return false;
        }
        // xdotool prints "Unknown command" on stdout with exit 1 on some builds; belt and braces:
        return err == null || !err.toLowerCase().contains("unknown command");
    }

    private static String resolveBrowser() {
        return MseBrowsers.resolveForPlayback();
    }

    /**
     * Tear down the browser process (e.g. AirPlay client disconnected). Next session
     * starts a fresh window via {@link #ensureAlive()}.
     */
    synchronized void stop() {
        presentationActive = false;
        destroyQuietly();
    }

    private void destroyQuietly() {
        Process p = process;
        process = null;
        if (p == null) {
            return;
        }
        log.info("MSE Chromium stopping pid={}", p.pid());
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    @Override
    public synchronized void close() {
        destroyQuietly();
    }
}
