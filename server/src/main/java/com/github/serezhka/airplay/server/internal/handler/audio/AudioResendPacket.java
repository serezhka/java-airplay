package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.server.internal.decoder.AudioDecoder;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Classic AirPlay audio retransmission on the control UDP port.
 * Request wire: {@code 80 D5 | reqSeq | firstMissing | count}.
 * Reply: 4-byte header (type {@code 0x56}/{@code 0xD6}) then a full audio RTP packet.
 */
public final class AudioResendPacket {

    /** Payload type without marker bit. */
    public static final int TYPE_REQUEST = 0x55;
    public static final int TYPE_REPLY = 0x56;
    /** Bytes before the embedded RTP frame in a reply datagram. */
    public static final int REPLY_HEADER_LEN = 4;

    private AudioResendPacket() {
    }

    public static byte[] encode(int firstMissingSeq, int count) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x80);
        buf.put((byte) (TYPE_REQUEST | 0x80));
        buf.putShort((short) 1);
        buf.putShort((short) (firstMissingSeq & 0xFFFF));
        buf.putShort((short) (count & 0xFFFF));
        return buf.array();
    }

    public static boolean isReplyType(int typeByte) {
        return (typeByte & 0x7F) == TYPE_REPLY;
    }

    /**
     * Extract the embedded RTP audio packet from a control datagram, or {@code null}.
     */
    public static AudioPacket parseReply(ByteBuf controlDatagram) {
        if (controlDatagram == null) {
            return null;
        }
        int ri = controlDatagram.readerIndex();
        int len = controlDatagram.readableBytes();
        if (len <= REPLY_HEADER_LEN + 12) {
            return null;
        }
        if (!isReplyType(controlDatagram.getByte(ri + 1))) {
            return null;
        }
        ByteBuf rtp = controlDatagram.slice(ri + REPLY_HEADER_LEN, len - REPLY_HEADER_LEN);
        return AudioDecoder.parseRtp(rtp);
    }
}
