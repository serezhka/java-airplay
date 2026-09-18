package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HlsFcupService {

    private static final long MASTER_POLL_INTERVAL_MS = 2000;
    /**
     * After the first post-EOS mediadata sweep, re-FCUP all mediadata every N master polls.
     * YouTube ad→content often keeps the same master URI list and only swaps VOD bodies.
     * N=3 (~6s) balances freshness vs reverse /event channel load (playlistRemove / next /play).
     */
    private static final int MEDIA_RESWEEP_EVERY_MASTER_POLLS = 3;

    private final SessionManager sessionManager;
    private final AirPlayConsumer airPlayConsumer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "airplay-hls-master-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledFuture<?>> masterPollTasks = new ConcurrentHashMap<>();
    /** One in-flight reverse {@code POST /event} per session (no HTTP pipelining). */
    private final Map<String, ReverseQueue> reverseQueues = new ConcurrentHashMap<>();

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
            cancelMasterPoll(session.getId());
            hls.abortMediaPrefetch();
            hls.setWaitingForMasterChange(false);
            hls.setPlaybackRate(0);

            // Never "paused" or "stopped" on EOS:
            // - paused looks like a user pause → YouTube never playlistRemove / next /play
            //   (dump 20260918-040320: paused + master-only poll → hung forever)
            // - stopped closes reverse /event on live preroll → content
            // Working path (2026-09-16 logs): loading + master/media FCUP → playlistRemove + /play
            int action = hls.getActionAtItemEnd();
            String reason = hls.isLivePlaylist()
                    ? "live playlist"
                    : "actionAtItemEnd=" + action;
            log.info("HLS ended ({}), refreshing master (keep session) {}", reason, session.getId());
            hls.setWaitingForMasterChange(true);
            hls.resetPostEosMediaSweep();
            sendPlaybackStateEvent(session, "loading");
            requestMasterRefresh(session);
            scheduleMasterPoll(session);
        }
    }

    public void onMasterRefreshDuringPlayback(Session session, String rewrittenBody, String rawBody,
                                              List<String> remoteMediaUris) {
        var hls = session.getHlsPlaylistState();
        if (hls == null || !hls.isPlaybackStarted()) {
            return;
        }
        boolean rewrittenChanged = hls.recordMasterIfChanged(rewrittenBody);
        boolean rawChanged = hls.recordRawMasterIfChanged(rawBody);
        boolean changed = rewrittenChanged || rawChanged;
        if (changed) {
            log.info("HLS master changed (rewritten={}, raw={}), restarting playback session {}",
                    rewrittenChanged, rawChanged, session.getId());
            cancelMasterPoll(session.getId());
            hls.setWaitingForMasterChange(false);
            hls.setPlaybackRate(1);
            if (remoteMediaUris != null && !remoteMediaUris.isEmpty()) {
                try {
                    hls.storeMasterPlaylist(rewrittenBody, remoteMediaUris);
                } catch (Exception e) {
                    log.warn("Failed to refresh media URI list after master change", e);
                    hls.updateMasterPlaylist(rewrittenBody);
                }
            } else {
                hls.updateMasterPlaylist(rewrittenBody);
            }
            airPlayConsumer.onMediaPlaylist(hls.getPlaylistUriLocal());
            sendPlaybackStateEvent(session, "playing");
            Double seek = hls.takePendingSeekSeconds();
            if (seek != null && seek > 0) {
                airPlayConsumer.onMediaPlaylistSeek(seek);
            }
        } else if (hls.isWaitingForMasterChange()) {
            if (hls.isPostEosMediaRefreshing()) {
                log.debug("HLS master unchanged, media refresh still in flight session={}", session.getId());
                return;
            }
            hls.updateMasterPlaylist(rewrittenBody);
            // Prefer a mediadata sweep after EOS: master URI list often stays identical while
            // VOD bodies swap (preroll ad → content). First sweep immediately; then every
            // MEDIA_RESWEEP_EVERY_MASTER_POLLS master polls so we do not starve /event
            // (playlistRemove / next /play still need the reverse channel).
            if (!hls.isPostEosMediaSweepDone()
                    || hls.noteMasterPollAndShouldResweepMedia(MEDIA_RESWEEP_EVERY_MASTER_POLLS)) {
                log.info("HLS master unchanged after EOS, refreshing media playlists session={}", session.getId());
                hls.beginPostEosMediaRefresh(remoteMediaUris);
                String first = hls.nextMediaUri();
                if (first != null) {
                    sendFcupRequest(session, first);
                } else {
                    hls.markPostEosMediaSweepDone();
                    scheduleMasterPoll(session);
                }
            } else {
                log.debug("HLS master still unchanged, master-only poll session={}", session.getId());
                scheduleMasterPoll(session);
            }
        } else {
            log.info("HLS master refreshed during playback, bytes={}", rewrittenBody.length());
        }
    }

    /** Called when a post-EOS media FCUP round finishes (all queued mediadata fetched). */
    public void onPostEosMediaRefreshComplete(Session session) {
        var hls = session.getHlsPlaylistState();
        if (hls == null || !hls.isPostEosMediaRefreshing()) {
            return;
        }
        boolean mediaChanged = hls.finishPostEosMediaRefresh();
        hls.markPostEosMediaSweepDone();
        if (mediaChanged) {
            log.info("HLS media playlists changed after EOS, restarting playback session {}", session.getId());
            cancelMasterPoll(session.getId());
            hls.setWaitingForMasterChange(false);
            hls.setPlaybackRate(1);
            airPlayConsumer.onMediaPlaylist(hls.getPlaylistUriLocal());
            sendPlaybackStateEvent(session, "playing");
        } else {
            log.info("HLS media unchanged after EOS, polling master session {}", session.getId());
            scheduleMasterPoll(session);
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

    public void clearReverseQueue(Session session) {
        ReverseQueue queue = reverseQueues.remove(session.getId());
        if (queue != null) {
            synchronized (queue) {
                queue.pending.clear();
                queue.inFlight = false;
            }
        }
    }

    /** Called when the reverse event channel receives HTTP 200 for a POST /event. */
    public void onReverseEventResponse(ChannelHandlerContext ctx) {
        for (Session session : sessionManager.allSessions()) {
            if (session.getReverseContexts().get("event") == ctx) {
                ReverseQueue queue = reverseQueues.get(session.getId());
                if (queue == null) {
                    return;
                }
                synchronized (queue) {
                    queue.inFlight = false;
                    flushReverseQueue(session, queue);
                }
                return;
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
                scheduleMasterPoll(session);
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

    public void sendPlaybackStateEvent(Session session, String state) {
        byte[] body = PropertyListUtil.preparePlaybackStateEvent(state);
        log.info("Playback state event: {} session={}", state, session.getId());
        enqueueReverseEvent(session, body);
    }

    public void sendFcupRequest(Session session, String listUri) {
        int requestId = 1;
        var hls = session.getHlsPlaylistState();
        if (hls != null) {
            requestId = hls.nextFcupRequestId();
        }
        log.info("FCUP request: url={}, requestId={}, session={}", listUri, requestId, session.getId());
        byte[] requestContent = PropertyListUtil.prepareEventRequest(session.getId(), listUri, requestId);
        enqueueReverseEvent(session, requestContent);
    }

    private void enqueueReverseEvent(Session session, byte[] body) {
        ReverseQueue queue = reverseQueues.computeIfAbsent(session.getId(), id -> new ReverseQueue());
        synchronized (queue) {
            queue.pending.add(body);
            flushReverseQueue(session, queue);
        }
    }

    private void flushReverseQueue(Session session, ReverseQueue queue) {
        if (queue.inFlight || queue.pending.isEmpty()) {
            return;
        }
        var eventContext = session.getReverseContexts().get("event");
        if (eventContext == null || !eventContext.channel().isActive()) {
            log.error("No active reverse event channel, dropping {} queued events session={}",
                    queue.pending.size(), session.getId());
            queue.pending.clear();
            return;
        }
        byte[] body = queue.pending.poll();
        queue.inFlight = true;
        DefaultFullHttpRequest event = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/event");
        event.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml");
        event.headers().add(HttpHeaderNames.CONTENT_LENGTH, body.length);
        event.headers().add("X-Apple-Session-ID", session.getId());
        event.content().writeBytes(body);
        eventContext.writeAndFlush(event);
    }

    private static final class ReverseQueue {
        final Queue<byte[]> pending = new ArrayDeque<>();
        boolean inFlight;
    }
}
