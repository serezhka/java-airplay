package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.ControlExchange;
import com.github.serezhka.airplay.server.internal.handler.session.PlaylistRequest;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.rtsp.RtspHeaderNames;
import io.netty.handler.codec.rtsp.RtspResponseStatuses;
import io.netty.handler.codec.rtsp.RtspVersions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Shared RTSP/HTTP response helpers and control-exchange publish for {@link ControlHandler}.
 */
@Slf4j
@RequiredArgsConstructor
final class ControlResponses {

    private final Playback airPlayConsumer;
    private final Supplier<byte[]> pendingRequestBody;

    DefaultFullHttpResponse createRtspResponse(FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        response.headers().clear();

        var cSeq = request.headers().get(RtspHeaderNames.CSEQ);
        if (cSeq != null) {
            response.headers().add(RtspHeaderNames.CSEQ, cSeq);
            response.headers().add(RtspHeaderNames.SERVER, "AirTunes/220.68");
        }
        return response;
    }

    void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        publishControlExchange(request, response);
        var future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    void publishControlExchange(FullHttpRequest request, FullHttpResponse response) {
        publishControlExchange(
                Optional.ofNullable(request.headers().get("Active-Remote"))
                        .orElseGet(() -> request.headers().get("X-Apple-Session-ID")),
                request.protocolVersion().text(),
                request.method().name(),
                request.uri(),
                copyHeaders(request.headers()),
                pendingRequestBody.get(),
                response);
    }

    void publishControlExchange(PlaylistRequest pending, FullHttpResponse response) {
        publishControlExchange(
                pending.requestHeaders().get("X-Apple-Session-ID"),
                pending.protocol(),
                pending.method(),
                pending.uri(),
                pending.requestHeaders(),
                pending.requestBody(),
                response);
    }

    void publishControlExchange(String sessionId, String protocol, String method, String uri,
                                Map<String, String> requestHeaders, byte[] requestBody,
                                FullHttpResponse response) {
        try {
            airPlayConsumer.onControlExchange(new ControlExchange(
                    Instant.now(),
                    sessionId,
                    protocol,
                    method,
                    uri,
                    requestHeaders,
                    requestBody,
                    response.status().code(),
                    copyHeaders(response.headers()),
                    ByteBufUtil.getBytes(response.content())));
        } catch (Exception e) {
            log.warn("Failed to publish control exchange", e);
        }
    }

    static Map<String, String> copyHeaders(HttpHeaders headers) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers) {
            copy.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return copy;
    }
}
