package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.handler.audio.AudioControlHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class AudioControlServer implements Runnable {

    private final Object monitor;

    private int port;

    public AudioControlServer(Object monitor) {
        this.monitor = monitor;
    }

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        var workerGroup = eventLoopGroup();
        var audioControlHandler = new AudioControlHandler();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast(audioControlHandler);
                        }
                    });

            var channelFuture = bootstrap.bind().sync();

            log.info("Audio control server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (monitor) {
                monitor.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.info("Audio control server interrupted");
        } finally {
            log.info("Audio control server stopped");
            workerGroup.shutdownGracefully();
        }
    }

    public int getPort() {
        return port;
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }
}