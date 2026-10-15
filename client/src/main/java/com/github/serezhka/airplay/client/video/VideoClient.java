package com.github.serezhka.airplay.client.video;

import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.video.source.GstTestSource;
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

@Slf4j
public class VideoClient extends ChannelInboundHandlerAdapter implements AutoCloseable {

    private final String address;
    private final int port;
    private final FairPlayVideoEncryptor encryptor;

    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicLong pts = new AtomicLong();

    private volatile EventLoopGroup workerGroup;
    private volatile Channel channel;
    private volatile ChannelHandlerContext ctx;
    private volatile GstTestSource gstTestSource;
    private volatile boolean sentCodecConfig;
    private volatile boolean closed;

    public VideoClient(String address, int port, FairPlayVideoEncryptor encryptor) throws InterruptedException {
        this.address = address;
        this.port = port;
        this.encryptor = encryptor;
        Thread t = new Thread(this::run, "airplay-video-client");
        t.setDaemon(true);
        t.start();
        if (!started.await(15, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("video client connect timed out");
        }
    }

    private void run() {
        workerGroup = eventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        try {
            bootstrap.group(workerGroup);
            bootstrap.channel(socketChannelClass());
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.remoteAddress(address, port);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    p.addLast("video handler", VideoClient.this);
                }
            });
            ChannelFuture channelFuture = bootstrap.connect().sync();
            channel = channelFuture.channel();
            log.info("Video client started → {}:{}", address, port);
            started.countDown();
            channel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.countDown();
        } catch (Exception e) {
            log.warn("Video client failed: {}", e.toString());
            started.countDown();
        } finally {
            closeQuietly();
            log.info("Video client stopped");
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        log.info("Video client connected");
        gstTestSource = new GstTestSource(this::onAnnexBAccessUnit);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("Video client disconnected");
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
        List<MirrorVideoFraming.Nal> vcl = new ArrayList<>();
        for (MirrorVideoFraming.Nal nal : nals) {
            int t = nal.type();
            if (t == 7) {
                sps = nal.data();
            } else if (t == 8) {
                pps = nal.data();
            } else if (t != 9) {
                vcl.add(nal);
            }
        }

        if (!sentCodecConfig && sps != null && pps != null) {
            sendPacket(1, MirrorVideoFraming.buildType1CodecData(sps, pps));
            sentCodecConfig = true;
        }
        if (vcl.isEmpty()) {
            return;
        }
        sendPacket(0, MirrorVideoFraming.toAvcc(vcl));
    }

    private void sendPacket(int payloadType, byte[] payload) {
        ChannelHandlerContext local = ctx;
        if (local == null || closed) {
            return;
        }
        try {
            encryptor.encrypt(payload);
        } catch (Exception e) {
            log.error("video encrypt failed: {}", e.getMessage());
            return;
        }
        long timestamp = pts.getAndAdd(3000);
        byte[] header = MirrorVideoFraming.header(payload.length, payloadType, timestamp);
        local.write(Unpooled.wrappedBuffer(header));
        local.writeAndFlush(Unpooled.wrappedBuffer(payload));
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
