package com.github.serezhka.airplay.app.mse;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MseMediaServerTest {

    private static final String[] ASSETS = {
            "player.html", "hls.min.js", "jmuxer.min.js",
            "fdk_aac.js", "fdk_aac.wasm", "FDK_AAC_NOTICE.txt",
            "alac.js", "ALAC_JS_NOTICE.txt"
    };

    private static void extractAssets(Path dir) throws Exception {
        for (String name : ASSETS) {
            try (InputStream in = MseMediaServerTest.class.getResourceAsStream("/mse/" + name)) {
                assertNotNull(in, name);
                Files.write(dir.resolve(name), in.readAllBytes());
            }
        }
    }

    @Test
    void classpathBundlesPlayerAssets() throws Exception {
        for (String name : ASSETS) {
            try (InputStream in = MseMediaServerTest.class.getResourceAsStream("/mse/" + name)) {
                assertNotNull(in, name);
                assertTrue(in.readAllBytes().length > 100, name);
            }
        }
    }

    @Test
    void servesHtmlAndDeliversMirrorNals() throws Exception {
        Path dir = Files.createTempDirectory("mse-test-");
        extractAssets(dir);
        try (MseMediaServer server = new MseMediaServer()) {
            server.start(dir);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(URI.create(server.pageUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("hls.min.js"));
            assertTrue(page.body().contains("jmuxer.min.js"));
            assertTrue(page.body().contains("fdk_aac.js"));

            HttpResponse<byte[]> wasm = client.send(
                    HttpRequest.newBuilder(URI.create(server.pageUrl() + "fdk_aac.wasm")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, wasm.statusCode());
            assertTrue(wasm.body().length > 10_000);
            assertEquals("application/wasm",
                    wasm.headers().firstValue("content-type").orElse(""));

            AtomicReference<byte[]> received = new AtomicReference<>();
            CompletableFuture<Void> got = new CompletableFuture<>();
            WebSocket socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(server.mirrorWsUrl()), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            received.set(bytes);
                            got.complete(null);
                            webSocket.request(1);
                            return null;
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            Thread.sleep(200);
            byte[] nal = new byte[]{0x00, 0x00, 0x00, 0x01, 0x67, 0x42};
            server.sendNal(nal);
            got.get(5, TimeUnit.SECONDS);
            assertEquals(nal.length, received.get().length);
            assertEquals(nal[4], received.get()[4]);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void fileUpstreamAllowedOnlyWhenOptIn() {
        String prev = System.getProperty("airplay.mse.allow-file-hls");
        try {
            System.clearProperty("airplay.mse.allow-file-hls");
            assertFalse(MseMediaServer.isAllowedUpstream("file:///tmp/mediadata.m3u8"));
            System.setProperty("airplay.mse.allow-file-hls", "true");
            assertTrue(MseMediaServer.isAllowedUpstream("file:///tmp/mediadata.m3u8"));
            assertTrue(MseMediaServer.isAllowedUpstream("http://127.0.0.1:9/playlist.m3u8"));
            assertFalse(MseMediaServer.isAllowedUpstream("http://evil.example/x.m3u8"));
        } finally {
            if (prev == null) {
                System.clearProperty("airplay.mse.allow-file-hls");
            } else {
                System.setProperty("airplay.mse.allow-file-hls", prev);
            }
        }
    }

    @Test
    void hlsClockReportsPlayingImmediatelyAndHonorsPauseSeek() throws Exception {
        Path dir = Files.createTempDirectory("mse-hls-clock-");
        extractAssets(dir);
        try (MseMediaServer server = new MseMediaServer()) {
            server.start(dir);
            server.startHls("http://127.0.0.1:" + server.port() + "/playlist/master.m3u8");
            assertTrue(server.isHlsActive());
            MseMediaServer.PlaybackSnapshot start = server.playback();
            assertEquals(1.0, start.rate(), 0.001, "rate must be 1 before browser readyState");
            assertEquals(0.0, start.position(), 0.05);

            Thread.sleep(300);
            MseMediaServer.PlaybackSnapshot advancing = server.playback();
            assertEquals(1.0, advancing.rate(), 0.001);
            assertTrue(advancing.position() >= 0.2, "wall clock should advance while playing");

            server.pause();
            double pausedAt = server.playback().position();
            assertEquals(0.0, server.playback().rate(), 0.001);
            Thread.sleep(250);
            assertEquals(pausedAt, server.playback().position(), 0.05, "position frozen while paused");

            server.seek(12.5);
            MseMediaServer.PlaybackSnapshot afterSeek = server.playback();
            assertEquals(1.0, afterSeek.rate(), 0.001, "scrub leaves transport playing");
            assertEquals(12.5, afterSeek.position(), 0.05);

            server.pause();
            assertEquals(0.0, server.playback().rate(), 0.001);
            server.resume();
            assertEquals(1.0, server.playback().rate(), 0.001);

            server.stopHls();
            assertFalse(server.isHlsActive());
            assertEquals(0.0, server.playback().rate(), 0.001);
        }
    }

    @Test
    void audioConfigureBeforeControlIsReplayedOnReady() throws Exception {
        Path dir = Files.createTempDirectory("mse-audio-resync-");
        extractAssets(dir);
        try (MseMediaServer server = new MseMediaServer()) {
            server.start(dir);
            byte[] cookie = hex("000001600010280a0e0200ff00000000000000000000ac44");
            server.configureAlac(44_100, 2, cookie);
            assertNotNull(server.lastAudioConfigureJson());
            assertTrue(server.lastAudioConfigureJson().contains("configureAlac"));
            assertTrue(server.lastAudioConfigureJson().contains("audioWsUrl"));

            // Resync pushes configureAlac then setVolume — do not assert on "last" text,
            // or a trailing volume frame races the assertion on CI.
            AtomicReference<String> configureCmd = new AtomicReference<>();
            CompletableFuture<Void> gotConfigure = new CompletableFuture<>();
            WebSocket control = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/control"),
                            new WebSocket.Listener() {
                                @Override
                                public void onOpen(WebSocket webSocket) {
                                    webSocket.request(1);
                                    webSocket.sendText("{\"event\":\"ready\"}", true);
                                }

                                @Override
                                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                    String msg = data.toString();
                                    if (msg.contains("configureAlac")) {
                                        configureCmd.set(msg);
                                        gotConfigure.complete(null);
                                    }
                                    webSocket.request(1);
                                    return null;
                                }
                            })
                    .get(5, TimeUnit.SECONDS);

            gotConfigure.get(5, TimeUnit.SECONDS);
            assertNotNull(configureCmd.get());
            assertTrue(configureCmd.get().contains("configureAlac"), configureCmd.get());
            assertTrue(configureCmd.get().contains("audioWsUrl"), configureCmd.get());
            control.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void audioAusBufferUntilAudioSocketConnects() throws Exception {
        Path dir = Files.createTempDirectory("mse-audio-pending-");
        extractAssets(dir);
        try (MseMediaServer server = new MseMediaServer()) {
            server.start(dir);
            byte[] au = new byte[]{1, 2, 3, 4, 5};
            server.sendAudioAu(au, 0, au.length);
            server.sendAudioAu(au, 0, au.length);
            assertEquals(2, server.pendingAudioAuCount());
            assertFalse(server.hasAudioClient());

            AtomicReference<byte[]> received = new AtomicReference<>();
            CompletableFuture<Void> got = new CompletableFuture<>();
            WebSocket audio = HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URI.create(server.audioWsUrl()), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(1);
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()];
                            data.get(bytes);
                            received.set(bytes);
                            got.complete(null);
                            webSocket.request(1);
                            return null;
                        }
                    })
                    .get(5, TimeUnit.SECONDS);

            got.get(5, TimeUnit.SECONDS);
            assertEquals(0, server.pendingAudioAuCount());
            assertTrue(server.hasAudioClient());
            assertEquals(5, received.get().length);
            audio.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        }
    }

    private static byte[] hex(String hex) {
        int n = hex.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
