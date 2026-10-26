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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MseMediaServerTest {

    @Test
    void classpathBundlesPlayerAssets() throws Exception {
        for (String name : new String[]{"player.html", "hls.min.js", "jmuxer.min.js"}) {
            try (InputStream in = MseMediaServerTest.class.getResourceAsStream("/mse/" + name)) {
                assertNotNull(in, name);
                assertTrue(in.readAllBytes().length > 100, name);
            }
        }
    }

    @Test
    void servesHtmlAndDeliversMirrorNals() throws Exception {
        Path dir = Files.createTempDirectory("mse-test-");
        for (String name : new String[]{"player.html", "hls.min.js", "jmuxer.min.js"}) {
            try (InputStream in = MseMediaServerTest.class.getResourceAsStream("/mse/" + name)) {
                Files.write(dir.resolve(name), in.readAllBytes());
            }
        }
        try (MseMediaServer server = new MseMediaServer()) {
            server.start(dir);
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(URI.create(server.pageUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("hls.min.js"));
            assertTrue(page.body().contains("jmuxer.min.js"));

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
}
