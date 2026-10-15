package com.github.serezhka.airplay.client.video;

import com.github.serezhka.airplay.client.video.source.GstTestSource;
import com.github.serezhka.airplay.protocol.crypto.DataStreamCipher;
import com.github.serezhka.airplay.protocol.mirror.MirrorPacketHeaders;
import com.github.serezhka.airplay.protocol.mirror.MirrorVideoFraming;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mirror video feed using pair-verify DataStream ChaCha (header AAD, type-1 codec plaintext).
 * Timestamps are boot-relative 32.32 values aligned with {@link com.github.serezhka.airplay.client.hap.AirPlayTimingResponder}.
 */
@Slf4j
public final class DataStreamVideoClient extends ChannelInboundHandlerAdapter implements AutoCloseable {

    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;
    /** Keep picture slightly ahead of the timing clock so frames are not dropped as late. */
    private static final double VIDEO_BIAS_SECONDS = 0.4;

    private final String address;
    private final int port;
    private final DataStreamCipher cipher;
    private final long bootNanos;

    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicLong packetsSent = new AtomicLong();

    private volatile EventLoopGroup workerGroup;
    private volatile Channel channel;
    private volatile ChannelHandlerContext ctx;
    private volatile GstTestSource gstTestSource;
    private volatile boolean sentCodecConfig;
    private volatile boolean closed;

    public DataStreamVideoClient(String address, int port, DataStreamCipher cipher, long bootNanos)
            throws InterruptedException {
        this.address = address;
        this.port = port;
        this.cipher = cipher;
        this.bootNanos = bootNanos;
        Thread t = new Thread(this::run, "airplay-datastream-video");
        t.setDaemon(true);
        t.start();
        if (!started.await(15, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("DataStream video client connect timed out");
        }
    }

    private void run() {
        workerGroup = eventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        try {
            bootstrap.group(workerGroup);
            bootstrap.channel(socketChannelClass());
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.option(ChannelOption.TCP_NODELAY, true);
            bootstrap.remoteAddress(address, port);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast("datastream video", DataStreamVideoClient.this);
                }
            });
            ChannelFuture channelFuture = bootstrap.connect().sync();
            channel = channelFuture.channel();
            log.info("DataStream video → {}:{}", address, port);
            started.countDown();
            channel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.countDown();
        } catch (Exception e) {
            log.warn("DataStream video failed: {}", e.toString());
            started.countDown();
        } finally {
            closeQuietly();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        gstTestSource = new GstTestSource(this::onAnnexBAccessUnit);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        stopGst();
    }

    private void onAnnexBAccessUnit(byte[] annexB) {
        if (closed || ctx == null) {
            return;
        }
        List<MirrorVideoFraming.Nal> nals = MirrorVideoFraming.splitAnnexB(annexB);
        if (nals.isEmpty()) {
            return;
        }

        byte[] sps = null;
        byte[] pps = null;
        List<MirrorVideoFraming.Nal> pending = new ArrayList<>();
        List<MirrorVideoFraming.Nal> vcl = new ArrayList<>();
        boolean keyframe = false;
        for (MirrorVideoFraming.Nal nal : nals) {
            int t = nal.type();
            if (t == 7) {
                sps = nal.data();
            } else if (t == 8) {
                pps = nal.data();
            } else if (t == 6) {
                pending.add(nal);
            } else if (t == 1 || t == 5) {
                if (t == 5) {
                    keyframe = true;
                }
                vcl.addAll(pending);
                pending.clear();
                vcl.add(nal);
            }
        }

        long ts = videoStamp();
        if (!sentCodecConfig && sps != null && pps != null) {
            byte[] codec = MirrorVideoFraming.buildType1CodecData(sps, pps);
            byte[] header = MirrorPacketHeaders.codec(codec.length, ts, FRAME_WIDTH, FRAME_HEIGHT);
            writeRaw(header, codec);
            sentCodecConfig = true;
        }
        if (vcl.isEmpty()) {
            return;
        }
        byte[] payload = MirrorVideoFraming.toAvcc(vcl);
        try {
            byte[] header = MirrorPacketHeaders.video(payload.length + 16, ts, keyframe);
            byte[] sealed = cipher.seal(payload, header);
            writeRaw(header, sealed);
        } catch (Exception e) {
            log.error("DataStream seal failed: {}", e.getMessage());
        }
    }

    private void writeRaw(byte[] header, byte[] payload) {
        ChannelHandlerContext local = ctx;
        if (local == null || closed) {
            return;
        }
        byte[] packet = new byte[header.length + payload.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(payload, 0, packet, header.length, payload.length);
        local.writeAndFlush(Unpooled.wrappedBuffer(packet));
        long n = packetsSent.incrementAndGet();
        if (n == 1 || n == 30 || n % 300 == 0) {
            log.info("DataStream packets sent={} lastLen={}", n, payload.length);
        }
    }

    /** Boot-relative 32.32 timestamp (high half = whole seconds). */
    private long videoStamp() {
        double elapsed = Math.max((System.nanoTime() - bootNanos) / 1_000_000_000.0, 0.0) + VIDEO_BIAS_SECONDS;
        long seconds = (long) elapsed;
        long fraction = (long) ((elapsed - seconds) * (1L << 32)) & 0xFFFFFFFFL;
        return (seconds << 32) | fraction;
    }

    @Override
    public void close() {
        closed = true;
        stopGst();
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        EventLoopGroup group = workerGroup;
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (Throwable ignored) {
            // best-effort
        }
    }

    private void stopGst() {
        GstTestSource src = gstTestSource;
        gstTestSource = null;
        if (src != null) {
            src.close();
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    private Class<? extends SocketChannel> socketChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }
}
