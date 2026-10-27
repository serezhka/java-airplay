package com.github.serezhka.airplay.app.mse.cast;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Opens a real Chromium window (native address bar), CDP-screencasts that tab to
 * JPEG, encodes H.264 with system {@code ffmpeg}, and pushes Annex-B AUs.
 * <p>
 * Avoids in-page {@code getDisplayMedia} + {@code ws://127.0.0.1} (blocked as mixed
 * content once the tab is on HTTPS such as YouTube).
 */
@Slf4j
public final class BrowserCastCaptureSource implements CaptureSource {

    private final Path userData;
    private final Process browser;
    private final CastCdpSession cdp;
    private final FfmpegH264Pipe ffmpeg;
    private final ScheduledExecutorService pace;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong jpegFrames = new AtomicLong();
    private final AtomicReference<byte[]> lastJpeg = new AtomicReference<>();
    private final CountDownLatch firstFrame = new CountDownLatch(1);

    public BrowserCastCaptureSource(Consumer<byte[]> onAnnexB) {
        Path data = null;
        Process proc = null;
        CastCdpSession session = null;
        FfmpegH264Pipe pipe = null;
        ScheduledExecutorService scheduler = null;
        try {
            data = Files.createTempDirectory("airplay-mse-cast-");
            data.toFile().deleteOnExit();
            pipe = new FfmpegH264Pipe(onAnnexB);
            proc = startBrowser(data);
            session = CastCdpSession.connect(data, 25_000);
            FfmpegH264Pipe pipeRef = pipe;
            session.setJpegListener((jpeg, sid) -> {
                lastJpeg.set(jpeg);
                try {
                    pipeRef.writeJpeg(jpeg);
                    if (jpegFrames.incrementAndGet() == 1) {
                        firstFrame.countDown();
                        log.info("MSE cast first screencast JPEG ({} bytes)", jpeg.length);
                    }
                } catch (IOException e) {
                    log.debug("ffmpeg jpeg write: {}", e.toString());
                }
            });
            String home = System.getProperty("airplay.mse.cast.home", "https://www.youtube.com");
            session.navigateAndScreencast(home);
            if (!firstFrame.await(30, TimeUnit.SECONDS)) {
                log.warn("No CDP screencast frames within timeout");
            }
            // Chromium often emits only one screencast JPEG for a still tab; image2pipe
            // needs a steady cadence or libx264 never leaves the first frame.
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mse-cast-jpeg-pace");
                t.setDaemon(true);
                return t;
            });
            FfmpegH264Pipe paced = pipe;
            scheduler.scheduleAtFixedRate(() -> {
                if (closed.get()) {
                    return;
                }
                byte[] jpeg = lastJpeg.get();
                if (jpeg == null) {
                    return;
                }
                try {
                    paced.writeJpeg(jpeg);
                } catch (IOException e) {
                    log.debug("ffmpeg paced jpeg: {}", e.toString());
                }
            }, 80, 66, TimeUnit.MILLISECONDS);
            log.info("MSE browser cast capture started home={} jpegs={}", home, jpegFrames.get());
            this.userData = data;
            this.browser = proc;
            this.cdp = session;
            this.ffmpeg = pipe;
            this.pace = scheduler;
        } catch (Exception e) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
            if (pipe != null) {
                try {
                    pipe.close();
                } catch (Exception ignored) {
                }
            }
            if (proc != null) {
                proc.destroyForcibly();
            }
            throw new IllegalStateException("Failed to start browser cast capture", e);
        }
    }

    private static Process startBrowser(Path userData) throws IOException {
        String bin = resolveBrowser();
        if (bin == null) {
            throw new IllegalStateException(
                    "No Chromium/Chrome for cast (set -Dairplay.mse.cast.browser=)");
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
        if (Boolean.parseBoolean(System.getProperty("airplay.mse.headless", "false"))) {
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
        }
        cmd.add("about:blank");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        log.info("MSE cast Chromium pid={} (CDP screencast)", p.pid());
        return p;
    }

    private static String resolveBrowser() {
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
        if (pace != null) {
            pace.shutdownNow();
        }
        try {
            cdp.close();
        } catch (Exception ignored) {
        }
        try {
            ffmpeg.close();
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
    }
}
