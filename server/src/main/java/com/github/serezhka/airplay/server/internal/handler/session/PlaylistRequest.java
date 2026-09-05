package com.github.serezhka.airplay.server.internal.handler.session;

import io.netty.channel.ChannelHandlerContext;

import java.util.Map;

public record PlaylistRequest(
        ChannelHandlerContext context,
        String protocol,
        String method,
        String uri,
        Map<String, String> requestHeaders,
        byte[] requestBody,
        boolean keepAlive
) {
}
