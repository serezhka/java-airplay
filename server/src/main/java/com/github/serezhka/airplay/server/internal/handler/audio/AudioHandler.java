package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.lib.AirPlay;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@Slf4j
@RequiredArgsConstructor
public class AudioHandler extends ChannelInboundHandlerAdapter {

    private final AirPlay airPlay;
    private final AirPlayConsumer dataConsumer;

    private final AudioPacket[] buffer = new AudioPacket[512];

    private int prevSeqNum;
    private int packetsInBuffer;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        AudioPacket packet = (AudioPacket) msg;

        // TODO handle bad cases (missing packets, curSeqNum - prevSeqNum > buffer.length, ...)
        int curSeqNum = packet.getSequenceNumber();
        if (curSeqNum <= prevSeqNum) {
            return;
        }

        buffer[curSeqNum % buffer.length] = packet;
        packetsInBuffer++;

        while (dequeue(curSeqNum)) {
            curSeqNum++;
        }
    }

    private boolean dequeue(int curSeqNo) throws Exception {
        if (curSeqNo - prevSeqNum == 1 || prevSeqNum == 0) {
            AudioPacket audioPacket = buffer[curSeqNo % buffer.length];
            if (audioPacket != null && audioPacket.isAvailable()) {
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
