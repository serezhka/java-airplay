package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.decoder.VideoDecoder;
import com.github.serezhka.airplay.server.internal.handler.video.VideoHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
@RequiredArgsConstructor
public class VideoServer implements Runnable {

    private final FairPlaySession airPlay;

    private Thread thread;
    private Playback airPlayConsumer;
    private volatile Channel serverChannel;

    @Getter
    private int port;

    public void start(Playback airPlayConsumer) throws InterruptedException {
        this.airPlayConsumer = airPlayConsumer;
        thread = new Thread(this);
        thread.start();
        synchronized (this) {
            wait();
        }
    }

    public void stop() {
        Channel ch = serverChannel;
        if (ch != null) {
            ch.close();
            serverChannel = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
            airPlayConsumer = null;
        }
    }

    /** True while the accept thread is alive (mirror video path). */
    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    @Override
    public void run() {
        var serverBootstrap = new ServerBootstrap();
        EventLoopGroup bossGroup = NettyEventLoops.sharedBoss();
        EventLoopGroup workerGroup = NettyEventLoops.sharedWorker();
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NettyEventLoops.serverSocketChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) {
                            ch.pipeline().addLast("videoDecoder", new VideoDecoder());
                            ch.pipeline().addLast("videoHandler", new VideoHandler(airPlay, airPlayConsumer));
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            var channelFuture = serverBootstrap.bind().sync();
            serverChannel = channelFuture.channel();

            log.info("AirPlay video server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (this) {
                this.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.debug("AirPlay video server interrupted");
            Thread.currentThread().interrupt();
        } finally {
            log.debug("AirPlay video server stopped");
            serverChannel = null;
            // Shared event loops stay alive for the process.
        }
    }
}
