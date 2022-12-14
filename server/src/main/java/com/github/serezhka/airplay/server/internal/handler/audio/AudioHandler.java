package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.AirplayDataConsumer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@Slf4j
public class AudioHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final AirPlay airPlay;
    private final AirplayDataConsumer dataConsumer;

    private final AudioPacket[] buffer = new AudioPacket[512];

    private int prevSeqNum;
    private int packetsInBuffer;

    public AudioHandler(AirPlay airPlay, AirplayDataConsumer dataConsumer) {
        this.airPlay = airPlay;
        this.dataConsumer = dataConsumer;
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = new AudioPacket();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
        ByteBuf content = msg.content();

        byte[] headerBytes = new byte[12];
        content.readBytes(headerBytes);

        int flag = headerBytes[0] & 0xFF;
        int type = headerBytes[1] & 0x7F;

        int curSeqNo = ((headerBytes[2] & 0xFF) << 8) | (headerBytes[3] & 0xFF);

        long timestamp = (headerBytes[7] & 0xFF) | ((headerBytes[6] & 0xFF) << 8) |
                ((headerBytes[5] & 0xFF) << 16) | ((headerBytes[4] & 0xFF) << 24);

        long ssrc = (headerBytes[11] & 0xFF) | ((headerBytes[6] & 0xFF) << 8) |
                ((headerBytes[9] & 0xFF) << 16) | ((headerBytes[8] & 0xFF) << 24);

        // TODO handle bad cases (missing packets, curSeqNum - prevSeqNum > buffer.length, ...)
        if (curSeqNo <= prevSeqNum) {
            return;
        }

        /*log.debug("Got audio packet. flag: {}, type: {}, prevSeqNum: {}, curSecNum: {}, audio packets in buffer: {}",
                flag, type, prevSeqNum, curSeqNo, packetsInBuffer);*/

        AudioPacket audioPacket = buffer[curSeqNo % buffer.length];
        audioPacket
                .flag(flag)
                .type(type)
                .sequenceNumber(curSeqNo)
                .timestamp(timestamp)
                .ssrc(ssrc)
                .available(true)
                .encodedAudioSize(content.readableBytes())
                .encodedAudio(packet -> content.readBytes(packet, 0, content.readableBytes()));
        packetsInBuffer++;

        while (dequeue(curSeqNo)) {
            curSeqNo++;
        }
    }

    private boolean dequeue(int curSeqNo) throws Exception {
        if (curSeqNo - prevSeqNum == 1 || prevSeqNum == 0) {
            AudioPacket audioPacket = buffer[curSeqNo % buffer.length];
            if (audioPacket.isAvailable()) {
                airPlay.decryptAudio(audioPacket.getEncodedAudio(), audioPacket.getEncodedAudioSize());
                dataConsumer.onAudio(Arrays.copyOfRange(audioPacket.getEncodedAudio(), 0, audioPacket.getEncodedAudioSize()));
                audioPacket.available(false);
                prevSeqNum = curSeqNo;
                packetsInBuffer--;
                return true;
            }
        }
        return false;
    }
}
