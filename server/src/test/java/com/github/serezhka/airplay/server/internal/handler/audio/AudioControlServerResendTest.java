package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.server.internal.AudioControlServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AudioControlServerResendTest {

    private AudioControlServer server;
    private EventLoopGroup group;
    private Channel client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close().syncUninterruptibly();
        }
        if (server != null) {
            server.stop();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void learnsPeerFromInboundAndSendsResendRequest() throws Exception {
        server = new AudioControlServer();
        server.start();

        ArrayBlockingQueue<byte[]> received = new ArrayBlockingQueue<>(1);
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<DatagramChannel>() {
                    @Override
                    protected void initChannel(DatagramChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                                ByteBuf content = msg.content();
                                byte[] copy = new byte[content.readableBytes()];
                                content.readBytes(copy);
                                received.offer(copy);
                            }
                        });
                    }
                });
        client = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        InetSocketAddress serverAddr = new InetSocketAddress("127.0.0.1", server.getPort());

        client.writeAndFlush(new DatagramPacket(
                Unpooled.wrappedBuffer(new byte[]{(byte) 0x80, 0x54, 0, 0}),
                serverAddr)).sync();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (server.peer() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertNotNull(server.peer(), "control server must learn peer from inbound datagram");

        server.requestResend(0x1234, 3);

        byte[] wire = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(wire, "resend datagram should reach peer");
        assertArrayEquals(AudioResendPacket.encode(0x1234, 3), wire);
    }
}
