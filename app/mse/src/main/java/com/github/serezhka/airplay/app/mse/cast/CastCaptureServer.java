package com.github.serezhka.airplay.app.mse.cast;

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
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Localhost hub for the cast Chromium shell: {@code /cast} page,
 * {@code /cast-control} commands, {@code /cast-video} Annex-B from WebCodecs.
 */
@Slf4j
public final class CastCaptureServer implements AutoCloseable {

    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER =
            AttributeKey.valueOf("cast.handshaker");
    private static final AttributeKey<String> WS_PATH = AttributeKey.valueOf("cast.wsPath");

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup(2);
    private final ChannelGroup controlClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ChannelGroup videoClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final AtomicReference<Channel> serverChannel = new AtomicReference<>();
    private final AtomicReference<Consumer<byte[]>> annexBSink = new AtomicReference<>(b -> {});
    private volatile CountDownLatch pageReady = new CountDownLatch(1);
    private volatile CountDownLatch captureStarted = new CountDownLatch(1);
    private volatile byte[] castShellHtml = new byte[0];
    private volatile int port = -1;

    public void start() throws InterruptedException, IOException {
        try (InputStream in = CastCaptureServer.class.getResourceAsStream("/mse/cast-shell.html")) {
            if (in == null) {
                throw new IOException("Missing /mse/cast-shell.html");
            }
            castShellHtml = in.readAllBytes();
        }
        Channel channel = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(2 * 1024 * 1024));
                        p.addLast(new FrontHandler());
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel();
        serverChannel.set(channel);
        port = ((InetSocketAddress) channel.localAddress()).getPort();
        log.info("MSE cast capture server http://127.0.0.1:{}/cast", port);
    }

    int port() {
        return port;
    }

    public String pageUrl() {
        return "http://127.0.0.1:" + port + "/cast";
    }

    String videoWsUrl() {
        return "ws://127.0.0.1:" + port + "/cast-video";
    }

    void setAnnexBSink(Consumer<byte[]> sink) {
        annexBSink.set(sink == null ? b -> {} : sink);
    }

    void resetLatches() {
        pageReady = new CountDownLatch(1);
        captureStarted = new CountDownLatch(1);
    }

    boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return pageReady.await(timeout, unit);
    }

    boolean awaitCaptureStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return captureStarted.await(timeout, unit);
    }

    void startCast(String homeUrl) {
        String home = homeUrl == null || homeUrl.isBlank() ? "https://www.youtube.com" : homeUrl;
        sendControl("{\"cmd\":\"startCast\",\"videoWsUrl\":\"" + videoWsUrl()
                + "\",\"homeUrl\":" + jsonString(home) + "}");
    }

    void stopCast() {
        sendControl("{\"cmd\":\"stopCast\"}");
        for (Channel ch : videoClients.toArray(new Channel[0])) {
            ch.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
        }
        videoClients.clear();
    }

    private void sendControl(String json) {
        controlClients.writeAndFlush(new TextWebSocketFrame(json));
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close() {
        stopCast();
        Channel channel = serverChannel.getAndSet(null);
        if (channel != null) {
            channel.close();
        }
        boss.shutdownGracefully();
        worker.shutdownGracefully();
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
            if ("/cast-control".equals(path) || "/cast-video".equals(path)) {
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
                    if ("/cast-video".equals(path)) {
                        videoClients.add(ctx.channel());
                        log.debug("cast video client {}", ctx.channel().remoteAddress());
                    } else {
                        controlClients.add(ctx.channel());
                        log.info("cast control client connected");
                    }
                });
                return;
            }
            if (request.method() != HttpMethod.GET) {
                writeHttp(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, new byte[0], "text/plain");
                return;
            }
            if ("/cast".equals(path) || "/".equals(path)) {
                writeHttp(ctx, request, HttpResponseStatus.OK, castShellHtml, "text/html; charset=utf-8");
                return;
            }
            writeHttp(ctx, request, HttpResponseStatus.NOT_FOUND,
                    "not found".getBytes(StandardCharsets.UTF_8), "text/plain");
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
            if (frame instanceof TextWebSocketFrame text && "/cast-control".equals(path)) {
                String payload = text.text();
                if (payload.contains("\"event\":\"ready\"")) {
                    pageReady.countDown();
                } else if (payload.contains("\"event\":\"captureStarted\"")) {
                    captureStarted.countDown();
                    log.info("MSE cast tab capture started");
                } else if (payload.contains("\"event\":\"error\"")) {
                    log.warn("MSE cast page: {}", payload);
                }
                return;
            }
            if (frame instanceof BinaryWebSocketFrame binary && "/cast-video".equals(path)) {
                byte[] nal = new byte[binary.content().readableBytes()];
                binary.content().readBytes(nal);
                if (nal.length > 0) {
                    annexBSink.get().accept(nal);
                }
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

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            controlClients.remove(ctx.channel());
            videoClients.remove(ctx.channel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.debug("cast capture client error: {}", cause.toString());
            ctx.close();
        }
    }
}
