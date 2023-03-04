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
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class ControlHandler extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;
    private final AirPlayConfig airPlayConfig;
    private final AirPlayConsumer airPlayConsumer;

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest request) {

            // TODO Split into several handlers or servers by protocol (airplay airtunes ..)

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
                    handleRstpAudioMode(ctx, request);
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
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    sendResponse(ctx, request, response);
                }
            } else {
                log.error("Unknown control request protocol: {}", request.protocolVersion());
            }
        } else {
            log.error("Unknown control message type: {}", msg);
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
        response.headers().add("Apple-Jack-Status", "connected; type=analog");
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

    private void handleRstpAudioMode(ChannelHandlerContext ctx, FullHttpRequest request) {
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
        ctx.pipeline().remove(HttpObjectAggregator.class);
        ctx.pipeline().addFirst(new HttpClientCodec());
        var session = resolveSession(request);
        session.getReverseContexts().put(purpose, ctx);
    }

    private void handlePlay(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.info("Request content:\n{}", play.toXMLPropertyList());

        var session = resolveSession(request);
        var playlistUri = play.get("Content-Location").toJavaObject(String.class);
        // sendEventRequest(session, playlistUri); // mlhls://localhost/master.m3u8
        var playlistUriLocal = playlistUriToLocal(playlistUri, playlistBaseUrl(ctx), session.getId());

        airPlayConsumer.onMediaPlaylist(playlistUriLocal);

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
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
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());

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

    // TODO refactor
    private void handleAction(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var action = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        log.info("Request content:\n{}", action.toXMLPropertyList());

        var params = (NSDictionary) action.get("params");
        var fcupResponseURL = params.get("FCUP_Response_URL").toJavaObject(String.class);
        var fcupResponseBase64 = ((NSData) (params.get("FCUP_Response_Data"))).getBase64EncodedData();
        var fcupResponse = new String(Base64.getDecoder().decode(fcupResponseBase64));

        // log.info("\n{}", fcupResponse);

        var session = resolveSession(request);

        if (session.getPlaylistRequestContexts().containsKey(fcupResponseURL)) {
            if (fcupResponseURL.contains("master.m3u8")) {
                var context = session.getPlaylistRequestContexts().get(fcupResponseURL);
                var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                response.content().writeCharSequence(masterPlaylistToLocalUrls(fcupResponse, playlistBaseUrl(ctx), session.getId()), StandardCharsets.UTF_8);
                HttpUtil.setContentLength(response, response.content().readableBytes());
                context.writeAndFlush(response);
                session.getPlaylistRequestContexts().remove(fcupResponseURL);
            } else {
                var parser = new MediaPlaylistParser(ParsingMode.LENIENT);
                var mediaPlaylist = parser.readPlaylist(fcupResponse);

                var condensedUrl = mediaPlaylist.comments().stream()
                        .filter(comment -> comment.startsWith("YT-EXT-CONDENSED-URL:"))
                        .map(comment -> comment.replace("YT-EXT-CONDENSED-URL:", ""))
                        .flatMap(attributes -> Pattern.compile("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))").matcher(attributes).results())
                        .collect(Collectors.toMap(matcher -> matcher.group(1), matcher -> matcher.group(2) != null ? matcher.group(2) : matcher.group(3)));

                if (condensedUrl.isEmpty()) {
                    log.warn("CONDENSED URL IS EMPTY");
                } else {
                    mediaPlaylist = MediaPlaylist.builder()
                            .from(mediaPlaylist)
                            .mediaSegments(mediaPlaylist.mediaSegments().stream()
                                    .map(segment -> {
                                        var prefix = condensedUrl.get("PREFIX");
                                        var paramNames = condensedUrl.get("PARAMS").split(",");
                                        var paramValues = segment.uri().replaceFirst(prefix, "").split("/");
                                        var paramResult = new StringBuilder();
                                        for (int i = 0; i < paramNames.length; i++) {
                                            paramResult.append("/").append(paramNames[i]).append("/").append(paramValues[i]);
                                        }
                                        return MediaSegment.builder().from(segment).uri(condensedUrl.get("BASE-URI") + paramResult).build();
                                    })
                                    .toList())
                            .build();

                    var context = session.getPlaylistRequestContexts().get(fcupResponseURL);
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                    response.content().writeCharSequence(parser.writePlaylistAsString(mediaPlaylist), StandardCharsets.UTF_8);
                    HttpUtil.setContentLength(response, response.content().readableBytes());
                    context.writeAndFlush(response);
                    session.getPlaylistRequestContexts().remove(fcupResponseURL);
                }
            }
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);

        /*if (fcupResponseURL.contains("master.m3u8")) {
            var session = resolveSession(request);
            var parser = new MasterPlaylistParser(ParsingMode.LENIENT);
            var masterPlaylist = parser.readPlaylist(fcupResponse);

            masterPlaylist.variants().stream()
                    .filter(variant -> variant.resolution().isPresent() &&
                            variant.resolution().get().equals(Resolution.of(1920, 1080)))
                    .findFirst()
                    .map(Variant::uri)
                    .ifPresent(listUri -> sendEventRequest(session, listUri));
        } else {
            var parser = new MediaPlaylistParser(ParsingMode.LENIENT);
            var mediaPlaylist = parser.readPlaylist(fcupResponse);

            var condensedUrl = mediaPlaylist.comments().stream()
                    .filter(comment -> comment.startsWith("YT-EXT-CONDENSED-URL:"))
                    .map(comment -> comment.replace("YT-EXT-CONDENSED-URL:", ""))
                    .flatMap(attributes -> Pattern.compile("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))").matcher(attributes).results())
                    .collect(Collectors.toMap(matcher -> matcher.group(1), matcher -> matcher.group(2) != null ? matcher.group(2) : matcher.group(3)));

            mediaPlaylist = MediaPlaylist.builder()
                    .from(mediaPlaylist)
                    .mediaSegments(mediaPlaylist.mediaSegments().stream()
                            .map(segment -> {
                                var prefix = condensedUrl.get("PREFIX");
                                var paramNames = condensedUrl.get("PARAMS").split(",");
                                var paramValues = segment.uri().replaceFirst(prefix, "").split("/");
                                var paramResult = new StringBuilder();
                                for (int i = 0; i < paramNames.length; i++) {
                                    paramResult.append("/").append(paramNames[i]).append("/").append(paramValues[i]);
                                }
                                return MediaSegment.builder().from(segment).uri(condensedUrl.get("BASE-URI") + paramResult).build();
                            })
                            .toList())
                    .build();

            Path path = Path.of("media.m3u8");
            Files.writeString(path, parser.writePlaylistAsString(mediaPlaylist));
            airPlayConsumer.onMediaPlaylist(path);
        }*/
    }

    private void handleGetProperty(ChannelHandlerContext ctx, FullHttpRequest request) {
        // TODO get requested param and respond accordingly
        var decoder = new QueryStringDecoder(request.uri());
        log.info("Path: {}, Query params: {}", decoder.path(), decoder.parameters());
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        sendResponse(ctx, request, response);
    }

    private void handleGetPlaylist(ChannelHandlerContext ctx, FullHttpRequest request) {
        log.warn("Playlist request: {}", request.uri());
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

    private String masterPlaylistToLocalUrls(String masterPlaylist, String baseUrl, String sessionId) throws PlaylistParserException {
        var parser = new MasterPlaylistParser();
        var playlist = parser.readPlaylist(masterPlaylist);

        playlist = MasterPlaylist.builder().from(playlist)
                .alternativeRenditions(playlist.alternativeRenditions().stream()
                        .map(rendition -> AlternativeRendition.builder().from(rendition)
                                .uri(playlistUriToLocal(rendition.uri().get(), baseUrl, sessionId)).build()).toList())
                .variants(playlist.variants().stream()
                        .filter(variant -> variant.codecs().stream().noneMatch(codec -> codec.startsWith("vp"))) // filter out variants with vp codecs
                        .map(variant -> Variant.builder().from(variant)
                                .uri(playlistUriToLocal(variant.uri(), baseUrl, sessionId)).build()).toList())
                .build();

        return parser.writePlaylistAsString(playlist);
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
