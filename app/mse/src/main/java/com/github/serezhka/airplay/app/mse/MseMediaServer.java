package com.github.serezhka.airplay.app.mse;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Localhost HTTP + WebSockets for the Chromium MSE page:
 * {@code /} static assets, {@code /control} JSON commands, {@code /mirror} H.264 NALs,
 * {@code /hls?u=} same-origin proxy so Chromium can load AirPlay playlists + googlevideo segments
 * (cross-origin fetch from the player page would fail without CORS).
 */
@Slf4j
final class MseMediaServer implements AutoCloseable {

    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER =
            AttributeKey.valueOf("mse.handshaker");
    private static final AttributeKey<String> WS_PATH = AttributeKey.valueOf("mse.wsPath");
    private static final int HLS_PROXY_MAX_BYTES = 32 * 1024 * 1024;
    /** Pre-WS mirror NALs (SPS often arrives ~300ms before Chromium connects after HLS→mirror). */
    private static final int MAX_PENDING_NAL_BYTES = 2 * 1024 * 1024;

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup(2);
    private final ChannelGroup mirrorClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ChannelGroup audioClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ChannelGroup controlClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Map<String, byte[]> staticFiles = new ConcurrentHashMap<>();
    private final AtomicReference<Channel> serverChannel = new AtomicReference<>();
    private volatile CountDownLatch pageReady = new CountDownLatch(1);
    private final AtomicReference<PlaybackSnapshot> lastPlayback =
            new AtomicReference<>(new PlaybackSnapshot(0, 0, 0, "idle"));
    private final HttpClient hlsHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Object pendingNalsLock = new Object();
    private final ArrayDeque<byte[]> pendingNals = new ArrayDeque<>();
    private int pendingNalBytes;

    private volatile int port = -1;
    private volatile boolean hlsActive;
    private volatile boolean mirrorActive;
    private volatile double mediaDurationHint;
    private volatile double volumeLinear = 1.0;

    private volatile Runnable onEnded = () -> {};

    void start(Path assetDir) throws InterruptedException, IOException {
        loadAssets(assetDir);
        Channel channel = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(16 * 1024 * 1024));
                        p.addLast(new FrontHandler());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel();
        serverChannel.set(channel);
        port = ((InetSocketAddress) channel.localAddress()).getPort();
        log.info("MSE media server http://127.0.0.1:{}/ (control+mirror WS)", port);
    }

    int port() {
        return port;
    }

    String pageUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    String mirrorWsUrl() {
        return "ws://127.0.0.1:" + port + "/mirror";
    }

    String audioWsUrl() {
        return "ws://127.0.0.1:" + port + "/audio";
    }

    boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return pageReady.await(timeout, unit);
    }

    boolean hasControlClient() {
        return !controlClients.isEmpty();
    }

    /** After Chromium relaunch, wait again for the page {@code ready} event. */
    void resetPageReady() {
        pageReady = new CountDownLatch(1);
    }

    void startMirror(int fps) {
        mirrorActive = true;
        hlsActive = false;
        clearPendingNals();
        if (!hasControlClient()) {
            log.warn("MSE startMirror with no control client — Chromium page not connected");
        }
        sendControl("{\"cmd\":\"startMirror\",\"wsUrl\":\"" + mirrorWsUrl()
                + "\",\"audioWsUrl\":\"" + audioWsUrl() + "\",\"fps\":" + fps + "}");
        applyVolume();
    }

    void stopMirror() {
        mirrorActive = false;
        clearPendingNals();
        closeMirrorClients();
        closeAudioClients();
        sendControl("{\"cmd\":\"stopMirror\"}");
    }

    /** Tell the page to (re)configure Web Audio for decoded mirror/Music PCM. */
    void configureAudio(int sampleRate, int channels) {
        sendControl("{\"cmd\":\"configureAudio\",\"sampleRate\":" + sampleRate
                + ",\"channels\":" + channels + "}");
    }

    void sendPcm(byte[] s16le, int offset, int length) {
        if (s16le == null || length <= 0 || audioClients.isEmpty()) {
            return;
        }
        int off = Math.max(0, offset);
        int len = Math.min(length, s16le.length - off);
        if (len <= 0) {
            return;
        }
        audioClients.writeAndFlush(new BinaryWebSocketFrame(Unpooled.copiedBuffer(s16le, off, len)));
    }

    void startHls(String playlistUri) {
        hlsActive = true;
        mirrorActive = false;
        mediaDurationHint = 0;
        closeMirrorClients();
        closeAudioClients();
        // Same-origin proxy: Chromium page cannot fetch AirPlay :control /playlist (no CORS)
        // and cannot fetch googlevideo segment URLs either.
        String proxied = hlsProxyUrl(playlistUri);
        sendControl("{\"cmd\":\"startHls\",\"url\":" + jsonString(proxied) + "}");
        applyVolume();
    }

    String hlsProxyUrl(String upstream) {
        return "http://127.0.0.1:" + port + "/hls?u="
                + URLEncoder.encode(upstream, StandardCharsets.UTF_8);
    }

    void stopHls() {
        hlsActive = false;
        sendControl("{\"cmd\":\"stopHls\"}");
    }

    void stopAll() {
        mirrorActive = false;
        hlsActive = false;
        clearPendingNals();
        closeMirrorClients();
        closeAudioClients();
        sendControl("{\"cmd\":\"stopAll\"}");
    }

    void pause() {
        sendControl("{\"cmd\":\"pause\"}");
    }

    void resume() {
        sendControl("{\"cmd\":\"resume\"}");
    }

    void seek(double seconds) {
        sendControl("{\"cmd\":\"seek\",\"seconds\":" + seconds + "}");
    }

    void setVolume(double linear) {
        volumeLinear = Math.max(0.0, Math.min(1.0, linear));
        applyVolume();
    }

    double volume() {
        return volumeLinear;
    }

    void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded == null ? () -> {} : onEnded;
    }


    void noteMediaDuration(double seconds) {
        if (seconds > mediaDurationHint) {
            mediaDurationHint = seconds;
        }
    }

    boolean isHlsActive() {
        return hlsActive;
    }

    void sendNal(byte[] nal) {
        if (nal == null || nal.length == 0) {
            return;
        }
        if (mirrorClients.isEmpty()) {
            if (mirrorActive) {
                bufferPendingNal(nal);
            }
            return;
        }
        flushPendingNals();
        mirrorClients.writeAndFlush(new BinaryWebSocketFrame(Unpooled.copiedBuffer(nal)));
    }

    private void bufferPendingNal(byte[] nal) {
        byte[] copy = nal.clone();
        synchronized (pendingNalsLock) {
            // New SPS → drop stale frames from the previous IDR window.
            if (annexBContainsNalType(copy, 7)) {
                pendingNals.clear();
                pendingNalBytes = 0;
            }
            while (pendingNalBytes + copy.length > MAX_PENDING_NAL_BYTES && !pendingNals.isEmpty()) {
                byte[] dropped = pendingNals.removeFirst();
                pendingNalBytes -= dropped.length;
            }
            pendingNals.addLast(copy);
            pendingNalBytes += copy.length;
        }
    }

    private void flushPendingNals() {
        ArrayDeque<byte[]> batch;
        synchronized (pendingNalsLock) {
            if (pendingNals.isEmpty()) {
                return;
            }
            batch = new ArrayDeque<>(pendingNals);
            pendingNals.clear();
            pendingNalBytes = 0;
        }
        log.info("MSE flushing {} buffered mirror NAL(s) to Chromium", batch.size());
        for (byte[] nal : batch) {
            mirrorClients.writeAndFlush(new BinaryWebSocketFrame(Unpooled.copiedBuffer(nal)));
        }
    }

    private void clearPendingNals() {
        synchronized (pendingNalsLock) {
            pendingNals.clear();
            pendingNalBytes = 0;
        }
    }

    /** Annex-B scan for a NAL unit type (e.g. 7=SPS, 8=PPS, 5=IDR). */
    static boolean annexBContainsNalType(byte[] bytes, int nalType) {
        if (bytes == null || bytes.length < 5) {
            return false;
        }
        int i = 0;
        while (i + 4 < bytes.length) {
            int sc;
            if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 0 && bytes[i + 3] == 1) {
                sc = 4;
            } else if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1) {
                sc = 3;
            } else {
                i++;
                continue;
            }
            int start = i + sc;
            if (start < bytes.length && (bytes[start] & 0x1f) == nalType) {
                return true;
            }
            i = start + 1;
        }
        return false;
    }

    PlaybackSnapshot playback() {
        PlaybackSnapshot snap = lastPlayback.get();
        double duration = snap.duration() > 0 ? snap.duration() : mediaDurationHint;
        // Never invent rate=1 — a stuck ad VOD with position=0 must report rate=0 or the phone hangs.
        double rate = hlsActive ? Math.max(0, snap.rate()) : 0;
        return new PlaybackSnapshot(duration, snap.position(), rate, snap.mode());
    }

    private void applyVolume() {
        sendControl("{\"cmd\":\"setVolume\",\"value\":" + volumeLinear + "}");
    }

    private void sendControl(String json) {
        controlClients.writeAndFlush(new TextWebSocketFrame(json));
    }

    private void closeMirrorClients() {
        for (Channel ch : mirrorClients.toArray(new Channel[0])) {
            ch.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
        }
        mirrorClients.clear();
    }

    private void closeAudioClients() {
        for (Channel ch : audioClients.toArray(new Channel[0])) {
            ch.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
        }
        audioClients.clear();
    }

    private void loadAssets(Path dir) throws IOException {
        for (String name : new String[]{"player.html", "hls.min.js", "jmuxer.min.js"}) {
            byte[] bytes = Files.readAllBytes(dir.resolve(name));
            staticFiles.put("/" + name, bytes);
            if ("player.html".equals(name)) {
                staticFiles.put("/", bytes);
                staticFiles.put("/index.html", bytes);
            }
        }
    }

    @Override
    public void close() {
        stopAll();
        Channel channel = serverChannel.getAndSet(null);
        if (channel != null) {
            channel.close();
        }
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static boolean isAllowedUpstream(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(scheme)) {
                return host != null && !host.isBlank();
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean looksLikePlaylist(String url, String contentType, byte[] body) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        if (lowerType.contains("mpegurl") || lowerType.contains("x-mpegurl")) {
            return true;
        }
        // YouTube segment URLs embed ".../playlist/index.m3u8/.../file/seg.ts" — do NOT
        // classify by URL substring ".m3u8", or we rewrite binary TS as text and hls.js
        // dies with fragParsingError.
        int n = Math.min(body.length, 16);
        if (n == 0) {
            return false;
        }
        String head = new String(body, 0, n, StandardCharsets.UTF_8).trim();
        return head.startsWith("#EXTM3U");
    }

    /**
     * Rewrite absolute/relative playlist URIs so Chromium fetches them via {@code /hls?u=}.
     */
    static String rewritePlaylistForProxy(String body, String playlistUrl, String proxyPrefix) {
        URI base = URI.create(playlistUrl);
        StringBuilder out = new StringBuilder(body.length() + 256);
        for (String rawLine : body.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                out.append(line).append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                out.append(rewriteAttributeUris(line, base, proxyPrefix)).append('\n');
                continue;
            }
            String resolved = resolveAgainst(base, line.trim());
            if (resolved != null && isAllowedUpstream(resolved)) {
                out.append(proxyPrefix).append(URLEncoder.encode(resolved, StandardCharsets.UTF_8)).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        if (!body.endsWith("\n") && out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static String rewriteAttributeUris(String line, URI base, String proxyPrefix) {
        // #EXT-X-MEDIA:URI="...", #EXT-X-KEY:METHOD=...,URI="..."
        int idx = 0;
        StringBuilder sb = new StringBuilder(line.length() + 64);
        while (true) {
            int uriKey = line.indexOf("URI=\"", idx);
            if (uriKey < 0) {
                sb.append(line.substring(idx));
                break;
            }
            int valueStart = uriKey + "URI=\"".length();
            int valueEnd = line.indexOf('"', valueStart);
            if (valueEnd < 0) {
                sb.append(line.substring(idx));
                break;
            }
            sb.append(line, idx, valueStart);
            String original = line.substring(valueStart, valueEnd);
            String resolved = resolveAgainst(base, original);
            if (resolved != null && isAllowedUpstream(resolved)) {
                sb.append(proxyPrefix).append(URLEncoder.encode(resolved, StandardCharsets.UTF_8));
            } else {
                sb.append(original);
            }
            sb.append('"');
            idx = valueEnd + 1;
        }
        return sb.toString();
    }

    private static String resolveAgainst(URI base, String ref) {
        try {
            return base.resolve(ref).toString();
        } catch (Exception e) {
            return null;
        }
    }

    record PlaybackSnapshot(double duration, double position, double rate, String mode) {
    }

    private final class FrontHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                handleHttp(ctx, request);
            } else if (msg instanceof WebSocketFrame frame) {
                handleFrame(ctx, frame);
            }
        }

        private void handleHttp(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = new QueryStringDecoder(request.uri()).path();
            if ("/mirror".equals(path) || "/audio".equals(path) || "/control".equals(path)) {
                WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                        "ws://127.0.0.1:" + port + path, null, true, 8 * 1024 * 1024);
                WebSocketServerHandshaker handshaker = factory.newHandshaker(request);
                if (handshaker == null) {
                    WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                    return;
                }
                ctx.channel().attr(HANDSHAKER).set(handshaker);
                ctx.channel().attr(WS_PATH).set(path);
                handshaker.handshake(ctx.channel(), request).addListener(f -> {
                    if (!f.isSuccess()) {
                        return;
                    }
                    if ("/mirror".equals(path)) {
                        mirrorClients.add(ctx.channel());
                        log.debug("MSE mirror client {}", ctx.channel().remoteAddress());
                        flushPendingNals();
                    } else if ("/audio".equals(path)) {
                        audioClients.add(ctx.channel());
                        log.debug("MSE audio client {}", ctx.channel().remoteAddress());
                    } else {
                        controlClients.add(ctx.channel());
                        log.info("MSE control client connected");
                        applyVolume();
                    }
                });
                return;
            }
            if (request.method() != HttpMethod.GET) {
                writeHttp(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, new byte[0], "text/plain");
                return;
            }
            if ("/hls".equals(path)) {
                handleHlsProxy(ctx, request);
                return;
            }
            byte[] body = staticFiles.get(path);
            if (body == null) {
                writeHttp(ctx, request, HttpResponseStatus.NOT_FOUND,
                        "not found".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            writeHttp(ctx, request, HttpResponseStatus.OK, body, contentType(path));
        }

        private void handleHlsProxy(ChannelHandlerContext ctx, FullHttpRequest request) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            List<String> values = decoder.parameters().get("u");
            if (values == null || values.isEmpty() || values.getFirst().isBlank()) {
                writeHttp(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "missing u".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            String upstream = values.getFirst();
            if (!isAllowedUpstream(upstream)) {
                writeHttp(ctx, request, HttpResponseStatus.FORBIDDEN,
                        "upstream not allowed".getBytes(StandardCharsets.UTF_8), "text/plain");
                return;
            }
            try {
                HttpResponse<byte[]> upstreamResponse = hlsHttp.send(
                        HttpRequest.newBuilder(URI.create(upstream))
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "airplay-mse-hls-proxy")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (upstreamResponse.statusCode() >= 400) {
                    writeHttp(ctx, request, HttpResponseStatus.valueOf(upstreamResponse.statusCode()),
                            ("upstream " + upstreamResponse.statusCode()).getBytes(StandardCharsets.UTF_8),
                            "text/plain");
                    return;
                }
                byte[] bytes = upstreamResponse.body();
                if (bytes.length > HLS_PROXY_MAX_BYTES) {
                    writeHttp(ctx, request, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                            "too large".getBytes(StandardCharsets.UTF_8), "text/plain");
                    return;
                }
                String contentType = upstreamResponse.headers()
                        .firstValue("content-type")
                        .orElse("application/octet-stream");
                boolean playlist = looksLikePlaylist(upstream, contentType, bytes);
                if (playlist) {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    String rewritten = rewritePlaylistForProxy(text, upstream, "http://127.0.0.1:" + port + "/hls?u=");
                    writeHttp(ctx, request, HttpResponseStatus.OK,
                            rewritten.getBytes(StandardCharsets.UTF_8),
                            "application/vnd.apple.mpegurl");
                } else {
                    writeHttp(ctx, request, HttpResponseStatus.OK, bytes, contentType);
                }
            } catch (Exception e) {
                log.warn("MSE HLS proxy failed for {}: {}", upstream, e.toString());
                writeHttp(ctx, request, HttpResponseStatus.BAD_GATEWAY,
                        e.toString().getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        }

        private void handleFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            String path = ctx.channel().attr(WS_PATH).get();
            WebSocketServerHandshaker handshaker = ctx.channel().attr(HANDSHAKER).get();
            if (frame instanceof CloseWebSocketFrame close) {
                if (handshaker != null) {
                    handshaker.close(ctx.channel(), close.retain());
                } else {
                    ctx.close();
                }
                return;
            }
            if (frame instanceof TextWebSocketFrame text && "/control".equals(path)) {
                handleControlMessage(text.text());
            }
        }

        private void writeHttp(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status,
                               byte[] body, String contentType) {
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            HttpUtil.setContentLength(response, body.length);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (!keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, "close");
            }
            ctx.writeAndFlush(response).addListener(keepAlive
                    ? ChannelFutureListener.CLOSE_ON_FAILURE
                    : ChannelFutureListener.CLOSE);
        }

        private String contentType(String path) {
            if (path.endsWith(".js")) {
                return "application/javascript; charset=utf-8";
            }
            if (path.endsWith(".html") || "/".equals(path)) {
                return "text/html; charset=utf-8";
            }
            return "application/octet-stream";
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            mirrorClients.remove(ctx.channel());
            audioClients.remove(ctx.channel());
            controlClients.remove(ctx.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("MSE media server client error: {}", cause.toString());
            ctx.close();
        }
    }

    private void handleControlMessage(String payload) {
        if (payload.contains("\"event\":\"ready\"")) {
            pageReady.countDown();
            log.info("MSE page ready");
            return;
        }
        if (payload.contains("\"event\":\"ended\"")) {
            log.info("MSE HLS ended");
            // Keep hlsActive so /playback-info still comes from the player clock.
            // Clearing it made MsePlayer fall back to zeros while playlist duration stayed ~6s
            // → phone saw "paused at 0 of 6" and never sent playlistRemove / next /play.
            PlaybackSnapshot prev = lastPlayback.get();
            double dur = Math.max(prev.duration(), mediaDurationHint);
            if (dur > 0) {
                // rate=1 at end: "playing at end", not user pause (same as GStreamer path).
                lastPlayback.set(new PlaybackSnapshot(dur, dur, 1, "hls"));
            }
            onEnded.run();
            return;
        }
        if (payload.contains("\"event\":\"error\"")) {
            log.warn("MSE page error: {}", payload);
            return;
        }
        if (payload.contains("\"event\":\"playback\"")) {
            double duration = extractNumber(payload, "duration");
            double position = extractNumber(payload, "position");
            double rate = extractNumber(payload, "rate");
            String mode = extractString(payload, "mode");
            lastPlayback.set(new PlaybackSnapshot(duration, position, rate, mode == null ? "idle" : mode));
            return;
        }
        if (payload.contains("\"event\":\"log\"")) {
            log.debug("MSE page: {}", payload);
        }
    }

    private static double extractNumber(String json, String key) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) {
            return 0;
        }
        int start = i + marker.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == 'e' || c == 'E') {
                end++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extractString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int i = json.indexOf(marker);
        if (i < 0) {
            return null;
        }
        int start = i + marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}
