package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirplayDataConsumer;
import com.github.serezhka.airplay.server.internal.handler.control.FairPlayHandler;
import com.github.serezhka.airplay.server.internal.handler.control.HeartBeatHandler;
import com.github.serezhka.airplay.server.internal.handler.control.PairingHandler;
import com.github.serezhka.airplay.server.internal.handler.control.RTSPHandler;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
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
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class ControlServer implements Runnable {

    private final PairingHandler pairingHandler;
    private final FairPlayHandler fairPlayHandler;
    private final RTSPHandler rtspHandler;
    private final HeartBeatHandler heartBeatHandler;

    private final int airTunesPort;

    public ControlServer(AirPlayConfig airPlayConfig, AirplayDataConsumer airplayDataConsumer) {
        this.airTunesPort = airPlayConfig.getAirtunesPort();
        SessionManager sessionManager = new SessionManager();
        pairingHandler = new PairingHandler(airPlayConfig, sessionManager);
        fairPlayHandler = new FairPlayHandler(sessionManager);
        rtspHandler = new RTSPHandler(airPlayConfig.getAirplayPort(), airTunesPort, sessionManager, airplayDataConsumer);
        heartBeatHandler = new HeartBeatHandler(sessionManager);
    }

    @Override
    public void run() {
        var serverBootstrap = new ServerBootstrap();
        var bossGroup = eventLoopGroup();
        var workerGroup = eventLoopGroup();
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(airTunesPort))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(64 * 1024),
                                    new LoggingHandler(LogLevel.DEBUG),
                                    pairingHandler,
                                    fairPlayHandler,
                                    rtspHandler,
                                    heartBeatHandler);
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            var channelFuture = serverBootstrap.bind().sync();
            log.info("Control server listening on port: {}", airTunesPort);
            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            log.info("Control server stopped");
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
