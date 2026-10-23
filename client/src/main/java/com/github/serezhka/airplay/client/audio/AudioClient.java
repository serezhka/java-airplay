package com.github.serezhka.airplay.client.audio;

import com.github.serezhka.airplay.client.crypto.FairPlayAudioEncryptor;
import com.github.serezhka.airplay.client.netty.NettyEventLoops;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** UDP RAOP-style audio sender (mirrors {@code AudioServer} datagram path). */
@Slf4j
public class AudioClient extends ChannelInboundHandlerAdapter implements AutoCloseable {

    private final String address;
    private final int port;
    private final FairPlayAudioEncryptor encryptor;
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile AudioSource audioSource;
    private volatile ChannelHandlerContext ctx;
    private volatile Channel channel;
    private volatile EventLoopGroup workerGroup;
    private int sequenceNumber;
    private long timestamp;

    public AudioClient(String address, int port, FairPlayAudioEncryptor encryptor)
            throws InterruptedException {
        this.address = address;
        this.port = port;
        this.encryptor = encryptor;
        Thread t = new Thread(this::run, "airplay-audio-client");
        t.setDaemon(true);
        t.start();
        if (!started.await(15, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("audio client connect timed out");
        }
    }

    private void run() {
        workerGroup = NettyEventLoops.create();
        Bootstrap bootstrap = new Bootstrap();
        try {
            bootstrap.group(workerGroup);
            bootstrap.channel(datagramChannelClass());
            bootstrap.option(ChannelOption.SO_BROADCAST, false);
            bootstrap.handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline().addLast("audio handler", AudioClient.this);
                }
            });
            channel = bootstrap.connect(address, port).sync().channel();
            log.info("Audio client started (UDP) {}:{}", address, port);
            started.countDown();
            channel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.countDown();
        } catch (Exception e) {
            log.warn("Audio client failed: {}", e.toString());
            started.countDown();
        } finally {
            stopAudio();
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            log.info("Audio client stopped");
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        log.info("Audio client connected");
        audioSource = AudioSources.open(this::send);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopAudio();
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
    }

    private void stopAudio() {
        AudioSource src = audioSource;
        audioSource = null;
        if (src != null) {
            try {
                src.close();
            } catch (Exception e) {
                log.debug("AudioSource stop: {}", e.toString());
            }
        }
    }

    private void send(byte[] frame) {
        if (closed.get() || ctx == null || !ctx.channel().isActive()) {
            return;
        }
        int paddedLen = (frame.length + 15) / 16 * 16;
        byte[] payload = new byte[paddedLen];
        System.arraycopy(frame, 0, payload, 0, frame.length);
        try {
            encryptor.encrypt(payload, paddedLen);
        } catch (Exception e) {
            log.error("Audio encrypt failed: {}", e.getMessage());
            return;
        }

        sequenceNumber = (sequenceNumber + 1) & 0xFFFF;
        timestamp += 1024;

        var packet = Unpooled.buffer(12 + paddedLen);
        packet.writeByte(0x80);
        packet.writeByte(0x60);
        packet.writeShort(sequenceNumber);
        packet.writeInt((int) timestamp);
        packet.writeInt(0);
        packet.writeBytes(payload);

        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        ctx.writeAndFlush(new DatagramPacket(packet, remote));
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }
}
