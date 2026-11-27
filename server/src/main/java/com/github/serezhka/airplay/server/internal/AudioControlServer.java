package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.server.internal.handler.audio.AudioControlHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
public class AudioControlServer implements Runnable {

    private Thread thread;
    private int port;
    private volatile Channel serverChannel;

    public void start() throws InterruptedException {
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
        }
    }

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        EventLoopGroup workerGroup = NettyEventLoops.sharedWorker();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(NettyEventLoops.datagramChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast("audioControlHandler", new AudioControlHandler());
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
