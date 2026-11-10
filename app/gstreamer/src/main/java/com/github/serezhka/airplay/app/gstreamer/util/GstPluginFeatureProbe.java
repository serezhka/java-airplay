package com.github.serezhka.airplay.app.gstreamer.util;

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
 * Post-{@code Gst.init} feature ranking: keep hardware and HTTP sources that actually work
 * on this host, demote only what fails a short probe.
 * <p>
 * Do not set {@code GST_PLUGIN_FEATURE_RANK} before init for soup/curl/nv — that forced
 * curl on Windows (multi hang) and permanently dropped NVIDIA decoders that can be fine
 * when CUDA is usable. Honors a user-supplied {@code GST_PLUGIN_FEATURE_RANK} and skips
 * auto-tuning.
 */
@Slf4j
public final class GstPluginFeatureProbe {

    /**
     * H.264 decode preference for HLS {@code playbin3} autoplug. First READY wins.
     * NVIDIA before d3d11: on some Windows hosts {@code d3d11h264dec} reports READY but
     * HLS never presents (adaptivedemux then dies with "Couldn't download fragments"),
     * while {@code nvh264dec} plays YouTube cleanly into {@code d3d11videosink}.
     */
    private static final String[] H264_HW_PREFERENCE = {
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

    /** Above {@link PluginFeature.Rank#PRIMARY} so a healthy curl / HW decoder wins autoplug. */
    private static final int PREFERRED_RANK = 300;
    private static final int CURL_PREFERRED_RANK = PREFERRED_RANK;
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
        probeH264Decoders();
        probeNvExtraDecoders();
    }

    private static void probeHttpSources() {
        if (lookup("curlhttpsrc") == null) {
            log.info("curlhttpsrc not installed; leaving souphttpsrc at default rank");
            return;
        }
        boolean curlOk = probeCurlHttpsrc();
        if (curlOk) {
            setRank("curlhttpsrc", PREFERRED_RANK);
            // Soft-stop still orphans playbins; prefer curl when it works so libsoup is not
            // the in-flight HTTP stack under HLS teardown.
            setRank("souphttpsrc", PluginFeature.Rank.SECONDARY.intValue());
            log.info("HTTP src probe: curlhttpsrc OK -> rank {}; souphttpsrc -> SECONDARY",
                    PREFERRED_RANK);
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

    /**
     * Pick the best installed H.264 HW decoder for HLS autoplug. Prefer d3d11 on Windows
     * (matches {@code d3d11videosink}); demote READY failures so playbin does not stutter
     * on a half-broken NVIDIA factory.
     */
    private static void probeH264Decoders() {
        String winner = null;
        for (String name : H264_HW_PREFERENCE) {
            PluginFeature feature = lookup(name);
            if (feature == null) {
                continue;
            }
            if (probeElementReady(name)) {
                if (winner == null) {
                    winner = name;
                    feature.setRank(PREFERRED_RANK);
                    log.info("HLS H.264 decoder preferred: {} (rank {})", name, PREFERRED_RANK);
                } else {
                    // Keep as fallback below the winner, above software when possible.
                    feature.setRank(PluginFeature.Rank.SECONDARY);
                    log.info("HLS H.264 decoder fallback: {} -> SECONDARY", name);
                }
            } else {
                feature.setRank(PluginFeature.Rank.NONE);
                log.info("Demoted {}: READY probe failed", name);
            }
        }
        if (winner == null) {
            log.info("HLS H.264: no HW decoder READY; playbin keeps default (usually avdec_h264)");
        }
    }

    private static void probeNvExtraDecoders() {
        for (String name : NV_EXTRA_DECODERS) {
            PluginFeature feature = lookup(name);
            if (feature == null) {
                continue;
            }
            if (probeElementReady(name)) {
                log.info("Keeping {} (READY ok, rank {})", name, feature.getRank());
            } else {
                feature.setRank(PluginFeature.Rank.NONE);
                log.info("Demoted {}: READY probe failed", name);
            }
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
