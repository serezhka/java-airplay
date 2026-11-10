package com.github.serezhka.airplay.app.gstreamer.util;

import com.sun.jna.Platform;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.PluginFeature;
import org.freedesktop.gstreamer.Registry;
import org.freedesktop.gstreamer.State;
import org.freedesktop.gstreamer.StateChangeReturn;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Post-{@code Gst.init} feature ranking: demote only what fails a short probe.
 * <p>
 * Do not blind-set {@code GST_PLUGIN_FEATURE_RANK} before init. Honors a user-supplied
 * {@code GST_PLUGIN_FEATURE_RANK} and skips auto-tuning.
 * <p>
 * HTTP: Windows {@code curlhttpsrc} hangs in multi — demote it without running a live
 * probe pipeline (a hung probe left running can poison later HLS downloads). Elsewhere,
 * probe curl and prefer it when healthy (soft-stop + soup still risks JVM abort).
 * <p>
 * H.264 HW: only demote READY failures. Do <b>not</b> boost ranks — forcing
 * {@code d3d11h264dec}/{@code nvh264dec} to 300 correlated with YouTube
 * "Couldn't download fragments" / no first picture on the Windows lab box; default
 * registry ranks already pick usable HW when present.
 */
@Slf4j
public final class GstPluginFeatureProbe {

    private static final String[] H264_HW_DECODERS = {
            "nvh264dec",
            "vah264dec",
            "d3d11h264dec",
            "d3d12h264dec",
            "vtdec_h264"
    };

    /** Non-H.264 NVIDIA factories: demote only when READY fails. */
    private static final String[] NV_EXTRA_DECODERS = {
            "nvh265dec", "nvvp8dec", "nvvp9dec", "nvav1dec"
    };

    /** Above {@link PluginFeature.Rank#PRIMARY} so a healthy curl wins over soup. */
    private static final int CURL_PREFERRED_RANK = 300;
    private static final int CURL_PROBE_TIMEOUT_MS = 2500;

    private GstPluginFeatureProbe() {
    }

    /**
     * Probe HTTP sources and video decoders; adjust registry ranks for subsequent autoplug
     * (HLS {@code playbin3}). Safe to call once after {@code Gst.init}.
     */
    public static void tuneAfterInit() {
        String userRank = System.getenv("GST_PLUGIN_FEATURE_RANK");
        if (userRank != null && !userRank.isBlank()) {
            log.info("Honoring GST_PLUGIN_FEATURE_RANK={}", userRank);
            return;
        }
        probeHttpSources();
        demoteBrokenHwDecoders();
        preferNonD3dH264OnWindows();
    }

    /**
     * On the Windows lab box {@code d3d12h264dec} defaults above {@code nvh264dec} (258 vs
     * 257) and {@code d3d11h264dec} can READY while YouTube HLS never presents. Demote both
     * so NVIDIA (or software) wins autoplug into {@code d3d11videosink}.
     */
    private static void preferNonD3dH264OnWindows() {
        if (!Platform.isWindows()) {
            return;
        }
        for (String name : new String[]{"d3d11h264dec", "d3d12h264dec"}) {
            PluginFeature feature = lookup(name);
            if (feature == null) {
                continue;
            }
            feature.setRank(PluginFeature.Rank.SECONDARY);
            log.info("Windows HLS: {} -> SECONDARY (prefer nv/software over d3d H.264)", name);
        }
    }

    private static void probeHttpSources() {
        if (lookup("curlhttpsrc") == null) {
            log.info("curlhttpsrc not installed; leaving souphttpsrc at default rank");
            return;
        }
        // Windows MSVC curlhttpsrc multi-hangs; never start a probe pipeline here.
        if (Platform.isWindows()) {
            setRank("curlhttpsrc", PluginFeature.Rank.NONE.intValue());
            log.info("HTTP src: Windows -> curlhttpsrc NONE (no live probe); souphttpsrc default");
            return;
        }
        boolean curlOk = probeCurlHttpsrc();
        if (curlOk) {
            setRank("curlhttpsrc", CURL_PREFERRED_RANK);
            setRank("souphttpsrc", PluginFeature.Rank.SECONDARY.intValue());
            log.info("HTTP src probe: curlhttpsrc OK -> rank {}; souphttpsrc -> SECONDARY",
                    CURL_PREFERRED_RANK);
        } else {
            setRank("curlhttpsrc", PluginFeature.Rank.NONE.intValue());
            log.info("HTTP src probe: curlhttpsrc unhealthy -> NONE; souphttpsrc keeps default rank");
        }
    }

    /**
     * Short localhost GET through {@code curlhttpsrc}. A hang or error demotes curl so
     * soup can serve HLS (known-good on Windows MSVC builds where curl multi stalls).
     */
    private static boolean probeCurlHttpsrc() {
        ServerSocket server = null;
        Thread acceptor = null;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            server.setSoTimeout(CURL_PROBE_TIMEOUT_MS + 1_000);
            int port = server.getLocalPort();
            CountDownLatch accepted = new CountDownLatch(1);
            ServerSocket listen = server;
            acceptor = new Thread(() -> serveOneProbeResponse(listen, accepted), "gst-curl-probe-http");
            acceptor.setDaemon(true);
            acceptor.start();

            String url = "http://127.0.0.1:" + port + "/probe";
            return runCurlPipeline(url, accepted);
        } catch (Throwable t) {
            log.warn("curlhttpsrc probe setup failed: {}", t.toString());
            return false;
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (acceptor != null) {
                try {
                    acceptor.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void serveOneProbeResponse(ServerSocket server, CountDownLatch accepted) {
        try (Socket socket = server.accept()) {
            accepted.countDown();
            InputStream in = socket.getInputStream();
            // Drain request headers (best-effort).
            byte[] buf = new byte[1024];
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < deadline && in.available() > 0) {
                if (in.read(buf) < 0) {
                    break;
                }
            }
            byte[] body = "ok\n".getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Exception e) {
            log.debug("curl probe HTTP accept: {}", e.toString());
            accepted.countDown();
        }
    }

    private static boolean runCurlPipeline(String url, CountDownLatch accepted) {
        Pipeline pipeline = null;
        AtomicBoolean terminal = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        try {
            pipeline = (Pipeline) org.freedesktop.gstreamer.Gst.parseLaunch(
                    "curlhttpsrc name=src location=" + url + " ! fakesink sync=false async=false");
            Element src = pipeline.getElementByName("src");
            if (src != null) {
                try {
                    src.set("timeout", 2);
                } catch (Throwable ignored) {
                    // property may differ by plugin version
                }
            }
            pipeline.getBus().connect((Bus.EOS) source -> {
                terminal.set(true);
                done.countDown();
            });
            pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {
                terminal.set(false);
                done.countDown();
            });
            StateChangeReturn play = pipeline.play();
            if (play == StateChangeReturn.FAILURE) {
                return false;
            }
            boolean finished = done.await(CURL_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            State state = pipeline.getState(0);
            boolean playing = state == State.PLAYING || state == State.PAUSED;
            boolean sawAccept = accepted.getCount() == 0;
            boolean ok = finished ? terminal.get() : (playing && sawAccept);
            if (!finished) {
                log.warn("curlhttpsrc probe timed out after {} ms (state={})",
                        CURL_PROBE_TIMEOUT_MS, state);
            }
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            log.warn("curlhttpsrc probe failed: {}", t.toString());
            return false;
        } finally {
            disposeProbePipeline(pipeline);
        }
    }

    private static void disposeProbePipeline(Pipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        Thread cleanup = new Thread(() -> {
            try {
                pipeline.stop();
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                pipeline.dispose();
            } catch (Throwable ignored) {
                // ignore
            }
        }, "gst-curl-probe-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
        try {
            cleanup.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Demote HW decoders that cannot reach READY; leave working ones at registry defaults. */
    private static void demoteBrokenHwDecoders() {
        for (String name : H264_HW_DECODERS) {
            demoteIfNotReady(name);
        }
        for (String name : NV_EXTRA_DECODERS) {
            demoteIfNotReady(name);
        }
    }

    private static void demoteIfNotReady(String name) {
        PluginFeature feature = lookup(name);
        if (feature == null) {
            return;
        }
        if (probeElementReady(name)) {
            log.info("Keeping {} (READY ok, rank {})", name, feature.getRank());
        } else {
            feature.setRank(PluginFeature.Rank.NONE);
            log.info("Demoted {}: READY probe failed", name);
        }
    }

    private static boolean probeElementReady(String factoryName) {
        Element element = null;
        try {
            element = ElementFactory.make(factoryName, "probe-" + factoryName);
            if (element == null) {
                return false;
            }
            StateChangeReturn ret = element.setState(State.READY);
            return ret != StateChangeReturn.FAILURE;
        } catch (Throwable t) {
            log.debug("READY probe {}: {}", factoryName, t.toString());
            return false;
        } finally {
            if (element != null) {
                try {
                    element.setState(State.NULL);
                } catch (Throwable ignored) {
                    // ignore
                }
                try {
                    element.dispose();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static PluginFeature lookup(String featureName) {
        return Registry.get().lookupFeature(featureName);
    }

    private static void setRank(String featureName, int rank) {
        PluginFeature feature = lookup(featureName);
        if (feature == null) {
            return;
        }
        feature.setRank(rank);
    }
}
