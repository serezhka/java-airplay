package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.lindstrom.m3u8.model.*;
import io.lindstrom.m3u8.parser.MasterPlaylistParser;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.*;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import android.util.Log;

@RequiredArgsConstructor
public class ControlHandler extends ChannelInboundHandlerAdapter {
    private static String TAG = "ControlHandler";

    private final SessionManager sessionManager;
    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {
            if (RtspVersions.RTSP_1_0.equals(request.protocolVersion())) {
                if (HttpMethod.GET.equals(request.method()) && "/info".equals(request.uri())) {
                    handleGetInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/pair-setup".equals(request.uri())) {
                    handlePairSetup(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/pair-verify".equals(request.uri())) {
                    handlePairVerify(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/fp-setup".equals(request.uri())) {
                    handleFairPlaySetup(ctx, request);
                } else if (RtspMethods.SETUP.equals(request.method())) {
                    handleRtspSetup(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && "/feedback".equals(request.uri())) {
                    handleRtspFeedback(ctx, request);
                } else if (RtspMethods.GET_PARAMETER.equals(request.method())) {
                    handleRtspGetParameter(ctx, request);
                } else if (RtspMethods.RECORD.equals(request.method())) {
                    handleRtspRecord(ctx, request);
                } else if (RtspMethods.SET_PARAMETER.equals(request.method())) {
                    handleRtspSetParameter(ctx, request);
                } else if ("FLUSH".equals(request.method().toString())) {
                    handleRtspFlush(ctx, request);
                } else if (RtspMethods.TEARDOWN.equals(request.method())) {
                    handleRtspTeardown(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && request.uri().equals("/audioMode")) {
                    handleRtspAudioMode(ctx, request);
                } else {
                    Log.e(TAG, String.format("Unknown control request: %s %s %s", request.protocolVersion(), request.method(), request.uri()));
                    var response = createRtspResponse(request);
                    response.setStatus(HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
            } else if (HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
                var decoder = new QueryStringDecoder(request.uri());
                if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/server-info")) {
                    handleGetServerInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/reverse")) {
                    handleReverse(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/play")) {
                    handlePlay(ctx, request);
                } else if (HttpMethod.PUT.equals(request.method()) && decoder.path().equals("/setProperty")) {
                    handleSetProperty(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/rate")) {
                    handleRate(ctx, request);
                } else if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/playback-info")) {
                    handlePlaybackInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/action")) {
                    handleAction(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/getProperty")) {
                    handleGetProperty(ctx, request);
                } else if (HttpMethod.GET.equals(request.method()) && decoder.path().startsWith("/playlist")) {
                    handleGetPlaylist(ctx, request);
                } else {
                    Log.e(TAG, String.format("Unknown control request: %s %s %s", request.protocolVersion(), request.method(), request.uri()));
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
            }
        } else if (msg instanceof FullHttpResponse response) {
            // reverse connection response
        } else {
            Log.e(TAG, String.format("Unknown control message type: %s", msg));
        }
    }

    /**
     * Resolves session by the request headers:<br/>
     * {@code Active-Remote} for RTSP<br/>
     * {@code X-Apple-Session-ID} for HTTP
     *
     * @param request incoming request
     * @return active session
     */
    private Session resolveSession(FullHttpRequest request) {
        var sessionId = Optional.ofNullable(request.headers().get("Active-Remote"))
            .orElseGet(() -> request.headers().get("X-Apple-Session-ID"));
        return sessionManager.getSession(sessionId);
    }

    private void handleGetInfo(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var info = PropertyListUtil.prepareInfoResponse(airPlayConfig);
        var response = createRtspResponse(request);
        response.content().writeBytes(info);
        sendResponse(ctx, request, response);
    }

    private void handlePairSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = createRtspResponse(request);
        session.getAirPlay().pairSetup(new ByteBufOutputStream(response.content()));
        sendResponse(ctx, request, response);
    }

    private void handlePairVerify(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = createRtspResponse(request);
        session.getAirPlay().pairVerify(new ByteBufInputStream(request.content()),
                new ByteBufOutputStream(response.content()));
        sendResponse(ctx, request, response);
    }

    private void handleFairPlaySetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = createRtspResponse(request);
        session.getAirPlay().fairPlaySetup(new ByteBufInputStream(request.content()),
                new ByteBufOutputStream(response.content()));
        sendResponse(ctx, request, response);
    }

    private void handleRtspSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = createRtspResponse(request);
        var mediaStreamInfo = session.getAirPlay().rtspSetup(new ByteBufInputStream(request.content()));
        byte[] setup;
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO:
                    airPlayConsumer.onAudioFormat((AudioStreamInfo) mediaStreamInfo.get());
                    session.getAudioServer().start(airPlayConsumer);
                    session.getAudioControlServer().start();
                    setup = PropertyListUtil.prepareSetupAudioResponse(session.getAudioServer().getPort(),
                            session.getAudioControlServer().getPort());
                    response.content().writeBytes(setup);
                    break;
                case VIDEO:
                    airPlayConsumer.onVideoFormat((VideoStreamInfo) mediaStreamInfo.get());
                    session.getVideoServer().start(airPlayConsumer);
                    setup = PropertyListUtil.prepareSetupVideoResponse(session.getVideoServer().getPort(),
                            ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort(), 0);
                    response.content().writeBytes(setup);
                    break;
            }
        }
        sendResponse(ctx, request, response);
    }

    private void handleRtspFeedback(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = createRtspResponse(request);
        sendResponse(ctx, request, response);
    }

    private void handleRtspGetParameter(ChannelHandlerContext ctx, FullHttpRequest request) {
        // TODO get requested param and respond accordingly
        byte[] content = "volume: 0.000000\r\n".getBytes(StandardCharsets.US_ASCII);
        var response = createRtspResponse(request);
        response.content().writeBytes(content);
        sendResponse(ctx, request, response);
    }

    private void handleRtspRecord(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = createRtspResponse(request);
        response.headers().add("Audio-Latency", "11025");
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        sendResponse(ctx, request, response);
    }

    private void handleRtspSetParameter(ChannelHandlerContext ctx, FullHttpRequest request) {
        // TODO get requested param and respond accordingly
        var response = createRtspResponse(request);
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        sendResponse(ctx, request, response);
    }

    private void handleRtspFlush(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = createRtspResponse(request);
        sendResponse(ctx, request, response);
    }

    private void handleRtspTeardown(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var mediaStreamInfo = session.getAirPlay().rtspTeardown(new ByteBufInputStream(request.content()));
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO:
                    airPlayConsumer.onAudioSrcDisconnect();
                    session.getAudioServer().stop();
                    session.getAudioControlServer().stop();
                    break;
                case VIDEO:
                    airPlayConsumer.onVideoSrcDisconnect();
                    session.getVideoServer().stop();
                    break;
            }
        } else {
            airPlayConsumer.onAudioSrcDisconnect();
            airPlayConsumer.onVideoSrcDisconnect();
            session.getAudioServer().stop();
            session.getAudioControlServer().stop();
            session.getVideoServer().stop();
        }
        var response = createRtspResponse(request);
        sendResponse(ctx, request, response);
    }

    private void handleRtspAudioMode(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = createRtspResponse(request);
        sendResponse(ctx, request, response);
    }

    private void handleGetServerInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var serverInfo = PropertyListUtil.prepareServerInfoResponse();
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        response.content().writeBytes(serverInfo);
        sendResponse(ctx, request, response);
    }

    private void handleReverse(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
        response.headers().add(HttpHeaderNames.UPGRADE, request.headers().get(HttpHeaderNames.UPGRADE));
        response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        sendResponse(ctx, request, response);

        var purpose = request.headers().get("X-Apple-Purpose");
        ctx.pipeline().remove(RtspDecoder.class);
        ctx.pipeline().remove(RtspEncoder.class);
        ctx.pipeline().addFirst(new HttpClientCodec());
        var session = resolveSession(request);
        session.getReverseContexts().put(purpose, ctx);
    }

    private void handlePlay(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        Log.i(TAG, String.format("Request content:\n%s", play.toXMLPropertyList()));

        var clientProcName = play.get("clientProcName").toJavaObject(String.class);
        if ("YouTube".equals(clientProcName)) {
            var session = resolveSession(request);
            var playlistUri = play.get("Content-Location").toJavaObject(String.class);
            var playlistUriLocal = playlistUriToLocal(playlistUri, playlistBaseUrl(ctx), session.getId());

            airPlayConsumer.onMediaPlaylist(playlistUriLocal);

            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            sendResponse(ctx, request, response);
        } else {
            Log.e(TAG, String.format("Client proc name [%s] is not supported!", clientProcName));
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED);
            sendResponse(ctx, request, response);
        }
    }

    private void handleSetProperty(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var decoder = new QueryStringDecoder(request.uri());
        Log.i(TAG, String.format("Path: %s, Query params: %s", decoder.path(), decoder.parameters()));
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        Log.i(TAG, String.format("Request content:\n%s", play.toXMLPropertyList()));

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleRate(ChannelHandlerContext ctx, FullHttpRequest request) {
        var decoder = new QueryStringDecoder(request.uri());
        Log.i(TAG, String.format("Path: %s, Query params: %s", decoder.path(), decoder.parameters()));

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handlePlaybackInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        var playbackInfo = PropertyListUtil.preparePlaybackInfoResponse();
        response.content().writeBytes(playbackInfo);
        sendResponse(ctx, request, response);
    }

    private void handleAction(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // TODO: reimplement handleAction function
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleGetProperty(ChannelHandlerContext ctx, FullHttpRequest request) {
        // TODO get requested param and respond accordingly
        var decoder = new QueryStringDecoder(request.uri());
        Log.i(TAG, String.format("Path: %s, Query params: %s", decoder.path(), decoder.parameters()));
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleGetPlaylist(ChannelHandlerContext ctx, FullHttpRequest request) {
        Log.w(TAG, String.format("Playlist request: %s", request.uri()));
        var playlistUriRemote = playlistPathToRemote(request.uri());
        var decoder = new QueryStringDecoder(request.uri());
        var session = sessionManager.getSession(decoder.parameters().get("session").get(0));
        session.getPlaylistRequestContexts().put(playlistUriRemote, ctx);
        sendEventRequest(session, playlistUriRemote);
    }

    private String playlistUriToLocal(String playlistUri, String baseUrl, String sessionId) {
        var playlistUriLocal = playlistUri.replace("mlhls://localhost", baseUrl);
        var queryEncoder = new QueryStringEncoder(playlistUriLocal);
        queryEncoder.addParam("session", sessionId);
        return queryEncoder.toString();
    }

    private String playlistPathToRemote(String playlistPath) {
        var playlistUriLocal = "mlhls://localhost" + playlistPath.replace("/playlist", "");
        return playlistUriLocal.split("\\?")[0]; // remove query
    }

    private String playlistBaseUrl(ChannelHandlerContext ctx) {
        var port = ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort();
        return String.format("http://localhost:%s/playlist", port);
    }

    private DefaultFullHttpResponse createRtspResponse(FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
        response.headers().clear();

        var cSeq = request.headers().get(RtspHeaderNames.CSEQ);
        if (cSeq != null) {
            response.headers().add(RtspHeaderNames.CSEQ, cSeq);
            response.headers().add(RtspHeaderNames.SERVER, "AirTunes/220.68");
        }

        return response;
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        HttpUtil.setContentLength(response, response.content().readableBytes());
        var future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void sendEventRequest(Session session, String listUri) {
        var requestContent = PropertyListUtil.prepareEventRequest(session.getId(), listUri);

        DefaultFullHttpRequest event = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/event");
        event.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        event.headers().add(HttpHeaderNames.CONTENT_LENGTH, requestContent.length);
        event.headers().add("X-Apple-Session-ID", session.getId());
        event.content().writeBytes(requestContent);

        session.getReverseContexts().get("event").writeAndFlush(event);
    }
}
