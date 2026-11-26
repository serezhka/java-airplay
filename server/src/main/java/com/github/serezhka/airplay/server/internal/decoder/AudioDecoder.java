package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

public class AudioDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        AudioPacket packet = parseRtp(msg);
        if (packet != null) {
            out.add(packet);
        }
    }

    /**
     * Parse one RTP audio datagram (12-byte header + encrypted AU).
     * Also used for control-channel resend replies that embed the same RTP frame.
     */
    public static AudioPacket parseRtp(ByteBuf msg) {
        if (msg == null || msg.readableBytes() < 12) {
            return null;
        }
        byte[] headerBytes = new byte[12];
        msg.readBytes(headerBytes);

        int flag = headerBytes[0] & 0xFF;
        int type = headerBytes[1] & 0x7F;
        int seqNumber = ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);
        long timestamp = readUnsignedIntBigEndian(headerBytes, 4);
        long ssrc = readUnsignedIntBigEndian(headerBytes, 8);

        int payloadLen = msg.readableBytes();
        AudioPacket audioPacket = AudioPacket.builder()
                .flag(flag)
                .type(type)
                .sequenceNumber(seqNumber)
                .timestamp(timestamp)
                .ssrc(ssrc)
                .available(true)
                .encodedAudioSize(payloadLen)
                .build();
        audioPacket.encodedAudio(packet -> msg.readBytes(packet, 0, payloadLen));
        return audioPacket;
    }

    private static long readUnsignedIntBigEndian(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFF) << 24 |
                ((long) bytes[offset + 1] & 0xFF) << 16 |
                ((long) bytes[offset + 2] & 0xFF) << 8 |
                ((long) bytes[offset + 3] & 0xFF);
    }
}
