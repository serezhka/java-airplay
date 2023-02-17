package com.github.serezhka.airplay.server.internal.handler.video;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.packet.VideoPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class VideoHandler extends ChannelInboundHandlerAdapter {

    private final AirPlay airPlay;
    private final AirPlayConsumer dataConsumer;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        VideoPacket packet = (VideoPacket) msg;
        try {
            if (packet.getPayloadType() == 0) {
                airPlay.decryptVideo(packet.getPayload());
                preparePictureNALUnits(packet.getPayload());
                dataConsumer.onVideo(packet.getPayload());
            } else if (packet.getPayloadType() == 1) {
                byte[] spsPps = prepareSpsPpsNALUnits(packet.getPayload());
                dataConsumer.onVideo(spsPps);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    private void preparePictureNALUnits(byte[] payload) {
        int idx = 0;
        while (idx < payload.length) {
            int naluSize = (payload[idx + 3] & 0xFF) | ((payload[idx + 2] & 0xFF) << 8) | ((payload[idx + 1] & 0xFF) << 16) | ((payload[idx] & 0xFF) << 24);
            if (naluSize > 0) {
                payload[idx] = 0;
                payload[idx + 1] = 0;
                payload[idx + 2] = 0;
                payload[idx + 3] = 1;
                idx += naluSize + 4;
            }
            if (payload.length - naluSize > 4) {
                log.error("Video packet contains corrupted NAL unit. It might be decrypt error");
                return;
            }
        }
    }

    private byte[] prepareSpsPpsNALUnits(byte[] payload) {
        ByteBuf payloadBuf = Unpooled.wrappedBuffer(payload);
        payloadBuf.readerIndex(6);

        short spsLen = (short) payloadBuf.readUnsignedShort();
        byte[] sequenceParameterSet = new byte[spsLen];
        payloadBuf.readBytes(sequenceParameterSet);

        payloadBuf.skipBytes(1); // pps count

        short ppsLen = (short) payloadBuf.readUnsignedShort();
        byte[] pictureParameterSet = new byte[ppsLen];
        payloadBuf.readBytes(pictureParameterSet);

        int spsPpsLen = spsLen + ppsLen + 8;
        log.info("SPS PPS length: {}", spsPpsLen);
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

        return spsPps;
    }
}
