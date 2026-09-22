package com.github.serezhka.airplay.client.video;

import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.client.video.source.GstTestSource;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class VideoClient extends ChannelInboundHandlerAdapter implements Runnable {

    private final String address;
    private final int port;
    private final FairPlayVideoEncryptor encryptor;

    private GstTestSource gstTestSource;
    private ChannelHandlerContext ctx;
    private final AtomicLong pts = new AtomicLong();
    private boolean sentCodecConfig;

    public VideoClient(String address, int port, FairPlayVideoEncryptor encryptor) throws InterruptedException {
        this.address = address;
        this.port = port;
        this.encryptor = encryptor;
        new Thread(this).start();
        synchronized (this) {
            wait();
        }
    }

    @Override
    public void run() {
        var workerGroup = eventLoopGroup();
        var bootstrap = new Bootstrap();

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

            var channelFuture = bootstrap.connect().sync();
            log.info("Video client started");

            synchronized (this) {
                this.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            log.info("Video client stopped");
            workerGroup.shutdownGracefully();
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
    }

    private void onAnnexBAccessUnit(byte[] annexB) {
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
            } else if (t != 9) { // skip AUD
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
        try {
            encryptor.encrypt(payload);
        } catch (Exception e) {
            log.error("video encrypt failed: {}", e.getMessage());
            return;
        }
        long timestamp = pts.getAndAdd(3000);
        byte[] header = MirrorVideoFraming.header(payload.length, payloadType, timestamp);
        ctx.write(Unpooled.wrappedBuffer(header));
        ctx.writeAndFlush(Unpooled.wrappedBuffer(payload));
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    private Class<? extends SocketChannel> socketChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }
}
