package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

@ChannelHandler.Sharable
public class PairingHandler extends ControlHandler {

    private final AirPlayConfig airPlayConfig;

    public PairingHandler(AirPlayConfig airPlayConfig, SessionManager sessionManager) {
        super(sessionManager);
        this.airPlayConfig = airPlayConfig;
    }

    @Override
    protected boolean handleRequest(ChannelHandlerContext ctx, Session session, FullHttpRequest request) throws Exception {
        var uri = request.uri();
        switch (uri) {
            case "/info": {
                var response = createResponseForRequest(request);
                session.getAirPlay().info(airPlayConfig.getWidth(), airPlayConfig.getHeight(), airPlayConfig.getFps(),
                        new ByteBufOutputStream(response.content()));
                return sendResponse(ctx, request, response);
            }
            case "/pair-setup": {
                var response = createResponseForRequest(request);
                session.getAirPlay().pairSetup(new ByteBufOutputStream(response.content()));
                return sendResponse(ctx, request, response);
            }
            case "/pair-verify": {
                var response = createResponseForRequest(request);
                session.getAirPlay().pairVerify(new ByteBufInputStream(request.content()),
                        new ByteBufOutputStream(response.content()));
                return sendResponse(ctx, request, response);
            }
        }
        return false;
    }
}
