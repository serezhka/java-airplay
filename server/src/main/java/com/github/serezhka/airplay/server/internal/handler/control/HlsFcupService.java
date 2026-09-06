package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HlsFcupService {

    private static final long MASTER_POLL_INTERVAL_MS = 2000;

    private final SessionManager sessionManager;
    private final AirPlayConsumer airPlayConsumer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "airplay-hls-master-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledFuture<?>> masterPollTasks = new ConcurrentHashMap<>();

    public HlsFcupService(SessionManager sessionManager, AirPlayConsumer airPlayConsumer) {
        this.sessionManager = sessionManager;
        this.airPlayConsumer = airPlayConsumer;
    }

    public void refreshActivePlaylists() {
        for (Session session : sessionManager.allSessions()) {
            var hls = session.getHlsPlaylistState();
            if (hls == null || !hls.isPlaybackStarted()) {
                continue;
            }
            log.info("HLS segment ended, polling for master change session {}", session.getId());
            hls.setWaitingForMasterChange(true);
            hls.invalidatePlaylists();
            requestMasterRefresh(session);
        }
    }

    public void onMasterRefreshDuringPlayback(Session session, String rewrittenBody) {
        var hls = session.getHlsPlaylistState();
        if (hls == null || !hls.isPlaybackStarted()) {
            return;
        }
        boolean changed = hls.recordMasterIfChanged(rewrittenBody);
        if (changed) {
            log.info("HLS master changed, restarting playback session {}", session.getId());
            cancelMasterPoll(session.getId());
            hls.setWaitingForMasterChange(false);
            airPlayConsumer.onMediaPlaylist(hls.getPlaylistUriLocal());
        } else if (hls.isWaitingForMasterChange()) {
            log.info("HLS master unchanged, scheduling poll session {}", session.getId());
            scheduleMasterPoll(session);
        } else {
            log.info("HLS master refreshed during playback, bytes={}", rewrittenBody.length());
        }
    }

    public void cancelAllMasterPolls() {
        for (Session session : sessionManager.allSessions()) {
            cancelMasterPoll(session.getId());
            var hls = session.getHlsPlaylistState();
            if (hls != null) {
                hls.setWaitingForMasterChange(false);
            }
        }
    }

    private void requestMasterRefresh(Session session) {
        var hls = session.getHlsPlaylistState();
        if (hls == null) {
            return;
        }
        sendFcupRequest(session, hls.getRemoteMasterUri());
    }

    private void scheduleMasterPoll(Session session) {
        cancelMasterPoll(session.getId());
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            var hls = session.getHlsPlaylistState();
            if (hls != null && hls.isWaitingForMasterChange()) {
                requestMasterRefresh(session);
            }
        }, MASTER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        masterPollTasks.put(session.getId(), future);
    }

    private void cancelMasterPoll(String sessionId) {
        ScheduledFuture<?> future = masterPollTasks.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public void sendFcupRequest(Session session, String listUri) {
        var eventContext = session.getReverseContexts().get("event");
        if (eventContext == null) {
            log.error("No reverse event channel for FCUP request {}", listUri);
            return;
        }
        int requestId = 1;
        var hls = session.getHlsPlaylistState();
        if (hls != null) {
            requestId = hls.nextFcupRequestId();
        }
        log.info("FCUP request: url={}, requestId={}, session={}", listUri, requestId, session.getId());
        var requestContent = PropertyListUtil.prepareEventRequest(session.getId(), listUri, requestId);

        DefaultFullHttpRequest event = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/event");
        event.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        event.headers().add(HttpHeaderNames.CONTENT_LENGTH, requestContent.length);
        event.headers().add("X-Apple-Session-ID", session.getId());
        event.content().writeBytes(requestContent);

        eventContext.writeAndFlush(event);
    }
}
