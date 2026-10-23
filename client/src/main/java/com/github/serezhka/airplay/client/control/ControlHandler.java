package com.github.serezhka.airplay.client.control;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ControlHandler extends ChannelInboundHandlerAdapter {

    private final BlockingQueue<FullHttpResponse> responseQueue = new LinkedBlockingQueue<>(1);
    private final CountDownLatch active = new CountDownLatch(1);

    private volatile ChannelHandlerContext ctx;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        active.countDown();
        log.info("Control client connected");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("Control client disconnected");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        responseQueue.put((FullHttpResponse) msg);
    }

    /**
     * Send already-framed RTSP bytes (bypasses {@code RtspEncoder}). Used when the encoder's
     * header formatting is rejected by a receiver that accepts raw RTSP as written.
     */
    public void sendRaw(byte[] rtspMessage) {
        awaitActive();
        this.ctx.writeAndFlush(Unpooled.wrappedBuffer(rtspMessage));
    }

    private void awaitActive() {
        try {
            if (!active.await(5, TimeUnit.SECONDS) || this.ctx == null) {
                throw new IllegalStateException("control channel not active");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for control channel", e);
        }
    }

    public FullHttpResponse receive() throws InterruptedException {
        FullHttpResponse response = responseQueue.poll(45, TimeUnit.SECONDS);
        if (response == null) {
            throw new IllegalStateException("timed out waiting for control response (45s)");
        }
        return response;
    }
}
