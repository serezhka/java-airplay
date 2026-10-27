package com.github.serezhka.airplay.app.mse.cast;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Chrome DevTools Protocol: navigate a top-level tab and stream JPEG screencast
 * frames (no in-page WebSocket — HTTPS pages block {@code ws://127.0.0.1}).
 * <p>
 * Connects to the <em>page</em> debugger WebSocket ({@code /json/list}), not the
 * browser-level socket with {@code Target.attachToTarget(flatten)}. Flattened
 * sessions accept {@code Page.startScreencast} but never emit frames on current
 * Chromium builds used for cast.
 */
@Slf4j
final class CastCdpSession implements AutoCloseable {

    private final WebSocket ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final AtomicReference<BiConsumer<byte[], Integer>> onJpeg =
            new AtomicReference<>((jpeg, sessionId) -> {});
    private volatile boolean screencastStarted;

    private record Pending(int id, CompletableFuture<String> future) {
    }

    private CastCdpSession(WebSocket ws) {
        this.ws = ws;
    }

    static CastCdpSession connect(Path userDataDir, long timeoutMs) throws Exception {
        int port = waitForDevToolsPort(userDataDir, timeoutMs);
        String pageWs = waitForPageWebSocket(port, timeoutMs);
        log.info("MSE cast CDP connecting {}", pageWs);

        CompletableFuture<CastCdpSession> ready = new CompletableFuture<>();
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(pageWs), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();
                    private CastCdpSession session;

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        session = new CastCdpSession(webSocket);
                        ready.complete(session);
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String msg = buf.toString();
                            buf.setLength(0);
                            if (session != null) {
                                session.onMessage(msg);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.warn("MSE cast CDP WebSocket error: {}", error.toString());
                        ready.completeExceptionally(error);
                    }
                })
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        return ready.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static int waitForDevToolsPort(Path userDataDir, long timeoutMs) throws Exception {
        Path portFile = userDataDir.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(portFile)) {
                try (BufferedReader r = Files.newBufferedReader(portFile)) {
                    String portLine = r.readLine();
                    String pathLine = r.readLine();
                    if (portLine != null && pathLine != null) {
                        return Integer.parseInt(portLine.trim());
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Chromium DevToolsActivePort missing under " + userDataDir);
    }

    private static String waitForPageWebSocket(int port, long timeoutMs) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI listUri = URI.create("http://127.0.0.1:" + port + "/json/list");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(listUri).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                String pageWs = extractPageWebSocketUrl(resp.body());
                if (pageWs != null) {
                    return pageWs;
                }
            } catch (Exception e) {
                log.debug("CDP /json/list not ready: {}", e.toString());
            }
            Thread.sleep(150);
        }
        throw new IllegalStateException("No CDP page target on port " + port);
    }

    /** Prefer a normal tab ({@code type=page}), not extension/background targets. */
    static String extractPageWebSocketUrl(String jsonListBody) {
        int idx = 0;
        while (true) {
            int typePage = indexOfExactTypePage(jsonListBody, idx);
            if (typePage < 0) {
                break;
            }
            // Stay inside this list entry so we do not pick a neighbouring target's WS URL.
            int objStart = jsonListBody.lastIndexOf('{', typePage);
            int objEnd = jsonListBody.indexOf('}', typePage);
            if (objStart >= 0 && objEnd > objStart) {
                String ws = extractJsonString(jsonListBody.substring(objStart, objEnd + 1), "webSocketDebuggerUrl");
                if (ws != null && ws.startsWith("ws://")) {
                    return ws;
                }
            }
            idx = typePage + 1;
        }
        return null;
    }

    /** {@code "type":"page"} / {@code "type": "page"} — not {@code background_page}. */
    private static int indexOfExactTypePage(String json, int from) {
        for (String marker : new String[]{"\"type\":\"page\"", "\"type\": \"page\""}) {
            int i = from;
            while (true) {
                i = json.indexOf(marker, i);
                if (i < 0) {
                    break;
                }
                int after = i + marker.length();
                if (after >= json.length()) {
                    return i;
                }
                char c = json.charAt(after);
                if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                    return i;
                }
                i++;
            }
        }
        return -1;
    }

    void setJpegListener(BiConsumer<byte[], Integer> listener) {
        onJpeg.set(listener == null ? (j, s) -> {} : listener);
    }

    void navigateAndScreencast(String homeUrl) throws Exception {
        send("Page.enable", "{}");
        try {
            send("Page.bringToFront", "{}");
        } catch (Exception e) {
            log.debug("Page.bringToFront: {}", e.toString());
        }
        send("Page.navigate", "{\"url\":\"" + jsonEscape(homeUrl) + "\"}");
        // Give the document a moment before screencast (fonts/layout).
        Thread.sleep(1000);
        String start = send("Page.startScreencast",
                "{\"format\":\"jpeg\",\"quality\":55,\"maxWidth\":960,\"maxHeight\":540,\"everyNthFrame\":1}");
        screencastStarted = true;
        log.info("MSE cast CDP screencast started → {} ({})", homeUrl,
                start.length() > 120 ? start.substring(0, 120) : start);
    }

    private String send(String method, String paramsJson) throws Exception {
        int id = nextId.getAndIncrement();
        CompletableFuture<String> fut = new CompletableFuture<>();
        pending.set(new Pending(id, fut));
        StringBuilder sb = new StringBuilder(128 + paramsJson.length());
        sb.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append("\"");
        sb.append(",\"params\":").append(paramsJson).append('}');
        ws.sendText(sb.toString(), true);
        String resp = fut.get(20, TimeUnit.SECONDS);
        if (resp.contains("\"error\"")) {
            log.warn("CDP {} error: {}", method, resp.length() > 300 ? resp.substring(0, 300) : resp);
        }
        return resp;
    }

    private void onMessage(String msg) {
        Pending p = pending.get();
        if (p != null && !p.future().isDone() && matchesResponseId(msg, p.id())) {
            p.future().complete(msg);
            // Fall through: a response never carries screencast frames, but keep the
            // check order so events that arrive while an RPC is pending are handled.
            return;
        }
        if (msg.contains("Page.screencastFrame")) {
            handleScreencastFrame(msg);
        }
    }

    /** Exact {@code "id":N} token match (avoids {@code id=1} matching {@code "id":10}). */
    static boolean matchesResponseId(String msg, int id) {
        String marker = "\"id\":" + id;
        int i = msg.indexOf(marker);
        if (i < 0) {
            marker = "\"id\": " + id;
            i = msg.indexOf(marker);
            if (i < 0) {
                return false;
            }
        }
        int after = i + marker.length();
        if (after >= msg.length()) {
            return true;
        }
        char c = msg.charAt(after);
        return c == ',' || c == '}' || c == ' ';
    }

    private void handleScreencastFrame(String msg) {
        try {
            String data = extractJsonString(msg, "data");
            Integer sid = extractScreencastSessionId(msg);
            if (data == null || sid == null) {
                log.warn("CDP screencastFrame missing data/sessionId (len={})", msg.length());
                return;
            }
            byte[] jpeg = Base64.getDecoder().decode(data);
            onJpeg.get().accept(jpeg, sid);
            // Ack so Chrome keeps sending frames (params.sessionId is numeric).
            int id = nextId.getAndIncrement();
            ws.sendText("{\"id\":" + id + ",\"method\":\"Page.screencastFrameAck\","
                    + "\"params\":{\"sessionId\":" + sid + "}}", true);
        } catch (Exception e) {
            log.warn("screencast frame handle failed: {}", e.toString());
        }
    }

    /**
     * Screencast ack id lives under {@code params.sessionId} (number). Ignore any
     * string {@code sessionId} (flatten leftovers).
     */
    static Integer extractScreencastSessionId(String msg) {
        String fromParams = extractJsonNumber(msg, "sessionId");
        if (fromParams == null) {
            return null;
        }
        try {
            return Integer.parseInt(fromParams);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) {
            marker = "\"" + key + "\": \"";
            i = json.indexOf(marker);
        }
        if (i < 0) {
            return null;
        }
        int start = i + marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static String extractJsonNumber(String json, String key) {
        String marker = "\"" + key + "\":";
        int from = 0;
        while (true) {
            int i = json.indexOf(marker, from);
            if (i < 0) {
                marker = "\"" + key + "\": ";
                i = json.indexOf(marker, from);
                if (i < 0) {
                    return null;
                }
            }
            int start = i + marker.length();
            while (start < json.length() && json.charAt(start) == ' ') {
                start++;
            }
            // Skip string values (flattened CDP sessionId is a UUID string).
            if (start < json.length() && json.charAt(start) == '"') {
                from = start + 1;
                continue;
            }
            int end = start;
            while (end < json.length()) {
                char c = json.charAt(end);
                if ((c >= '0' && c <= '9') || c == '-') {
                    end++;
                } else {
                    break;
                }
            }
            return end > start ? json.substring(start, end) : null;
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        try {
            if (screencastStarted) {
                send("Page.stopScreencast", "{}");
            }
        } catch (Exception ignored) {
        }
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (Exception ignored) {
        }
    }
}
