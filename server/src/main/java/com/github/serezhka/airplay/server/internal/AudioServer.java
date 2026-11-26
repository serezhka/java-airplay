package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.decoder.AudioDecoder;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioResendRequests;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioRtpInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.handler.codec.DatagramPacketDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
@RequiredArgsConstructor
public class AudioServer implements Runnable {

    private final FairPlaySession airPlay;

    private Thread thread;
    private Playback airPlayConsumer;
    private AudioResendRequests resendRequests = AudioResendRequests.NOOP;
    private volatile Channel serverChannel;
    private volatile AudioHandler audioHandler;

    @Getter
    private int port;

    public void start(Playback airPlayConsumer) throws InterruptedException {
        start(airPlayConsumer, AudioResendRequests.NOOP);
    }

    public void start(Playback airPlayConsumer, AudioResendRequests resendRequests) throws InterruptedException {
        this.airPlayConsumer = airPlayConsumer;
        this.resendRequests = resendRequests != null ? resendRequests : AudioResendRequests.NOOP;
        thread = new Thread(this);
        thread.start();
        synchronized (this) {
            wait();
        }
    }

    public void stop() {
        AudioResendRequests resend = resendRequests;
        if (resend instanceof AudioControlServer control) {
            control.setRetransmitSink(null);
        }
        Channel ch = serverChannel;
        if (ch != null) {
            ch.close();
            serverChannel = null;
        }
        audioHandler = null;
        if (thread != null) {
            thread.interrupt();
            thread = null;
            airPlayConsumer = null;
            resendRequests = AudioResendRequests.NOOP;
        }
    }

    public boolean isRunning() {
        Channel ch = serverChannel;
        return ch != null && ch.isActive();
    }

    public void resetReorder() {
        flushReorder(AudioRtpInfo.empty());
    }

    public void flushReorder(AudioRtpInfo info) {
        AudioHandler handler = audioHandler;
        if (handler != null) {
            handler.flush(info != null ? info : AudioRtpInfo.empty());
        }
    }

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        EventLoopGroup workerGroup = NettyEventLoops.sharedWorker();
        AudioResendRequests resend = this.resendRequests;

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(NettyEventLoops.datagramChannelClass())
                    .localAddress(new InetSocketAddress(0))
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            AudioHandler handler = new AudioHandler(airPlay, airPlayConsumer, resend);
                            audioHandler = handler;
                            if (resend instanceof AudioControlServer control) {
                                control.setRetransmitSink(handler::offer);
                            }
                            ch.pipeline().addLast("audioDecoder", new DatagramPacketDecoder(new AudioDecoder()));
                            ch.pipeline().addLast("audioHandler", handler);
                        }
                    });
            var channelFuture = bootstrap.bind().sync();
            serverChannel = channelFuture.channel();

            log.info("AirPlay audio server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            synchronized (this) {
                this.notify();
            }

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.debug("AirPlay audio server interrupted");
            Thread.currentThread().interrupt();
        } finally {
            log.debug("AirPlay audio server stopped");
            serverChannel = null;
        }
    }
}
