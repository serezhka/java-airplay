package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.ControlExchange;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.HlsUriRewrite;
import com.github.serezhka.airplay.server.internal.handler.session.PlaylistRequest;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.lindstrom.m3u8.model.*;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class ControlHandler extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;
    private final HlsFcupService hlsFcupService;
    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;

    public ControlHandler(SessionManager sessionManager,
                          HlsFcupService hlsFcupService,
                          AirPlayConfig airPlayConfig,
                          AirPlayConsumer airPlayConsumer) {
        this.sessionManager = sessionManager;
        this.hlsFcupService = hlsFcupService;
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
    }

    private byte[] pendingRequestBody = new byte[0];

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof FullHttpRequest request) {
                pendingRequestBody = ByteBufUtil.getBytes(request.content());
                try {
                    dispatchControlRequest(ctx, request);
                } finally {
                    pendingRequestBody = new byte[0];
                }
            } else if (msg instanceof FullHttpResponse response) {
                log.debug("Reverse channel response: {} {}", response.status(), response.content().readableBytes());
            } else {
                log.error("Unknown control message type: {}", msg);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void dispatchControlRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
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
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = createRtspResponse(request);
                    response.setStatus(HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
            } else if (HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
                var decoder = new QueryStringDecoder(request.uri());
                if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/server-info")) {
                    handleGetServerInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/fp-setup")) {
                    // TODO handleFairPlaySetup(ctx, request);
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/fp-setup2")) {
                    // TODO handleFairPlaySetup2(ctx, request);
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
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/scrub")) {
                    log.info(request.uri()); // TODO
                } else if (HttpMethod.POST.equals(request.method()) && decoder.path().equals("/stop")) {
                    log.info(request.uri()); // TODO
                } else if (HttpMethod.GET.equals(request.method()) && decoder.path().startsWith("/playlist")) {
                    handleGetPlaylist(ctx, request);
                } else {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
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

    /*private void handleFairPlaySetup2(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }*/

    private void handleRtspSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = createRtspResponse(request);
        var mediaStreamInfo = session.getAirPlay().rtspSetup(new ByteBufInputStream(request.content()));
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO -> {
                    airPlayConsumer.onAudioFormat((AudioStreamInfo) mediaStreamInfo.get());
                    session.getAudioServer().start(airPlayConsumer);
                    session.getAudioControlServer().start();
                    var setup = PropertyListUtil.prepareSetupAudioResponse(session.getAudioServer().getPort(),
                            session.getAudioControlServer().getPort());
                    response.content().writeBytes(setup);
                }
                case VIDEO -> {
                    airPlayConsumer.onVideoFormat((VideoStreamInfo) mediaStreamInfo.get());
                    session.getVideoServer().start(airPlayConsumer);
                    var setup = PropertyListUtil.prepareSetupVideoResponse(session.getVideoServer().getPort(),
                            ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort(), 0);
                    response.content().writeBytes(setup);
                }
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
                case AUDIO -> {
                    airPlayConsumer.onAudioSrcDisconnect();
                    session.getAudioServer().stop();
                    session.getAudioControlServer().stop();
                }
                case VIDEO -> {
                    airPlayConsumer.onVideoSrcDisconnect();
                    session.getVideoServer().stop();
                }
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
        log.info("Request content:\n{}", play.toXMLPropertyList());

        var clientProcName = play.get("clientProcName") != null
                ? play.get("clientProcName").toJavaObject(String.class)
                : "";
        var playlistUri = play.get("Content-Location") != null
                ? play.get("Content-Location").toJavaObject(String.class)
                : null;
        if (playlistUri != null && !playlistUri.isBlank()) {
            var session = resolveSession(request);
            var playlistUriLocal = playlistUriToLocal(playlistUri, playlistBaseUrl(ctx), session.getId());
            var remotePlaylistUri = playlistUri.split("\\?")[0];

            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            sendResponse(ctx, request, response);

            if (remotePlaylistUri.contains("master.m3u8")) {
                var hls = new HlsPlaylistState(remotePlaylistUri, playlistUriLocal);
                session.setHlsPlaylistState(hls);
                log.info("HLS play from [{}]: prefetching playlists via FCUP, localUri={}", clientProcName, playlistUriLocal);
                hlsFcupService.sendFcupRequest(session, remotePlaylistUri);
            } else {
                airPlayConsumer.onMediaPlaylist(playlistUriLocal);
            }
        } else {
            log.error("Client proc name [{}] has no Content-Location playlist", clientProcName);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED);
            sendResponse(ctx, request, response);
        }
    }

    private void handleSetProperty(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var decoder = new QueryStringDecoder(request.uri());
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.info("Request content:\n{}", play.toXMLPropertyList());

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleRate(ChannelHandlerContext ctx, FullHttpRequest request) {
        var decoder = new QueryStringDecoder(request.uri());
        var rate = (int) Double.parseDouble(decoder.parameters().get("value").get(0));

        if (rate == 0) {
            airPlayConsumer.onMediaPlaylistPause();
        } else {
            airPlayConsumer.onMediaPlaylistResume();
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handlePlaybackInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        var playbackInfo = PropertyListUtil.preparePlaybackInfoResponse(airPlayConsumer.playbackInfo());
        response.content().writeBytes(playbackInfo);
        sendResponse(ctx, request, response);
    }

    private void handleAction(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        NSDictionary action = parseActionPlist(request);
        log.info("Action request:\n{}", action.toXMLPropertyList());

        var type = action.get("type").toJavaObject(String.class);
        log.info("Action type: {}", type);
        if ("unhandledURLResponse".equals(type)) {
            handleUnhandledUrlResponse(ctx, request, action);
        } else if ("playlistRemove".equals(type)) {
            /*<plist version="1.0">
            <dict>
            	<key>type</key>
            	<string>playlistRemove</string>
            	<key>params</key>
            	<dict>
            		<key>item</key>
            		<dict>
            			<key>uuid</key>
            			<string>59F93E62-4E79-4A8F-A55A-D7DA65247AF1</string>
            		</dict>
            	</dict>
            </dict>
            </plist>*/
            airPlayConsumer.onMediaPlaylistRemove();
            hlsFcupService.cancelAllMasterPolls();
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleGetProperty(ChannelHandlerContext ctx, FullHttpRequest request) {
        // TODO get requested param and respond accordingly
        var decoder = new QueryStringDecoder(request.uri());
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private NSDictionary parseActionPlist(FullHttpRequest request) throws Exception {
        byte[] content = ByteBufUtil.getBytes(request.content());
        try {
            return (NSDictionary) BinaryPropertyListParser.parse(content);
        } catch (Exception binaryError) {
            log.debug("Action body is not binary plist, trying XML", binaryError);
            return (NSDictionary) PropertyListParser.parse(content);
        }
    }

    private void handleUnhandledUrlResponse(ChannelHandlerContext ctx, FullHttpRequest request, NSDictionary action) {
        var params = action.get("params") instanceof NSDictionary dictionary ? dictionary : null;
        if (params == null || params.get("FCUP_Response_URL") == null) {
            log.warn("FCUP response is missing URL: {}", action.toXMLPropertyList());
            return;
        }
        var fcupResponseURL = params.get("FCUP_Response_URL").toJavaObject(String.class);
        var session = resolveSession(request);
        if (params.get("FCUP_Response_Data") == null) {
            log.warn("FCUP response without data: url={}, session={}", fcupResponseURL, session.getId());
            var hls = session.getHlsPlaylistState();
            if (hls != null) {
                continueMediaPrefetch(session, hls);
            }
            return;
        }
        var fcupResponseBase64 = ((NSData) params.get("FCUP_Response_Data")).getBase64EncodedData();
        var fcupResponse = new String(Base64.getDecoder().decode(fcupResponseBase64), StandardCharsets.UTF_8);
        log.info("FCUP response: url={}, bytes={}, session={}", fcupResponseURL, fcupResponse.length(), session.getId());

        String body;
        try {
            if (fcupResponseURL.contains("master.m3u8")) {
                body = masterPlaylistToLocalUrls(fcupResponse, playlistBaseUrl(ctx), session.getId());
            } else if (fcupResponseURL.contains("mediadata.m3u8")) {
                body = mediaPlaylistBody(fcupResponse);
            } else {
                body = fcupResponse;
            }
        } catch (Exception e) {
            log.warn("Failed to rewrite playlist {}, forwarding raw FCUP body", fcupResponseURL, e);
            body = fcupResponse;
        }

        var hls = session.getHlsPlaylistState();
        if (hls != null) {
            try {
                if (fcupResponseURL.contains("master.m3u8")) {
                    if (hls.isPlaybackStarted()) {
                        hlsFcupService.onMasterRefreshDuringPlayback(session, body);
                    } else {
                        hls.storeMasterPlaylist(body, fcupResponse);
                        log.info("HLS master received: {} media playlists queued, state={}", hls.pendingMediaUriCount(), hls);
                        if (!hls.isPlaybackStarted()) {
                            hls.markPlaybackStarted();
                            log.info("HLS starting playback after master: {}", hls.getPlaylistUriLocal());
                            airPlayConsumer.onMediaPlaylist(hls.getPlaylistUriLocal());
                        }
                    }
                } else {
                    hls.putPlaylist(fcupResponseURL, body);
                }
            } catch (Exception e) {
                log.warn("Failed to update HLS state for {}", fcupResponseURL, e);
                hls.putPlaylist(fcupResponseURL, body);
            }
            airPlayConsumer.onMediaPlaylistContent(fcupResponseURL, body);

            replyPendingPlaylists(session, fcupResponseURL, body);

            continueMediaPrefetch(session, hls);
            return;
        }

        if (replyPendingPlaylists(session, fcupResponseURL, body)) {
            airPlayConsumer.onMediaPlaylistContent(fcupResponseURL, body);
        } else {
            log.warn("No pending GET /playlist for {}", fcupResponseURL);
        }
    }

    private void continueMediaPrefetch(Session session, HlsPlaylistState hls) {
        if (hls.hasMoreMediaUris()) {
            hlsFcupService.sendFcupRequest(session, hls.nextMediaUri());
        }
    }

    private boolean replyPendingPlaylists(Session session, String remoteUri, String body) {
        PlaylistRequest pending;
        boolean replied = false;
        while ((pending = session.pollPlaylistRequest(remoteUri)) != null) {
            replyPlaylist(pending, body);
            replied = true;
        }
        return replied;
    }

    private String mediaPlaylistBody(String fcupResponse) throws PlaylistParserException {
        var parser = new MediaPlaylistParser(ParsingMode.LENIENT);
        var mediaPlaylist = expandYoutubeCondensedUrls(parser.readPlaylist(fcupResponse));
        return parser.writePlaylistAsString(mediaPlaylist);
    }

    private MediaPlaylist expandYoutubeCondensedUrls(MediaPlaylist mediaPlaylist) {
        var condensedUrl = mediaPlaylist.comments().stream()
                .filter(comment -> comment.startsWith("YT-EXT-CONDENSED-URL:"))
                .map(comment -> comment.replace("YT-EXT-CONDENSED-URL:", ""))
                .flatMap(attributes -> Pattern.compile("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))").matcher(attributes).results())
                .collect(Collectors.toMap(matcher -> matcher.group(1), matcher -> matcher.group(2) != null ? matcher.group(2) : matcher.group(3)));
        if (condensedUrl.isEmpty()) {
            return mediaPlaylist;
        }
        var prefix = condensedUrl.get("PREFIX");
        var params = condensedUrl.get("PARAMS");
        var baseUri = condensedUrl.get("BASE-URI");
        if (prefix == null || params == null || baseUri == null) {
            return mediaPlaylist;
        }
        var paramNames = params.split(",");
        boolean matching = mediaPlaylist.mediaSegments().stream().allMatch(segment ->
                segment.uri().replaceFirst(Pattern.quote(prefix), "").split("/").length == paramNames.length);
        if (!matching) {
            log.warn("YouTube condensed URL param count mismatch, leaving playlist unchanged");
            return mediaPlaylist;
        }
        return MediaPlaylist.builder()
                .from(mediaPlaylist)
                .mediaSegments(mediaPlaylist.mediaSegments().stream()
                        .map(segment -> {
                            var paramValues = segment.uri().replaceFirst(Pattern.quote(prefix), "").split("/");
                            var paramResult = new StringBuilder();
                            for (int i = 0; i < paramNames.length; i++) {
                                paramResult.append("/").append(paramNames[i]).append("/").append(paramValues[i]);
                            }
                            return MediaSegment.builder().from(segment).uri(baseUri + paramResult).build();
                        })
                        .toList())
                .build();
    }

    private void replyPlaylist(PlaylistRequest pending, String body) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        response.content().writeCharSequence(body == null ? "" : body, StandardCharsets.UTF_8);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        publishControlExchange(pending, response);
        var future = pending.context().writeAndFlush(response);
        if (!pending.keepAlive()) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void handleGetPlaylist(ChannelHandlerContext ctx, FullHttpRequest request) {
        var playlistUriRemote = playlistPathToRemote(request.uri());
        var decoder = new QueryStringDecoder(request.uri());
        var session = sessionManager.getSession(decoder.parameters().get("session").get(0));

        var pending = new PlaylistRequest(
                ctx,
                request.protocolVersion().text(),
                request.method().name(),
                request.uri(),
                copyHeaders(request.headers()),
                ByteBufUtil.getBytes(request.content()),
                HttpUtil.isKeepAlive(request));

        var hls = session.getHlsPlaylistState();
        var cached = hls != null ? hls.getPlaylist(playlistUriRemote) : null;
        boolean isMediaPlaylist = playlistUriRemote.contains("mediadata.m3u8");
        boolean isMasterPlaylist = playlistUriRemote.contains("master.m3u8");
        boolean refreshPlaylist = isMediaPlaylist || (isMasterPlaylist && hls != null && hls.isPlaybackStarted());

        if (cached != null && !refreshPlaylist) {
            log.info("Serving cached playlist {}", playlistUriRemote);
            replyPlaylist(pending, cached);
            return;
        }

        session.enqueuePlaylistRequest(playlistUriRemote, pending);
        recordPendingPlaylistRequest(pending);

        if (refreshPlaylist) {
            log.info("GET /playlist {} requesting FCUP refresh", playlistUriRemote);
            hlsFcupService.sendFcupRequest(session, playlistUriRemote);
        } else if (hls == null) {
            log.info("GET /playlist {} without HLS prefetch, requesting FCUP", playlistUriRemote);
            hlsFcupService.sendFcupRequest(session, playlistUriRemote);
        } else {
            log.info("GET /playlist {} waiting for HLS prefetch ({})", playlistUriRemote, hls);
        }
    }

    private void recordPendingPlaylistRequest(PlaylistRequest pending) {
        var processing = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PROCESSING);
        publishControlExchange(pending, processing);
    }

    private String playlistUriToLocal(String playlistUri, String baseUrl, String sessionId) {
        return HlsUriRewrite.toLocalUri(playlistUri, baseUrl, sessionId);
    }

    private String playlistPathToRemote(String playlistPath) {
        var playlistUriLocal = "mlhls://localhost" + playlistPath.replace("/playlist", "");
        return playlistUriLocal.split("\\?")[0]; // remove query
    }

    private String playlistBaseUrl(ChannelHandlerContext ctx) {
        var port = ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort();
        return String.format("http://localhost:%s/playlist", port);
    }

    private String masterPlaylistToLocalUrls(String masterPlaylist, String baseUrl, String sessionId) {
        return HlsUriRewrite.rewritePlaylist(masterPlaylist, baseUrl, sessionId);
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
        publishControlExchange(request, response);
        var future = ctx.writeAndFlush(response);
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void publishControlExchange(FullHttpRequest request, FullHttpResponse response) {
        publishControlExchange(
                Optional.ofNullable(request.headers().get("Active-Remote"))
                        .orElseGet(() -> request.headers().get("X-Apple-Session-ID")),
                request.protocolVersion().text(),
                request.method().name(),
                request.uri(),
                copyHeaders(request.headers()),
                pendingRequestBody,
                response);
    }

    private void publishControlExchange(PlaylistRequest pending, FullHttpResponse response) {
        publishControlExchange(
                pending.requestHeaders().get("X-Apple-Session-ID"),
                pending.protocol(),
                pending.method(),
                pending.uri(),
                pending.requestHeaders(),
                pending.requestBody(),
                response);
    }

    private void publishControlExchange(String sessionId, String protocol, String method, String uri,
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

    private static Map<String, String> copyHeaders(HttpHeaders headers) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers) {
            copy.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return copy;
    }
}
