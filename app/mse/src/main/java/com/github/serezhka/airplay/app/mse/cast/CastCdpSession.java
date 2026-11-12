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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Chrome DevTools Protocol for the cast tab: inject {@code cast-inject.js} on each
 * document and receive Annex-B / ADTS via {@code Runtime.addBinding} (HTTPS-safe).
 * <p>
 * Connects to the <em>page</em> debugger WebSocket ({@code /json/list}), not the
 * browser-level socket with {@code Target.attachToTarget(flatten)}.
 */
@Slf4j
final class CastCdpSession implements AutoCloseable {

    private final WebSocket ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    /** Multiplexed CDP command futures — a single slot drops replies under binding traffic. */
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Consumer<String>> onBinding =
            new AtomicReference<>(payload -> {});
    private volatile boolean pipelineInstalled;

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

    /**
     * Prefer a normal tab ({@code type=page}), not extension/background targets.
     * When several {@code page} targets exist (e.g. starter {@code about:blank} plus the navigated tab),
     * attach to the tab whose {@code url} is not {@code about:blank} so capture runs on the real document.
     */
    static String extractPageWebSocketUrl(String jsonListBody) {
        int idx = 0;
        String fallbackWs = null;
        while (true) {
            int typePage = indexOfExactTypePage(jsonListBody, idx);
            if (typePage < 0) {
                break;
            }
            // Stay inside this list entry so we do not pick a neighbouring target's WS URL.
            int objStart = jsonListBody.lastIndexOf('{', typePage);
            int objEnd = jsonListBody.indexOf('}', typePage);
            if (objStart >= 0 && objEnd > objStart) {
                String chunk = jsonListBody.substring(objStart, objEnd + 1);
                String ws = extractJsonString(chunk, "webSocketDebuggerUrl");
                if (ws != null && ws.startsWith("ws://")) {
                    String url = extractJsonString(chunk, "url");
                    if (url != null
                            && !url.isBlank()
                            && !url.startsWith("about:blank")
                            && !url.startsWith("chrome://")) {
                        return ws;
                    }
                    if (fallbackWs == null) {
                        fallbackWs = ws;
                    }
                }
            }
            idx = typePage + 1;
        }
        return fallbackWs;
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

    void setBindingListener(Consumer<String> listener) {
        onBinding.set(listener == null ? p -> {} : listener);
    }

    void installCastPipeline(String injectScriptSource) throws Exception {
        if (pipelineInstalled) {
            return;
        }
        send("Runtime.enable", "{}");
        send("Page.enable", "{}");
        send("Runtime.addBinding", "{\"name\":\"" + CastBindingCodec.BINDING_NAME + "\"}");
        String escaped = jsonEscape(injectScriptSource);
        send("Page.addScriptToEvaluateOnNewDocument", "{\"source\":\"" + escaped + "\"}");
        pipelineInstalled = true;
        log.info("MSE cast CDP binding + inject installed");
    }

    void setCaptureAudio(boolean enable) throws Exception {
        if (!enable) {
            return;
        }
        send("Runtime.evaluate", "{\"expression\":\"if(typeof __airplayCastEnableAudio==='function')"
                + "__airplayCastEnableAudio();\",\"awaitPromise\":false}");
    }

    void navigateAndStartCapture(String homeUrl) throws Exception {
        try {
            send("Page.bringToFront", "{}");
        } catch (Exception e) {
            log.debug("Page.bringToFront: {}", e.toString());
        }
        // YouTube can be slow; CDP may also delay the ack while the page paints. Do not abort
        // the cast session — navigation often still proceeds and the inject script boots capture.
        try {
            send("Page.navigate", "{\"url\":\"" + jsonEscape(homeUrl) + "\"}", 45, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("MSE cast CDP Page.navigate ack timed out → {} (continuing; inject may still run)",
                    homeUrl);
        }
        log.info("MSE cast CDP navigate → {}", homeUrl);
    }

    private String send(String method, String paramsJson) throws Exception {
        return send(method, paramsJson, 20, TimeUnit.SECONDS);
    }

    private String send(String method, String paramsJson, long timeout, TimeUnit unit) throws Exception {
        int id = nextId.getAndIncrement();
        CompletableFuture<String> fut = new CompletableFuture<>();
        pending.put(id, fut);
        StringBuilder sb = new StringBuilder(128 + paramsJson.length());
        sb.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append("\"");
        sb.append(",\"params\":").append(paramsJson).append('}');
        try {
            ws.sendText(sb.toString(), true);
            String resp = fut.get(timeout, unit);
            if (resp.contains("\"error\"")) {
                log.warn("CDP {} error: {}", method, resp.length() > 300 ? resp.substring(0, 300) : resp);
            }
            return resp;
        } finally {
            pending.remove(id);
        }
    }

    private void onMessage(String msg) {
        Integer responseId = extractResponseId(msg);
        if (responseId != null) {
            CompletableFuture<String> fut = pending.get(responseId);
            if (fut != null) {
                fut.complete(msg);
                return;
            }
        }
        if (msg.contains("Runtime.bindingCalled")) {
            handleBindingCalled(msg);
        }
    }

    /**
     * CDP command replies look like {@code {"id":N,"result":...}}; events look like
     * {@code {"method":"...","params":...}}. Prefer the first top-level numeric id when
     * {@code method} is absent or appears after {@code id}.
     */
    static Integer extractResponseId(String msg) {
        if (msg == null || msg.isEmpty()) {
            return null;
        }
        int methodAt = msg.indexOf("\"method\"");
        int idKeyAt = msg.indexOf("\"id\"");
        if (idKeyAt < 0) {
            return null;
        }
        if (methodAt >= 0 && methodAt < idKeyAt) {
            return null;
        }
        int colon = msg.indexOf(':', idKeyAt);
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < msg.length() && msg.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < msg.length() && Character.isDigit(msg.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(msg.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void handleBindingCalled(String msg) {
        String payload = extractBindingPayload(msg);
        if (payload == null) {
            log.warn("CDP bindingCalled missing payload");
            return;
        }
        onBinding.get().accept(payload);
    }

    /**
     * CDP embeds the page string as a JSON string ({@code \"} escapes). Must unescape
     * before {@link CastBindingCodec#parse}; a naive cut-at-first-quote yields garbage
     * and drops every Annex-B / ADTS frame (black TV).
     */
    static String extractBindingPayload(String bindingCalledMessage) {
        return CastBindingCodec.extractJsonString(bindingCalledMessage, "payload");
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
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (Exception ignored) {
        }
    }
}
