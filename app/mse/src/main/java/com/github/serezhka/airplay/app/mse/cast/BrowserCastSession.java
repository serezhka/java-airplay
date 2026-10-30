package com.github.serezhka.airplay.app.mse.cast;

import com.github.serezhka.airplay.app.mse.MseBrowsers;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One Chromium tab + CDP binding pipeline shared by browser video/audio cast sources.
 */
@Slf4j
final class BrowserCastSession implements AutoCloseable {

    private static final Object START_LOCK = new Object();
    private static volatile BrowserCastSession shared;

    private final Process browser;
    private final CastCdpSession cdp;
    private final AnnexBAccessUnitSplitter annexBSplitter;
    private final CopyOnWriteArrayList<Consumer<byte[]>> videoSinks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<byte[]>> audioSinks = new CopyOnWriteArrayList<>();
    private final AtomicInteger refCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch firstVideo = new CountDownLatch(1);
    private final AtomicBoolean audioEnabled = new AtomicBoolean();

    private BrowserCastSession(
            Process browser,
            CastCdpSession cdp,
            AnnexBAccessUnitSplitter splitter) {
        this.browser = browser;
        this.cdp = cdp;
        this.annexBSplitter = splitter;
    }

    static BrowserCastSession acquire() throws Exception {
        synchronized (START_LOCK) {
            if (shared != null && !shared.closed.get()) {
                shared.refCount.incrementAndGet();
                return shared;
            }
            BrowserCastSession session = startNew();
            shared = session;
            session.refCount.set(1);
            return session;
        }
    }

    void addVideoSink(Consumer<byte[]> sink) {
        videoSinks.add(sink);
    }

    void removeVideoSink(Consumer<byte[]> sink) {
        videoSinks.remove(sink);
    }

    void addAudioSink(Consumer<byte[]> sink) {
        audioSinks.add(sink);
        if (audioEnabled.compareAndSet(false, true)) {
            try {
                cdp.setCaptureAudio(true);
            } catch (Exception e) {
                log.warn("Could not enable tab audio capture: {}", e.toString());
            }
        }
    }

    void removeAudioSink(Consumer<byte[]> sink) {
        audioSinks.remove(sink);
    }

    boolean awaitFirstVideo(long timeout, TimeUnit unit) throws InterruptedException {
        return firstVideo.await(timeout, unit);
    }

    void release() {
        synchronized (START_LOCK) {
            if (refCount.decrementAndGet() > 0) {
                return;
            }
            if (shared == this) {
                shared = null;
            }
        }
        close();
    }

    private static BrowserCastSession startNew() throws Exception {
        Path data = Files.createTempDirectory("airplay-mse-cast-");
        data.toFile().deleteOnExit();
        Process proc = startBrowser(data);
        CastCdpSession session = CastCdpSession.connect(data, 25_000);
        BrowserCastSession[] ref = new BrowserCastSession[1];
        AnnexBAccessUnitSplitter splitter =
                new AnnexBAccessUnitSplitter(au -> ref[0].dispatchVideo(au));
        BrowserCastSession hub = new BrowserCastSession(proc, session, splitter);
        ref[0] = hub;
        session.setBindingListener(hub::onBindingPayload);
        // getDisplayMedia(audio) must be true at first capture — late __airplayCastEnableAudio
        // cannot add a track without re-capture (set by MseCaptureConfig when audio.src=browser).
        boolean enableAudio = Boolean.parseBoolean(
                System.getProperty("airplay.mse.cast.enableAudio", "false"));
        String inject = loadInjectScript(enableAudio);
        session.installCastPipeline(inject);
        String home = System.getProperty("airplay.mse.cast.home", "https://www.youtube.com");
        session.navigateAndStartCapture(home);
        log.info("MSE browser cast (WebCodecs + CDP binding) home={} audio={}", home, enableAudio);
        return hub;
    }

    private void dispatchVideo(byte[] au) {
        if (firstVideo.getCount() > 0) {
            firstVideo.countDown();
            log.info("MSE cast first in-tab Annex-B AU ({} bytes)", au.length);
        }
        for (Consumer<byte[]> sink : videoSinks) {
            try {
                sink.accept(au);
            } catch (Exception e) {
                log.debug("video sink: {}", e.toString());
            }
        }
    }

    void onBindingPayload(String payload) {
        try {
            CastBindingCodec.Message msg = CastBindingCodec.parse(payload);
            switch (msg) {
                case CastBindingCodec.VideoAnnexB v ->
                        annexBSplitter.accept(v.annexB(), 0, v.annexB().length);
                case CastBindingCodec.AudioAdts a -> {
                    byte[] frame = a.adtsFrame();
                    for (Consumer<byte[]> sink : audioSinks) {
                        try {
                            sink.accept(frame);
                        } catch (Exception e) {
                            log.debug("audio sink: {}", e.toString());
                        }
                    }
                }
                case CastBindingCodec.Event e -> {
                    if ("error".equals(e.kind())) {
                        log.warn("MSE cast page: {}", e.detail());
                    } else if ("log".equals(e.kind()) || "captureStarted".equals(e.kind())) {
                        log.info("MSE cast page [{}]: {}", e.kind(), e.detail());
                    } else {
                        log.debug("MSE cast page event {}: {}", e.kind(), e.detail());
                    }
                }
            }
        } catch (Exception e) {
            String snippet = payload == null ? "null"
                    : payload.length() > 120 ? payload.substring(0, 120) + "…" : payload;
            log.warn("cast binding parse failed: {} payload={}", e.toString(), snippet);
        }
    }

    private static String loadInjectScript(boolean enableAudio) throws IOException {
        try (InputStream in = BrowserCastSession.class.getResourceAsStream("/mse/cast-inject.js")) {
            if (in == null) {
                throw new IOException("Missing /mse/cast-inject.js");
            }
            String js = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return js.replace("__BINDING_NAME__", CastBindingCodec.BINDING_NAME)
                    .replace("__ENABLE_AUDIO__", enableAudio ? "true" : "false");
        }
    }

    static String loadInjectScriptForTest() throws IOException {
        return loadInjectScript(false);
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
        cmd.add("--enable-features=UserMediaScreenCapturingWithSelfBrowserSurface");
        if (Boolean.parseBoolean(System.getProperty("airplay.mse.cast.autoCapture", "false"))) {
            // CI / smoke: accept getDisplayMedia without a picker (synthetic A/V tracks).
            // Do not pair with --auto-accept-camera-and-microphone-capture — that combo
            // aborts Chrome before DevToolsActivePort appears on current builds.
            cmd.add("--use-fake-ui-for-media-stream");
            cmd.add("--use-fake-device-for-media-stream");
        }
        if (Boolean.parseBoolean(System.getProperty("airplay.mse.headless", "false"))) {
            cmd.add("--headless=new");
            cmd.add("--disable-gpu");
        }
        cmd.add("about:blank");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        log.info("MSE cast Chromium pid={} (in-tab WebCodecs)", p.pid());
        return p;
    }

    private static String resolveBrowser() {
        return MseBrowsers.resolveForCast();
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
