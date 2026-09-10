package com.github.serezhka.airplay.client.audio;

import com.github.serezhka.airplay.client.audio.source.GstAudioTestSource;
import com.github.serezhka.airplay.client.crypto.FairPlayAudioEncryptor;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/** UDP RAOP-style audio sender (mirrors {@code AudioServer} datagram path). */
@Slf4j
public class AudioClient extends ChannelInboundHandlerAdapter implements Runnable {

    private final String address;
    private final int port;
    private final FairPlayAudioEncryptor encryptor;

    private GstAudioTestSource audioSource;
    private ChannelHandlerContext ctx;
    private int sequenceNumber;
    private long timestamp;

    public AudioClient(String address, int port, FairPlayAudioEncryptor encryptor) throws InterruptedException {
        this.address = address;
        this.port = port;
        this.encryptor = encryptor;
        new Thread(this, "audio-client").start();
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
            bootstrap.channel(datagramChannelClass());
            bootstrap.option(ChannelOption.SO_BROADCAST, false);
            bootstrap.handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline().addLast("audio handler", AudioClient.this);
                }
            });
            var channelFuture = bootstrap.connect(address, port).sync();
            log.info("Audio client started (UDP) {}:{}", address, port);
            synchronized (this) {
                notify();
            }
            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            log.info("Audio client stopped");
            if (audioSource != null) {
                audioSource.stop();
            }
            workerGroup.shutdownGracefully();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        log.info("Audio client connected");
        audioSource = new GstAudioTestSource(this::send);
    }

    private void send(byte[] frame) {
        if (ctx == null || !ctx.channel().isActive()) {
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

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }
}
