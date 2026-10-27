package com.github.serezhka.airplay.app.mse.cast;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tiny Chrome DevTools Protocol client: inject cast bootstrap + navigate top-level URL
 * (avoids iframe X-Frame-Options "refused to connect").
 */
@Slf4j
final class CastCdpSession implements AutoCloseable {

    private final WebSocket ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicReference<Pending> pending = new AtomicReference<>();

    private record Pending(int id, CompletableFuture<String> future) {
    }

    private CastCdpSession(WebSocket ws) {
        this.ws = ws;
    }

    static CastCdpSession connect(Path userDataDir, long timeoutMs) throws Exception {
        Path portFile = userDataDir.resolve("DevToolsActivePort");
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(portFile)) {
                break;
            }
            Thread.sleep(100);
        }
        if (!Files.isRegularFile(portFile)) {
            throw new IllegalStateException("Chromium DevToolsActivePort missing under " + userDataDir);
        }
        String portLine;
        String pathLine;
        try (BufferedReader r = Files.newBufferedReader(portFile)) {
            portLine = r.readLine();
            pathLine = r.readLine();
        }
        if (portLine == null || pathLine == null) {
            throw new IllegalStateException("DevToolsActivePort incomplete");
        }
        String browserWs = "ws://127.0.0.1:" + portLine.trim() + pathLine.trim();
        log.info("MSE cast CDP connecting {}", browserWs);

        CompletableFuture<CastCdpSession> ready = new CompletableFuture<>();
        HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(browserWs), new WebSocket.Listener() {
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
                        ready.completeExceptionally(error);
                    }
                })
                .get(timeoutMs, TimeUnit.MILLISECONDS);
        return ready.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void preparePageAndNavigate(String injectJs, String homeUrl) throws Exception {
        // Attach to first page target (or create one).
        String targets = send("Target.getTargets", "{}");
        String targetId = extractTargetId(targets);
        if (targetId == null) {
            String created = send("Target.createTarget", "{\"url\":\"about:blank\"}");
            targetId = extractJsonString(created, "targetId");
        }
        if (targetId == null) {
            throw new IllegalStateException("No CDP page target");
        }
        String attached = send("Target.attachToTarget",
                "{\"targetId\":\"" + targetId + "\",\"flatten\":true}");
        String sessionId = extractJsonString(attached, "sessionId");
        // With flatten:true, subsequent commands use sessionId in flat mode via sessionId field.
        sendFlat(sessionId, "Page.enable", "{}");
        String escaped = jsonEscape(injectJs);
        sendFlat(sessionId, "Page.addScriptToEvaluateOnNewDocument",
                "{\"source\":\"" + escaped + "\"}");
        // Also evaluate now in case document already loaded.
        sendFlat(sessionId, "Runtime.evaluate",
                "{\"expression\":\"" + escaped + "\",\"awaitPromise\":false}");
        sendFlat(sessionId, "Page.navigate", "{\"url\":\"" + jsonEscape(homeUrl) + "\"}");
        log.info("MSE cast CDP navigated to {}", homeUrl);
    }

    private String send(String method, String paramsJson) throws Exception {
        return sendFlat(null, method, paramsJson);
    }

    private String sendFlat(String sessionId, String method, String paramsJson) throws Exception {
        int id = nextId.getAndIncrement();
        CompletableFuture<String> fut = new CompletableFuture<>();
        pending.set(new Pending(id, fut));
        StringBuilder sb = new StringBuilder(128 + paramsJson.length());
        sb.append("{\"id\":").append(id).append(",\"method\":\"").append(method).append("\"");
        if (sessionId != null) {
            sb.append(",\"sessionId\":\"").append(sessionId).append("\"");
        }
        sb.append(",\"params\":").append(paramsJson).append('}');
        ws.sendText(sb.toString(), true);
        return fut.get(20, TimeUnit.SECONDS);
    }

    private void onMessage(String msg) {
        Pending p = pending.get();
        if (p == null || p.future().isDone()) {
            return;
        }
        if (msg.contains("\"id\":" + p.id()) || msg.contains("\"id\": " + p.id())) {
            p.future().complete(msg);
        }
    }

    private static String extractTargetId(String targetsJson) {
        // Prefer type=page
        int idx = 0;
        while (true) {
            int typePage = targetsJson.indexOf("\"type\":\"page\"", idx);
            if (typePage < 0) {
                break;
            }
            // search backwards for targetId in this object — simple scan around
            int start = Math.max(0, typePage - 200);
            int end = Math.min(targetsJson.length(), typePage + 200);
            String slice = targetsJson.substring(start, end);
            String id = extractJsonString(slice, "targetId");
            if (id != null) {
                return id;
            }
            idx = typePage + 1;
        }
        return extractJsonString(targetsJson, "targetId");
    }

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
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
