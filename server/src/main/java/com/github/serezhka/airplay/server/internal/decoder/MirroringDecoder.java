package com.github.serezhka.airplay.server.internal.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ReplayingDecoder;

import java.util.List;


public class MirroringDecoder extends ReplayingDecoder<MirroringDecoder.DecoderState> {

    public enum DecoderState {
        READ_HEADER,
        READ_PAYLOAD
    }

    public MirroringDecoder() {
        super(DecoderState.READ_HEADER);
    }

    private int payloadSize;
    private short payloadType;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        switch (state()) {
            case READ_HEADER:
                ByteBuf headerBuf = in.readSlice(128);
                payloadSize = (int) headerBuf.readUnsignedIntLE();
                payloadType = (short) (headerBuf.readUnsignedShortLE() & 0xff);
                checkpoint(DecoderState.READ_PAYLOAD);
            case READ_PAYLOAD:
                if (payloadType == 0 || payloadType == 1) {
                    ByteBuf payloadBuf = in.readSlice(payloadSize);
                    MirroringData data = processPayload(payloadBuf);
                    checkpoint(DecoderState.READ_HEADER);
                    out.add(data);
                } else {
                    in.skipBytes(payloadSize);
                    checkpoint(DecoderState.READ_HEADER);
                }
                break;
            default:
                throw new Error("Shouldn't reach here.");
        }

        // TODO Profile and optimize

        /*ByteBuf headerBuf = in.readSlice(128);

        int payloadSize = (int) headerBuf.readUnsignedIntLE();
        short payloadType = (short) (headerBuf.readUnsignedShortLE() & 0xff);
        short payloadOption = (short) headerBuf.readUnsignedShortLE();

        MirroringData data;

        if (payloadType == 0) {
            byte[] payloadBytes = new byte[payloadSize];
            in.readBytes(payloadBytes);
            data = new MirroringData(payloadType, payloadSize, payloadBytes);
        } else if (payloadType == 1) {
            headerBuf.readerIndex(40);
            int widthSource = (int) headerBuf.readFloatLE();
            int heightSource = (int) headerBuf.readFloatLE();
            headerBuf.readerIndex(56);
            int width = (int) headerBuf.readFloatLE();
            int height = (int) headerBuf.readFloatLE();

            int indAfterReadSpsPps = in.readerIndex() + payloadSize;

            in.readBytes(6);

            short spsLen = (short) in.readUnsignedShort();
            byte[] sequenceParameterSet = new byte[spsLen];
            in.readBytes(sequenceParameterSet);

            in.readBytes(1); // pps count

            short ppsLen = (short) in.readUnsignedShort();
            byte[] pictureParameterSet = new byte[ppsLen];
            in.readBytes(pictureParameterSet);

            int spsPpsLen = spsLen + ppsLen + 8;
            byte[] spsPps = new byte[spsPpsLen];
            spsPps[0] = 0;
            spsPps[1] = 0;
            spsPps[2] = 0;
            spsPps[3] = 1;
            System.arraycopy(sequenceParameterSet, 0, spsPps, 4, spsLen);
            spsPps[spsLen + 4] = 0;
            spsPps[spsLen + 5] = 0;
            spsPps[spsLen + 6] = 0;
            spsPps[spsLen + 7] = 1;
            System.arraycopy(pictureParameterSet, 0, spsPps, 8 + spsLen, ppsLen);
            data = new MirroringData(payloadType, payloadSize, spsPps);
            in.readerIndex(indAfterReadSpsPps);
        } else {
            in.skipBytes(payloadSize);
            data = new MirroringData(payloadType, payloadSize, null);
        }

        out.add(data);*/
    }

    private MirroringData processPayload(ByteBuf payloadBuf) {
        if (payloadType == 0) {
            byte[] payloadBytes = new byte[payloadSize];
            payloadBuf.readBytes(payloadBytes);
            return new MirroringData(payloadType, payloadSize, payloadBytes);
        } else if (payloadType == 1) {
            payloadBuf.skipBytes(6);

            short spsLen = (short) payloadBuf.readUnsignedShort();
            byte[] sequenceParameterSet = new byte[spsLen];
            payloadBuf.readBytes(sequenceParameterSet);

            payloadBuf.skipBytes(1); // pps count

            short ppsLen = (short) payloadBuf.readUnsignedShort();
            byte[] pictureParameterSet = new byte[ppsLen];
            payloadBuf.readBytes(pictureParameterSet);

            int spsPpsLen = spsLen + ppsLen + 8;
            byte[] spsPps = new byte[spsPpsLen];
            spsPps[0] = 0;
            spsPps[1] = 0;
            spsPps[2] = 0;
            spsPps[3] = 1;
            System.arraycopy(sequenceParameterSet, 0, spsPps, 4, spsLen);
            spsPps[spsLen + 4] = 0;
            spsPps[spsLen + 5] = 0;
            spsPps[spsLen + 6] = 0;
            spsPps[spsLen + 7] = 1;
            System.arraycopy(pictureParameterSet, 0, spsPps, 8 + spsLen, ppsLen);
            return new MirroringData(payloadType, payloadSize, spsPps);
        } else {
            return new MirroringData(payloadType, payloadSize, null);
        }
    }
}
