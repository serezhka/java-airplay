package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.decoder.AudioDecoder;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.DatagramPacketDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
@RequiredArgsConstructor
public class AudioReceiver implements Runnable {

    private final AudioHandler audioHandler;
    private final Object monitor;

    @Getter
    private int port;

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        var workerGroup = eventLoopGroup();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast("audioDecoder", new DatagramPacketDecoder(new AudioDecoder()));
                            ch.pipeline().addLast("audioHandler", audioHandler);
                        }
                    });
            var channelFuture = bootstrap.bind().sync();

            log.info("AirPlay audio receiver listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (monitor) {
                monitor.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.info("AirPlay audio receiver interrupted");
        } finally {
            log.info("AirPlay audio receiver stopped");
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }
}
