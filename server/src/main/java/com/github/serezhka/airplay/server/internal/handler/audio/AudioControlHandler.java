package com.github.serezhka.airplay.server.internal.handler.audio;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

@Slf4j
@RequiredArgsConstructor
public class AudioControlHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final Consumer<InetSocketAddress> peerObserver;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
        InetSocketAddress sender = msg.sender();
        if (sender != null && peerObserver != null) {
            peerObserver.accept(sender);
        }
        int readable = msg.content().readableBytes();
        if (readable >= 2) {
            int type = msg.content().getByte(msg.content().readerIndex() + 1) & ~0x80;
            log.debug("Audio control packet type={} len={} from={}", type, readable, sender);
        }
    }
}
