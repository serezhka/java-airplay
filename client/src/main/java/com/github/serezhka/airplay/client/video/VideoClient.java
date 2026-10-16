package com.github.serezhka.airplay.client.video;

import com.github.serezhka.airplay.client.netty.NettyEventLoops;

import com.github.serezhka.airplay.client.capture.CaptureSource;
import com.github.serezhka.airplay.client.capture.CaptureSources;
import com.github.serezhka.airplay.client.crypto.AesCtrStreamEncryptor;
import com.github.serezhka.airplay.client.crypto.FairPlayVideoEncryptor;
import com.github.serezhka.airplay.protocol.mirror.MirrorPacketHeaders;
import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout;
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
import io.netty.channel.epoll.EpollSocketChannel;
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
    private final AesCtrStreamEncryptor streamEncryptor;

    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;

    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicLong pts = new AtomicLong(System.nanoTime());

    private volatile EventLoopGroup workerGroup;
    private volatile Channel channel;
    private volatile ChannelHandlerContext ctx;
    private volatile CaptureSource captureSource;
    private volatile boolean sentCodecConfig;
    private volatile boolean closed;
    private final AtomicLong packetsSent = new AtomicLong();

    /**
     * @param encryptor AES-CTR for FairPlay / raw-ekey streams; {@code null} = plaintext
     *                  (HAP SETUP without {@code ekey})
     */
    public VideoClient(String address, int port, FairPlayVideoEncryptor encryptor) throws InterruptedException {
        this(address, port, encryptor, null);
    }

    /** Continuous CTR path used for HAP {@code aes_raw}. */
    public VideoClient(String address, int port, AesCtrStreamEncryptor streamEncryptor) throws InterruptedException {
        this(address, port, null, streamEncryptor);
    }

    private VideoClient(
            String address,
            int port,
            FairPlayVideoEncryptor encryptor,
            AesCtrStreamEncryptor streamEncryptor) throws InterruptedException {
        this.address = address;
        this.port = port;
        this.encryptor = encryptor;
        this.streamEncryptor = streamEncryptor;
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
        captureSource = CaptureSources.open(this::onAnnexBAccessUnit);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("Video client disconnected");
        stopCapture();
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
        for (MirrorVideoFraming.Nal nal : nals) {
            int t = nal.type();
            if (t == 7) {
                sps = nal.data();
            } else if (t == 8) {
                pps = nal.data();
            } else if (t == 6) {
                // SEI ahead of the next slice (common sender packing).
                pending.add(nal);
            } else if (t == 1 || t == 5) {
                vcl.addAll(pending);
                pending.clear();
                vcl.add(nal);
            }
            // drop AUD (9) / filler
        }

        if (!sentCodecConfig && sps != null && pps != null) {
            sendPacket(MirrorPacketLayout.PAYLOAD_CODEC, MirrorVideoFraming.buildType1CodecData(sps, pps));
            sentCodecConfig = true;
        }
        if (vcl.isEmpty()) {
            return;
        }
        sendPacket(MirrorPacketLayout.PAYLOAD_VIDEO, MirrorVideoFraming.toAvcc(vcl));
    }

    private void sendPacket(int payloadType, byte[] payload) {
        ChannelHandlerContext local = ctx;
        if (local == null || closed) {
            return;
        }
        // Type-1 codec config is plaintext on the wire (receivers do not AES-CTR it).
        // Encrypting it advances our CTR while the peer does not — every later frame decrypts wrong.
        if (payloadType != MirrorPacketLayout.PAYLOAD_CODEC) {
            try {
                if (streamEncryptor != null) {
                    streamEncryptor.encrypt(payload);
                } else if (encryptor != null) {
                    encryptor.encrypt(payload);
                }
            } catch (Exception e) {
                log.error("video encrypt failed: {}", e.getMessage());
                return;
            }
        }
        // ~33.3ms in nanoseconds — 30 fps PTS step used by typical senders.
        long timestamp = pts.getAndAdd(33_333_333L);
        byte[] header = payloadType == MirrorPacketLayout.PAYLOAD_CODEC
                ? MirrorPacketHeaders.legacy(payload.length, payloadType, timestamp, FRAME_WIDTH, FRAME_HEIGHT)
                : MirrorPacketHeaders.legacy(payload.length, payloadType, timestamp);
        // One TCP write: some receivers mis-frame when the 128B header and payload are split.
        byte[] packet = new byte[header.length + payload.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(payload, 0, packet, header.length, payload.length);
        local.writeAndFlush(Unpooled.wrappedBuffer(packet));
        long n = packetsSent.incrementAndGet();
        if (n == 1 || n == 30 || n % 300 == 0) {
            log.info("Video packets sent={} lastType={} lastLen={}", n, payloadType, payload.length);
        }
    }

    @Override
    public void close() {
        closed = true;
        stopCapture();
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

    private void stopCapture() {
        CaptureSource src = captureSource;
        captureSource = null;
        if (src != null) {
            try {
                src.close();
            } catch (Exception e) {
                log.debug("CaptureSource stop: {}", e.toString());
            }
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return NettyEventLoops.create();
    }

    private Class<? extends SocketChannel> socketChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }
}
