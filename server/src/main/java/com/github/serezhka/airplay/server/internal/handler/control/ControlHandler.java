package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.AirPlayVolume;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.rtsp.*;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
public class ControlHandler extends ChannelInboundHandlerAdapter {

    private final SessionManager sessionManager;
    private final HlsFcupService hlsFcupService;
    private final HlsSessionControl hlsSession;
    private final HlsPlaylistHttp hlsPlaylistHttp;
    private final ControlResponses responses;
    private final AirPlayConfig airPlayConfig;
    private final Playback airPlayConsumer;

    public ControlHandler(SessionManager sessionManager,
                          HlsFcupService hlsFcupService,
                          AirPlayConfig airPlayConfig,
                          Playback airPlayConsumer) {
        this.sessionManager = sessionManager;
        this.hlsFcupService = hlsFcupService;
        this.hlsSession = new HlsSessionControl(sessionManager, hlsFcupService, airPlayConsumer);
        this.airPlayConfig = airPlayConfig;
        this.airPlayConsumer = airPlayConsumer;
        this.responses = new ControlResponses(airPlayConsumer, () -> pendingRequestBody);
        this.hlsPlaylistHttp = new HlsPlaylistHttp(
                sessionManager, hlsFcupService, hlsSession, airPlayConsumer, responses);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        for (Session session : sessionManager.allSessions()) {
            var reverse = session.getReverseContexts();
            ChannelHandlerContext eventCtx = reverse.get("event");
            if (eventCtx == ctx) {
                log.info("Reverse event channel closed, stopping HLS session {}", session.getId());
                hlsSession.stopHlsPlayback(session);
            }
            reverse.entrySet().removeIf(entry -> entry.getValue() == ctx);
        }
        super.channelInactive(ctx);
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
                hlsFcupService.onReverseEventResponse(ctx);
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
                } else if (RtspMethods.OPTIONS.equals(request.method())) {
                    handleRtspOptions(ctx, request);
                } else if (RtspMethods.ANNOUNCE.equals(request.method())) {
                    handleRtspAnnounce(ctx, request);
                } else {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = responses.createRtspResponse(request);
                    response.setStatus(HttpResponseStatus.NOT_FOUND);
                    responses.sendResponse(ctx, request, response);
                }
            } else if (HttpVersion.HTTP_1_1.equals(request.protocolVersion())) {
                var decoder = new QueryStringDecoder(request.uri());
                if (HttpMethod.GET.equals(request.method()) && decoder.path().equals("/server-info")) {
                    handleGetServerInfo(ctx, request);
                } else if (HttpMethod.POST.equals(request.method())
                        && (decoder.path().equals("/fp-setup") || decoder.path().equals("/fp-setup2"))) {
                    // YouTube posts FairPlay *version 1* here; we only implement v3 on RTSP.
                    // Acknowledge so the client is not 404'd / reset — do not run fairPlaySetup.
                    handleHttpFairPlaySetupAck(ctx, request);
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
                    handleScrub(ctx, request);
                } else if (HttpMethod.POST.equals(request.method())
                        && (decoder.path().equals("/stop") || decoder.path().equals("/stop2"))) {
                    handleStop(ctx, request);
                } else if (HttpMethod.GET.equals(request.method()) && decoder.path().startsWith("/playlist")) {
                    hlsPlaylistHttp.handleGetPlaylist(ctx, request);
                } else {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), request.method(), request.uri());
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    responses.sendResponse(ctx, request, response);
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
        var info = PropertyListUtil.prepareInfoResponse(airPlayConfig, sessionManager.pairingPublicKey());
        var response = responses.createRtspResponse(request);
        response.content().writeBytes(info);
        responses.sendResponse(ctx, request, response);
    }

    private void handlePairSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = responses.createRtspResponse(request);
        session.getAirPlay().pairSetup(new ByteBufOutputStream(response.content()));
        responses.sendResponse(ctx, request, response);
    }

    private void handlePairVerify(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = responses.createRtspResponse(request);
        session.getAirPlay().pairVerify(new ByteBufInputStream(request.content()),
                new ByteBufOutputStream(response.content()));
        responses.sendResponse(ctx, request, response);
    }

    private void handleFairPlaySetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = responses.createRtspResponse(request);
        session.getAirPlay().fairPlaySetup(new ByteBufInputStream(request.content()),
                new ByteBufOutputStream(response.content()));
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspSetup(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var session = resolveSession(request);
        var response = responses.createRtspResponse(request);
        var mediaStreamInfo = session.getAirPlay().rtspSetup(new ByteBufInputStream(request.content()));
        if (mediaStreamInfo.isPresent()) {
            switch (mediaStreamInfo.get().getStreamType()) {
                case AUDIO -> {
                    airPlayConsumer.onAudioFormat((AudioStreamInfo) mediaStreamInfo.get());
                    session.getAudioServer().start(airPlayConsumer);
                    session.getAudioControlServer().start();
                    log.info("SESSION mode=AUDIO session={} audioPort={} controlPort={}",
                            session.getId(),
                            session.getAudioServer().getPort(),
                            session.getAudioControlServer().getPort());
                    var setup = PropertyListUtil.prepareSetupAudioResponse(session.getAudioServer().getPort(),
                            session.getAudioControlServer().getPort());
                    response.content().writeBytes(setup);
                }
                case VIDEO -> {
                    airPlayConsumer.onVideoFormat((VideoStreamInfo) mediaStreamInfo.get());
                    session.getVideoServer().start(airPlayConsumer);
                    log.info("SESSION mode=MIRROR session={} videoPort={}",
                            session.getId(), session.getVideoServer().getPort());
                    var setup = PropertyListUtil.prepareSetupVideoResponse(session.getVideoServer().getPort(),
                            ((ServerSocketChannel) ctx.channel().parent()).localAddress().getPort(), 0);
                    response.content().writeBytes(setup);
                }
            }
        }
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspFeedback(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = responses.createRtspResponse(request);
        responses.sendResponse(ctx, request, response);
    }

    /**
     * First probe when the sender treats the receiver as a speaker ({@code OPTIONS *}).
     * {@code Public} lists the methods this connection accepts.
     */
    private void handleRtspOptions(ChannelHandlerContext ctx, FullHttpRequest request) {
        log.debug("RTSP OPTIONS {}", request.uri());
        var response = responses.createRtspResponse(request);
        response.headers().add(RtspHeaderNames.PUBLIC,
                "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET");
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        responses.sendResponse(ctx, request, response);
    }

    /** Speaker setup follows OPTIONS with an SDP body. Accept it and keep the session open. */
    private void handleRtspAnnounce(ChannelHandlerContext ctx, FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.US_ASCII).replaceAll("\\s+", " ").trim();
        log.debug("RTSP ANNOUNCE {}", body.length() > 400 ? body.substring(0, 400) : body);
        var response = responses.createRtspResponse(request);
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspGetParameter(ChannelHandlerContext ctx, FullHttpRequest request) {
        byte[] content = AirPlayVolume.formatRtspParameter(airPlayConsumer.volume()).getBytes(StandardCharsets.US_ASCII);
        var response = responses.createRtspResponse(request);
        response.content().writeBytes(content);
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspRecord(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = responses.createRtspResponse(request);
        response.headers().add("Audio-Latency", "11025");
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspSetParameter(ChannelHandlerContext ctx, FullHttpRequest request) {
        var contentType = Optional.ofNullable(request.headers().get(HttpHeaderNames.CONTENT_TYPE)).orElse("");
        if (contentType.contains("text/parameters") && request.content().isReadable()) {
            String body = request.content().toString(StandardCharsets.US_ASCII);
            for (String line : body.split("\r?\n")) {
                if (line.regionMatches(true, 0, "volume:", 0, 7)) {
                    try {
                        double db = Double.parseDouble(line.substring(7).trim());
                        double linear = AirPlayVolume.fromDecibels(db);
                        airPlayConsumer.onVolume(linear);
                        log.debug("RTSP volume {} dB -> linear {}", db, linear);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid RTSP volume line: {}", line);
                    }
                }
            }
        }
        var response = responses.createRtspResponse(request);
        response.headers().add("Audio-Jack-Status", "connected; type=analog");
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspFlush(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = responses.createRtspResponse(request);
        responses.sendResponse(ctx, request, response);
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
            // Full session teardown only — stream-specific TEARDOWN must not stop HLS.
            if (session.getHlsPlaylistState() != null) {
                log.info("RTSP TEARDOWN (full): stopping HLS session {}", session.getId());
                hlsSession.stopHlsPlayback(session);
            }
        }
        var response = responses.createRtspResponse(request);
        responses.sendResponse(ctx, request, response);
    }

    private void handleRtspAudioMode(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = responses.createRtspResponse(request);
        responses.sendResponse(ctx, request, response);
    }

    private void handleGetServerInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var serverInfo = PropertyListUtil.prepareServerInfoResponse();
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        response.content().writeBytes(serverInfo);
        responses.sendResponse(ctx, request, response);
    }

    /**
     * HTTP {@code /fp-setup} and {@code /fp-setup2}: return 200 with empty body.
     * Must not 404 (phone aborts YouTube HLS) and must not run RTSP FairPlay v3
     * against the v1 payload YouTube sends (logs "version 1 is not supported" and resets).
     */
    private void handleHttpFairPlaySetupAck(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        responses.sendResponse(ctx, request, response);
    }

    private void handleReverse(ChannelHandlerContext ctx, FullHttpRequest request) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
        response.headers().add(HttpHeaderNames.UPGRADE, request.headers().get(HttpHeaderNames.UPGRADE));
        response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
        responses.sendResponse(ctx, request, response);

        var purpose = request.headers().get("X-Apple-Purpose");
        ctx.pipeline().remove(RtspDecoder.class);
        ctx.pipeline().remove(RtspEncoder.class);
        ctx.pipeline().addFirst(new HttpClientCodec());
        var session = resolveSession(request);
        session.getReverseContexts().put(purpose, ctx);
    }

    private void handlePlay(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        if (log.isDebugEnabled()) {
            log.debug("POST /play body:\n{}", play.toXMLPropertyList());
        }

        if (play.get("volume") != null) {
            try {
                double linear = AirPlayVolume.clampLinear(play.get("volume").toJavaObject(Double.class));
                airPlayConsumer.onVolume(linear);
            } catch (Exception e) {
                log.debug("Ignoring /play volume", e);
            }
        }

        Double startPositionSeconds = null;
        if (play.get("Start-Position-Seconds") != null) {
            try {
                startPositionSeconds = play.get("Start-Position-Seconds").toJavaObject(Double.class);
            } catch (Exception e) {
                log.debug("Ignoring Start-Position-Seconds", e);
            }
        }

        var clientProcName = play.get("clientProcName") != null
                ? play.get("clientProcName").toJavaObject(String.class)
                : "";
        var playlistUri = play.get("Content-Location") != null
                ? play.get("Content-Location").toJavaObject(String.class)
                : null;
        if (playlistUri != null && !playlistUri.isBlank()) {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            responses.sendResponse(ctx, request, response);
            String itemUuid = play.get("uuid") != null ? play.get("uuid").toJavaObject(String.class) : null;
            hlsSession.startMediaPlaylist(
                    resolveSession(request),
                    playlistUri,
                    hlsPlaylistHttp.playlistBaseUrl(ctx),
                    clientProcName,
                    startPositionSeconds,
                    itemUuid);
        } else {
            log.error("Client proc name [{}] has no Content-Location playlist", clientProcName);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED);
            responses.sendResponse(ctx, request, response);
        }
    }

    private void handleSetProperty(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        var decoder = new QueryStringDecoder(request.uri());
        var path = decoder.path();
        var params = decoder.parameters();
        var play = (NSDictionary) BinaryPropertyListParser.parse(new ByteBufInputStream(request.content()));
        boolean isActionAtItemEnd = params.containsKey("actionAtItemEnd") || request.uri().contains("actionAtItemEnd");
        boolean isSelectedMedia = params.containsKey("selectedMediaArray") || request.uri().contains("selectedMediaArray");
        if (isActionAtItemEnd || isSelectedMedia) {
            log.debug("SET_PROPERTY {}: {}", request.uri(), play.toXMLPropertyList().replaceAll("\\s+", " ").trim());
        } else {
            log.debug("SET_PARAMETER path={}, params={}", path, params);
            if (log.isDebugEnabled()) {
                log.debug("SET_PARAMETER body:\n{}", play.toXMLPropertyList());
            }
        }
        if (isActionAtItemEnd && play.get("value") != null) {
            var session = resolveSession(request);
            var hls = session.getHlsPlaylistState();
            if (hls != null) {
                int action = ((Number) play.get("value").toJavaObject()).intValue();
                hls.setActionAtItemEnd(action);
                log.debug("actionAtItemEnd={} session={}", action, session.getId());
            }
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        responses.sendResponse(ctx, request, response);
    }

    private void handleRate(ChannelHandlerContext ctx, FullHttpRequest request) {
        var decoder = new QueryStringDecoder(request.uri());
        double value = Double.parseDouble(decoder.parameters().get("value").get(0));
        var session = resolveSession(request);
        var hls = session.getHlsPlaylistState();
        log.debug("POST /rate value={}", value);

        // After VOD ad EOS we wait for the next /play; the sender still probes rate 0/1.
        // Answering with paused→playing makes it treat the ad as still active.
        if (hls != null && hls.isWaitingForMasterChange()) {
            log.debug("Ignoring rate={} while waiting for next HLS item session={}", value, session.getId());
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            responses.sendResponse(ctx, request, response);
            return;
        }

        if (value == 0) {
            if (hls != null && hls.isScrubGesture()) {
                // Tail of the scrub, before the seek-picture callback. The sender applies
                // this rate=0 locally; playing has to follow it.
                log.debug("rate=0 during scrub gesture session={}", session.getId());
                hlsSession.reannounceAfterScrubRateZero(session);
            } else if (hls != null) {
                hls.armRateZero();
                // Freeze the picture now; formal paused (playbackRate=0 + reverse event) still
                // waits for the scrub window so a following /scrub is not blocked by a pause UI.
                airPlayConsumer.onPause();
                log.debug("rate=0 soft-pause; formal pause waits for scrub window session={}",
                        session.getId());
            }
        } else {
            boolean softPaused = hls != null && hls.isRateZeroPending();
            boolean wasPaused = hls != null && (hls.getPlaybackRate() <= 0 || softPaused);
            if (hls != null) {
                hls.clearRateZero();
                hls.endScrubGesture();
                hls.setPlaybackRate(1);
            }
            // Only resume when leaving a real/soft pause — rate=1 while already playing must not re-seek.
            if (wasPaused) {
                airPlayConsumer.onResume();
            }
            // rate=1 can arrive before the first frame; wait for the picture callback.
            if (hls == null || !hls.isAwaitingPresentation()) {
                hlsFcupService.sendPlaybackStateEvent(session, "playing");
            } else {
                log.debug("Holding playing until the picture session={}", session.getId());
            }
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        responses.sendResponse(ctx, request, response);
    }

    private void handleScrub(ChannelHandlerContext ctx, FullHttpRequest request) {
        var decoder = new QueryStringDecoder(request.uri());
        var positions = decoder.parameters().get("position");
        if (positions != null && !positions.isEmpty()) {
            try {
                double position = Double.parseDouble(positions.get(0));
                log.debug("POST /scrub position={}", position);
                var session = resolveSession(request);
                var hls = session.getHlsPlaylistState();
                if (hls != null) {
                    hls.clearRateZero();
                    hls.beginScrubGesture();
                    hls.setPlaybackRate(1);
                }
                if (hls != null && !hls.isPlaybackStarted()) {
                    hls.setPendingSeekSeconds(position);
                    log.debug("Deferring /scrub to pending seek until HLS starts");
                } else {
                    // onSeek plays; clears a soft-pause from the leading rate=0.
                    airPlayConsumer.onSeek(position);
                    // loading then playing (clock under params) unsticks the pause UI.
                    hlsFcupService.sendPlaybackStateEvent(session, "loading");
                    hlsFcupService.sendPlaybackStateEvent(session, "playing");
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid /scrub position: {}", positions.get(0));
            }
        } else {
            log.warn("POST /scrub without position: {}", request.uri());
        }
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        responses.sendResponse(ctx, request, response);
    }

    private void handleStop(ChannelHandlerContext ctx, FullHttpRequest request) {
        var path = new QueryStringDecoder(request.uri()).path();
        var session = resolveSession(request);
        log.info("SESSION mode=STOP path={} session={}", path, session.getId());
        hlsSession.stopHlsPlayback(session);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        responses.sendResponse(ctx, request, response);
    }

    private void handlePlaybackInfo(ChannelHandlerContext ctx, FullHttpRequest request) {
        var session = resolveSession(request);
        hlsSession.commitPauseOnPlaybackInfo(session);
        var playbackInfo = HlsPlaybackInfo.resolve(session.getHlsPlaylistState(), airPlayConsumer.info());
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        response.content().writeBytes(PropertyListUtil.preparePlaybackInfoResponse(playbackInfo));
        responses.sendResponse(ctx, request, response);
    }

    private void handleAction(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        NSDictionary action = parseActionPlist(request);
        if (log.isDebugEnabled()) {
            log.debug("POST /action body:\n{}", action.toXMLPropertyList());
        }

        var type = action.get("type").toJavaObject(String.class);
        log.debug("Action type: {}", type);
        if ("unhandledURLResponse".equals(type)) {
            hlsPlaylistHttp.handleUnhandledUrlResponse(ctx, request, action);
        } else if ("playlistRemove".equals(type)) {
            hlsSession.stopHlsPlayback(resolveSession(request));
        } else if ("playlistInsert".equals(type)) {
            handlePlaylistInsert(ctx, request, action);
        }

        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        responses.sendResponse(ctx, request, response);
    }

    private void handlePlaylistInsert(ChannelHandlerContext ctx, FullHttpRequest request, NSDictionary action) {
        var params = action.get("params") instanceof NSDictionary dictionary ? dictionary : null;
        var item = params != null && params.get("item") instanceof NSDictionary dictionary ? dictionary : null;
        if (item == null || item.get("Content-Location") == null) {
            log.warn("playlistInsert without Content-Location: {}", action.toXMLPropertyList());
            return;
        }
        var playlistUri = item.get("Content-Location").toJavaObject(String.class);
        var clientProcName = item.get("clientProcName") != null
                ? item.get("clientProcName").toJavaObject(String.class)
                : "";
        String itemUuid = item.get("uuid") != null ? item.get("uuid").toJavaObject(String.class) : null;
        log.debug("playlistInsert Content-Location={} from [{}]", playlistUri, clientProcName);
        hlsSession.startMediaPlaylist(
                resolveSession(request), playlistUri, hlsPlaylistHttp.playlistBaseUrl(ctx), clientProcName, null, itemUuid);
    }

    private void handleGetProperty(ChannelHandlerContext ctx, FullHttpRequest request) {
        var decoder = new QueryStringDecoder(request.uri());
        // Senders probe playbackAccessLog / playbackErrorLog after VOD EOS (often before
        // playlistRemove). Log the property name; body stays empty until we have a verified
        // AccessLog template.
        log.debug("POST /getProperty {}", decoder.parameters().keySet());
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        byte[] body = PropertyListUtil.prepareEmptyPropertyResponse();
        response.content().writeBytes(body);
        HttpUtil.setContentLength(response, body.length);
        responses.sendResponse(ctx, request, response);
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


}
