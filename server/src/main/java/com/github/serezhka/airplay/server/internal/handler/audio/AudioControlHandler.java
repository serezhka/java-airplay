package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

@Slf4j
public class AudioControlHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final Consumer<InetSocketAddress> peerObserver;
    private final Consumer<AudioPacket> retransmitSink;

    public AudioControlHandler(Consumer<InetSocketAddress> peerObserver,
                               Consumer<AudioPacket> retransmitSink) {
        this.peerObserver = peerObserver;
        this.retransmitSink = retransmitSink;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
        InetSocketAddress sender = msg.sender();
        if (sender != null && peerObserver != null) {
            peerObserver.accept(sender);
        }
        ByteBuf content = msg.content();
        int readable = content.readableBytes();
        if (readable < 2) {
            return;
        }
        int typeByte = content.getByte(content.readerIndex() + 1);
        if (!AudioResendPacket.isReplyType(typeByte)) {
            log.debug("Audio control packet type={} len={} from={}", typeByte & 0x7F, readable, sender);
            return;
        }
        AudioPacket packet = AudioResendPacket.parseReply(content);
        if (packet == null) {
            log.debug("Audio resend reply too short len={} from={}", readable, sender);
            return;
        }
        Consumer<AudioPacket> sink = retransmitSink;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(packet);
        } catch (Exception e) {
            log.debug("Audio resend reply inject failed: {}", e.toString());
        }
    }
}
