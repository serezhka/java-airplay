package com.github.serezhka.airplay.client.hap;

import com.github.serezhka.airplay.client.audio.AudioSource;
import com.github.serezhka.airplay.client.audio.AudioSources;
import com.github.serezhka.airplay.client.crypto.ShkAudioEncryptor;
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

/** UDP AAC sender for HAP mirror using SETUP {@code shk} (same RTP shape as legacy audio). */
@Slf4j
public final class HapAudioClient extends ChannelInboundHandlerAdapter implements AutoCloseable {

    private final String address;
    private final int port;
    private final ShkAudioEncryptor encryptor;
    private final CountDownLatch started = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile AudioSource audioSource;
    private volatile ChannelHandlerContext ctx;
    private volatile Channel channel;
    private volatile EventLoopGroup workerGroup;
    private int sequenceNumber;
    private long timestamp;

    public HapAudioClient(String address, int port, byte[] audioShk) throws Exception {
        this.address = address;
        this.port = port;
        this.encryptor = new ShkAudioEncryptor(audioShk);
        Thread t = new Thread(this::run, "airplay-hap-audio");
        t.setDaemon(true);
        t.start();
        if (!started.await(15, TimeUnit.SECONDS)) {
            close();
            throw new IllegalStateException("HAP audio client connect timed out");
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
                    ch.pipeline().addLast("hap audio", HapAudioClient.this);
                }
            });
            channel = bootstrap.connect(address, port).sync().channel();
            log.info("HAP audio client started (UDP) {}:{}", address, port);
            started.countDown();
            channel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            started.countDown();
        } catch (Exception e) {
            log.warn("HAP audio client failed: {}", e.toString());
            started.countDown();
        } finally {
            stopAudio();
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            log.info("HAP audio client stopped");
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
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
            log.error("HAP audio encrypt failed: {}", e.getMessage());
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
