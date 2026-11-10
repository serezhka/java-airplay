package com.github.serezhka.airplay.server.internal.handler.video;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout;
import com.github.serezhka.airplay.server.Playback;
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

    private final FairPlaySession airPlay;
    private final Playback dataConsumer;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        VideoPacket packet = (VideoPacket) msg;
        try {
            if (packet.getPayloadType() == MirrorPacketLayout.PAYLOAD_VIDEO) {
                // Type 0 only: type 1 codec config is not AES-CTR-encrypted on the wire;
                // decrypting it advances the CTR counter and corrupts every later frame.
                // Type 5 is an unencrypted streaming-report plist — same trap.
                airPlay.decryptVideo(packet.getPayload());
                // Never feed partial/corrupt Annex-B to the consumer.
                if (!toAnnexB(packet.getPayload())) {
                    return;
                }
                dataConsumer.onVideo(packet.getPayload());
            } else if (packet.getPayloadType() == MirrorPacketLayout.PAYLOAD_CODEC) {
                byte[] spsPps = prepareSpsPpsNALUnits(packet.getPayload());
                if (spsPps != null) {
                    dataConsumer.onVideo(spsPps);
                }
            }
        } catch (Exception e) {
            // Mirror NALs still arrive for a moment after HLS tears the video process down.
            // A stack trace per packet stalls the event loop and delays /play.
            if (e instanceof IllegalStateException && e.getMessage() != null
                    && e.getMessage().contains("not running")) {
                log.debug("Dropping video packet: {}", e.getMessage());
            } else {
                log.error(e.getMessage(), e);
            }
        }
    }

    /** @return false if length-prefixed NALs are invalid (likely bad decrypt) */
    static boolean toAnnexB(byte[] payload) {
        int idx = 0;
        while (idx + 4 <= payload.length) {
            int naluSize = (payload[idx + 3] & 0xFF)
                    | ((payload[idx + 2] & 0xFF) << 8)
                    | ((payload[idx + 1] & 0xFF) << 16)
                    | ((payload[idx] & 0xFF) << 24);
            if (naluSize <= 0 || idx + 4 + naluSize > payload.length) {
                log.error("Video packet contains corrupted NAL unit. It might be decrypt error");
                return false;
            }
            payload[idx] = 0;
            payload[idx + 1] = 0;
            payload[idx + 2] = 0;
            payload[idx + 3] = 1;
            idx += naluSize + 4;
        }
        return true;
    }

    /**
     * Parse type-1 codec config into Annex-B SPS+PPS.
     * @return null if the payload is empty/truncated (phone may probe with a zero-length type-1)
     */
    static byte[] prepareSpsPpsNALUnits(byte[] payload) {
        if (payload == null || payload.length < 11) {
            // Minimum: 6-byte prefix + 2 spsLen + ≥1 sps + 1 ppsCount + 2 ppsLen
            log.debug("Skipping type-1 codec packet: too short ({})",
                    payload == null ? -1 : payload.length);
            return null;
        }
        ByteBuf payloadBuf = Unpooled.wrappedBuffer(payload);
        try {
            payloadBuf.readerIndex(6);

            int spsLen = payloadBuf.readUnsignedShort();
            if (spsLen <= 0 || payloadBuf.readableBytes() < spsLen + 1 + 2) {
                log.debug("Skipping type-1 codec packet: bad spsLen={}", spsLen);
                return null;
            }
            byte[] sequenceParameterSet = new byte[spsLen];
            payloadBuf.readBytes(sequenceParameterSet);

            payloadBuf.skipBytes(1); // pps count

            int ppsLen = payloadBuf.readUnsignedShort();
            if (ppsLen <= 0 || payloadBuf.readableBytes() < ppsLen) {
                log.debug("Skipping type-1 codec packet: bad ppsLen={}", ppsLen);
                return null;
            }
            byte[] pictureParameterSet = new byte[ppsLen];
            payloadBuf.readBytes(pictureParameterSet);

            int spsPpsLen = spsLen + ppsLen + 8;
            log.debug("SPS PPS length: {}", spsPpsLen);
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
        } finally {
            payloadBuf.release();
        }
    }
}
