package com.github.serezhka.airplay.server.internal.handler.control;

import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.HlsUriRewrite;
import com.github.serezhka.airplay.server.internal.handler.session.PlaylistRequest;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP playlist / FCUP exchange paths used by YouTube-style HLS (GET /playlist +
 * unhandledURLResponse). Extracted from {@link ControlHandler} for readability.
 */
@Slf4j
@RequiredArgsConstructor
final class HlsPlaylistHttp {

    private final SessionManager sessionManager;
    private final HlsFcupService hlsFcupService;
    private final HlsSessionControl hlsSession;
    private final Playback airPlayConsumer;
    private final ControlResponses responses;

    void handleUnhandledUrlResponse(ChannelHandlerContext ctx, FullHttpRequest request, NSDictionary action) {
        var params = action.get("params") instanceof NSDictionary dictionary ? dictionary : null;
        if (params == null || params.get("FCUP_Response_URL") == null) {
            log.warn("FCUP response is missing URL: {}", action.toXMLPropertyList());
            return;
        }
        var fcupResponseURL = normalizeFcupUrl(params.get("FCUP_Response_URL").toJavaObject(String.class));
        var session = resolveSession(request);
        if (params.get("FCUP_Response_Data") == null) {
            log.warn("FCUP response without data: url={}, session={}", fcupResponseURL, session.getId());
            var hls = session.getHlsPlaylistState();
            if (hls != null && !hls.isWaitingForMasterChange()) {
                continueMediaPrefetch(session, hls);
            }
            return;
        }
        var fcupResponseBase64 = ((NSData) params.get("FCUP_Response_Data")).getBase64EncodedData();
        var fcupResponse = new String(Base64.getDecoder().decode(fcupResponseBase64), StandardCharsets.UTF_8);
        log.debug("FCUP response: url={}, bytes={}, session={}", fcupResponseURL, fcupResponse.length(), session.getId());

        String body;
        List<String> remoteMediaUris = null;
        try {
            if (fcupResponseURL.contains("master.m3u8")) {
                var rewritten = rewriteMasterPlaylist(fcupResponse, playlistBaseUrl(ctx), session.getId());
                body = rewritten.localBody();
                remoteMediaUris = rewritten.remoteMediaUris();
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
                        hlsFcupService.onMasterRefreshDuringPlayback(session, body, fcupResponse, remoteMediaUris);
                    } else {
                        hls.storeMasterPlaylist(body, remoteMediaUris);
                        hls.recordRawMasterIfChanged(fcupResponse);
                        log.debug("HLS master received: {} media playlists queued ({} video), state={}",
                                hls.pendingMediaUriCount(), hls.videoMediaUriCount(), hls);
                        if (!hls.isPlaybackStarted()) {
                            hlsSession.schedulePlaybackStartFallback(session, hls);
                        }
                    }
                } else {
                    hls.putPlaylist(fcupResponseURL, body);
                    hlsSession.maybeStartHlsPlayback(session, hls, "mediadata ready");
                }
            } catch (Exception e) {
                log.warn("Failed to update HLS state for {}", fcupResponseURL, e);
                hls.putPlaylist(fcupResponseURL, body);
                hlsSession.maybeStartHlsPlayback(session, hls, "mediadata ready after error");
            }
            airPlayConsumer.onPlaylistContent(fcupResponseURL, body);

            replyPendingPlaylists(session, fcupResponseURL, body);

            if (hls.isPostEosMediaRefreshing()) {
                if (fcupResponseURL.contains("mediadata.m3u8")) {
                    if (hls.hasMoreMediaUris()) {
                        hlsFcupService.sendFcupRequest(session, hls.nextMediaUri());
                    } else {
                        hlsFcupService.onPostEosMediaRefreshComplete(session);
                    }
                }
            } else if (!hls.isWaitingForMasterChange()) {
                continueMediaPrefetch(session, hls);
            }
            return;
        }

        if (replyPendingPlaylists(session, fcupResponseURL, body)) {
            airPlayConsumer.onPlaylistContent(fcupResponseURL, body);
        } else {
            log.warn("No pending GET /playlist for {}", fcupResponseURL);
        }
    }

    void handleGetPlaylist(ChannelHandlerContext ctx, FullHttpRequest request) {
        var playlistUriRemote = playlistPathToRemote(request.uri());
        var decoder = new QueryStringDecoder(request.uri());
        var session = sessionManager.getSession(decoder.parameters().get("session").get(0));

        var pending = new PlaylistRequest(
                ctx,
                request.protocolVersion().text(),
                request.method().name(),
                request.uri(),
                ControlResponses.copyHeaders(request.headers()),
                ByteBufUtil.getBytes(request.content()),
                HttpUtil.isKeepAlive(request));

        var hls = session.getHlsPlaylistState();
        var cached = hls != null ? hls.getPlaylist(playlistUriRemote) : null;
        boolean isMasterPlaylist = playlistUriRemote.contains("master.m3u8");
        boolean refreshPlaylist = isMasterPlaylist && hls != null && hls.isPlaybackStarted() && cached == null;

        if (cached != null) {
            log.debug("Serving cached playlist {}", playlistUriRemote);
            replyPlaylist(pending, cached);
            if (hls != null && hls.isPlaybackStarted()
                    && hls.shouldRefreshPlaylist(playlistUriRemote, TimeUnit.MILLISECONDS.toNanos(1500))) {
                hlsFcupService.sendFcupRequest(session, playlistUriRemote);
            }
            return;
        }

        session.enqueuePlaylistRequest(playlistUriRemote, pending);
        recordPendingPlaylistRequest(pending);

        if (cached == null && (refreshPlaylist || hls == null || !hls.hasMoreMediaUris())) {
            log.debug("GET /playlist {} requesting FCUP", playlistUriRemote);
            hlsFcupService.sendFcupRequest(session, playlistUriRemote);
        } else {
            log.debug("GET /playlist {} waiting for HLS prefetch ({})", playlistUriRemote, hls);
        }
    }

    String playlistBaseUrl(ChannelHandlerContext ctx) {
        int port = 7000;
        if (ctx.channel().parent() instanceof ServerSocketChannel server) {
            port = server.localAddress().getPort();
        }
        // IPv4 loopback: on Windows "localhost" often resolves to ::1 while the
        // control server listens on IPv4, so the local HLS consumer cannot fetch.
        return String.format("http://127.0.0.1:%s/playlist", port);
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
        var mediaPlaylist = HlsCondensedUrls.expand(parser.readPlaylist(fcupResponse));
        return parser.writePlaylistAsString(mediaPlaylist);
    }

    private void replyPlaylist(PlaylistRequest pending, String body) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/vnd.apple.mpegurl");
        response.content().writeCharSequence(body == null ? "" : body, StandardCharsets.UTF_8);
        HttpUtil.setContentLength(response, response.content().readableBytes());
        responses.publishControlExchange(pending, response);
        var future = pending.context().writeAndFlush(response);
        if (!pending.keepAlive()) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void recordPendingPlaylistRequest(PlaylistRequest pending) {
        var processing = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PROCESSING);
        responses.publishControlExchange(pending, processing);
    }

    private String playlistPathToRemote(String playlistPath) {
        var playlistUriLocal = "mlhls://localhost" + playlistPath.replace("/playlist", "");
        return playlistUriLocal.split("\\?")[0];
    }

    private record MasterRewrite(String localBody, List<String> remoteMediaUris) {
    }

    private MasterRewrite rewriteMasterPlaylist(String masterPlaylist, String baseUrl, String sessionId) {
        String filteredRemote = HlsUriRewrite.preferAvcVariants(masterPlaylist);
        if (!filteredRemote.equals(masterPlaylist)) {
            log.debug("Filtered HLS master to AVC variants and the default audio track");
        }
        List<String> remoteMediaUris;
        try {
            remoteMediaUris = HlsUriRewrite.extractMediaUris(filteredRemote);
        } catch (PlaylistParserException e) {
            log.warn("Failed to extract media URIs from filtered master", e);
            remoteMediaUris = List.of();
        }
        String localBody = HlsUriRewrite.rewritePlaylist(filteredRemote, baseUrl, sessionId);
        return new MasterRewrite(localBody, remoteMediaUris);
    }

    private static String normalizeFcupUrl(String url) {
        if (url == null) {
            return null;
        }
        String bare = url.split("\\?")[0];
        int playlistIdx = bare.indexOf("/playlist/");
        if (bare.startsWith("http") && playlistIdx >= 0) {
            return "mlhls://localhost" + bare.substring(playlistIdx + "/playlist".length());
        }
        return bare;
    }

    private Session resolveSession(FullHttpRequest request) {
        var sessionId = java.util.Optional.ofNullable(request.headers().get("Active-Remote"))
                .orElseGet(() -> request.headers().get("X-Apple-Session-ID"));
        return sessionManager.getSession(sessionId);
    }
}
