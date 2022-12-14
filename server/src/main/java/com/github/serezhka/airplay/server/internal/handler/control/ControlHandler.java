package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class ControlHandler extends ChannelInboundHandlerAdapter {

    private static final String HEADER_CSEQ = "CSeq";
    private static final String HEADER_ACTIVE_REMOTE = "Active-Remote";

    private final SessionManager sessionManager;

    protected ControlHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest && handleRequest(ctx, (FullHttpRequest) msg))) {
            super.channelRead(ctx, msg);
        }
    }

    private boolean handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        return handleRequest(ctx, sessionManager.getSession(request.headers().get(HEADER_ACTIVE_REMOTE)), request);
    }

    protected abstract boolean handleRequest(ChannelHandlerContext ctx, Session session, FullHttpRequest request) throws Exception;

    protected DefaultFullHttpResponse createResponseForRequest(FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        response.headers().clear();

        var cSeq = request.headers().get(HEADER_CSEQ);
        if (cSeq != null) {
            response.headers().add(HEADER_CSEQ, cSeq);
        }

        return response;
    }

    protected boolean sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        var future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
        log.info("Request {} {} is handled!", request.method(), request.uri());
        return true;
    }
}
