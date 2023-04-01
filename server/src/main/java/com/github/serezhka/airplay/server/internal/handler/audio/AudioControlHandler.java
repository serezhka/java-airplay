package com.github.serezhka.airplay.server.internal.handler.audio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import android.util.Log;

public class AudioControlHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static String TAG = "AudioControlHandler";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
        ByteBuf content = msg.content();
        int contentLength = content.readableBytes();
        byte[] contentBytes = new byte[contentLength];
        content.readBytes(contentBytes);
        int type = contentBytes[1] & ~0x80;
        Log.d(TAG, String.format("Got audio control packet, type: %d, length: %d", type, contentLength));
    }
}
