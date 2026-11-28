package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.handler.audio.AudioControlHandler;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioResendPacket;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioResendRequests;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

@Slf4j
public class AudioControlServer implements Runnable, AudioResendRequests {

    private static final int UDP_RCVBUF_BYTES = 2 * 1024 * 1024;

    private Thread thread;
    private int port;
    private volatile Channel serverChannel;
    private volatile InetSocketAddress peer;
    /** Fills RTP reorder holes; set while the matching {@link AudioServer} is live. */
    private volatile Consumer<AudioPacket> retransmitSink;

    public void start() throws InterruptedException {
        thread = new Thread(this);
        thread.start();
        synchronized (this) {
            wait();
        }
    }

    public void stop() {
        retransmitSink = null;
        Channel ch = serverChannel;
        if (ch != null) {
            ch.close();
            serverChannel = null;
        }
        peer = null;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public void setRetransmitSink(Consumer<AudioPacket> sink) {
        retransmitSink = sink;
    }

    @Override
    public void requestResend(int firstSeq, int count) {
        if (count <= 0) {
            return;
        }
        InetSocketAddress target = peer;
        Channel ch = serverChannel;
        if (target == null || ch == null || !ch.isActive()) {
            return;
        }
        byte[] payload = AudioResendPacket.encode(firstSeq, count);
        ch.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(payload), target));
        log.debug("Audio resend requested first={} count={} -> {}", firstSeq & 0xFFFF, count, target);
    }

    void rememberPeer(InetSocketAddress address) {
        if (address != null) {
            peer = address;
        }
    }

    public InetSocketAddress peer() {
        return peer;
    }

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        EventLoopGroup workerGroup = NettyEventLoops.sharedWorker();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(NettyEventLoops.datagramChannelClass())
                    .option(ChannelOption.SO_RCVBUF, UDP_RCVBUF_BYTES)
                    .localAddress(new InetSocketAddress(0))
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast("audioControlHandler",
                                    new AudioControlHandler(
                                            AudioControlServer.this::rememberPeer,
                                            packet -> {
                                                Consumer<AudioPacket> sink = retransmitSink;
                                                if (sink != null) {
                                                    sink.accept(packet);
                                                }
                                            }));
                        }
                    });

            var channelFuture = bootstrap.bind().sync();
            serverChannel = channelFuture.channel();

            log.info("AirPlay audio control server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (this) {
                this.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.debug("AirPlay audio control server interrupted");
            Thread.currentThread().interrupt();
        } finally {
            log.debug("AirPlay audio control server stopped");
            serverChannel = null;
        }
    }

    public int getPort() {
        return port;
    }
}
