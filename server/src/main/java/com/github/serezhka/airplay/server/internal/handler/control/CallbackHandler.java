package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CallbackHandler extends ChannelInboundHandlerAdapter {

    private final ChannelHandlerContext mContext;

    private boolean init = false;

    public void sendEvent(String sessionId, String listUri) {
        var requestContent = PropertyListUtil.prepareEventRequest(sessionId, listUri);

        DefaultFullHttpRequest event = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/event");
        event.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        event.headers().add(HttpHeaderNames.CONTENT_LENGTH, requestContent.length);
        event.headers().add("X-Apple-Session-ID", sessionId);
        event.content().writeBytes(requestContent);

        if (!init) {
            mContext.pipeline().remove(RtspDecoder.class);
            mContext.pipeline().remove(RtspEncoder.class);
            mContext.pipeline().remove(HttpObjectAggregator.class);
            mContext.pipeline().addFirst(new HttpClientCodec());
            init = true;
        }

        mContext.writeAndFlush(event);
    }
}
