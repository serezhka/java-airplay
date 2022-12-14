package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.decoder.MirroringDecoder;
import com.github.serezhka.airplay.server.internal.handler.mirroring.MirroringHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class MirroringReceiver implements Runnable {

    private final int port;
    private final MirroringHandler mirroringHandler;

    public MirroringReceiver(int port, MirroringHandler mirroringHandler) {
        this.port = port;
        this.mirroringHandler = mirroringHandler;
    }

    @Override
    public void run() {
        var serverBootstrap = new ServerBootstrap();
        var bossGroup = eventLoopGroup();
        var workerGroup = eventLoopGroup();
        // var executorGroup = new DefaultEventExecutorGroup(4);
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast(new MirroringDecoder(), mirroringHandler); // executorGroup
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            var channelFuture = serverBootstrap.bind().sync();
            log.info("Mirroring receiver listening on port: {}", port);
            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.info("Mirroring receiver interrupted");
        } finally {
            log.info("Mirroring receiver stopped");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

    private Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
}
