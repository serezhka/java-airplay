package com.github.serezhka.airplay.server.internal.decoder;

import com.github.serezhka.airplay.server.internal.packet.VideoPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.util.List;

import android.util.Log;

public class VideoDecoder extends ReplayingDecoder<VideoDecoder.DecoderState> {
    private static String TAG = "VideoDecoder";

    public enum DecoderState {
        READ_HEADER,
        READ_PAYLOAD
    }

    public VideoDecoder() {
        super(DecoderState.READ_HEADER);
    }

    private int payloadSize;
    private short payloadType;
    // private short payloadOption;
    // private long timestamp;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        switch (state()) {
            case READ_HEADER:
                ByteBuf headerBuf = in.readSlice(128);
                payloadSize = (int) headerBuf.readUnsignedIntLE();
                payloadType = (short) (headerBuf.readUnsignedShortLE() & 0xff);
                // payloadOption = (short) headerBuf.readUnsignedShortLE();
                // timestamp = headerBuf.readLongLE();
                checkpoint(DecoderState.READ_PAYLOAD);
            case READ_PAYLOAD:
                if (payloadType == 0 || payloadType == 1) {
                    ByteBuf payloadBuf = in.readSlice(payloadSize);
                    byte[] payloadBytes = new byte[payloadSize];
                    payloadBuf.readBytes(payloadBytes);
                    checkpoint(DecoderState.READ_HEADER);
                    out.add(new VideoPacket(payloadType, payloadSize, payloadBytes));
                } else {
                    Log.i(TAG, String.format("Video packet with type: %d, length: %d bytes is skipped", payloadType, payloadSize));
                    in.skipBytes(payloadSize);
                    checkpoint(DecoderState.READ_HEADER);
                }
                break;
            default:
                throw new Error("Shouldn't reach here.");
        }
    }
}
