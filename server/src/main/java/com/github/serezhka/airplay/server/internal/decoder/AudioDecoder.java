package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class AudioDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        byte[] headerBytes = new byte[12];
        msg.readBytes(headerBytes);

        int flag = headerBytes[0] & 0xFF;
        int type = headerBytes[1] & 0x7F;

        int seqNumber = ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);

        long timestamp = (headerBytes[7] & 0xFF) | ((headerBytes[6] & 0xFF) << 8) |
                ((headerBytes[5] & 0xFF) << 16) | ((headerBytes[4] & 0xFF) << 24);

        long ssrc = (headerBytes[11] & 0xFF) | ((headerBytes[6] & 0xFF) << 8) |
                ((headerBytes[9] & 0xFF) << 16) | ((headerBytes[8] & 0xFF) << 24);

        AudioPacket audioPacket = AudioPacket.builder()
                .flag(flag)
                .type(type)
                .sequenceNumber(seqNumber)
                .timestamp(timestamp)
                .ssrc(ssrc)
                .available(true)
                .encodedAudioSize(msg.readableBytes())
                .build();
        audioPacket.encodedAudio(packet -> msg.readBytes(packet, 0, msg.readableBytes()));
        out.add(audioPacket);
    }
}
