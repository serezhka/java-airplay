package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
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
    /** If no frame arrives, announce anyway so the sender is not stuck on loading. */
    private static final long PRESENTATION_FALLBACK_MS = 8_000;
    /**
     * Re-assert {@code playing} or {@code paused} on the reverse channel. The sender's
     * play/pause icon follows these events; {@code /playback-info} only moves the timeline.
     * Skipped while the channel is busy so FCUP and the VOD end burst are not queued behind it.
     */
    private static final long STATE_HEARTBEAT_MS = 2_000;

    private final SessionManager sessionManager;
    private final Playback airPlayConsumer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "airplay-hls-master-poll");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, ScheduledFuture<?>> masterPollTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> presentationFallbacks = new ConcurrentHashMap<>();
    /** One in-flight reverse {@code POST /event} per session (no HTTP pipelining). */
    private final Map<String, ReverseQueue> reverseQueues = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> stateHeartbeat;

    public HlsFcupService(SessionManager sessionManager, Playback airPlayConsumer) {
        this.sessionManager = sessionManager;
        this.airPlayConsumer = airPlayConsumer;
        this.stateHeartbeat = scheduler.scheduleAtFixedRate(
                this::pushPlaybackStates, STATE_HEARTBEAT_MS, STATE_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    /** Stop the state re-assert. Master polls use the same executor and keep running. */
    public void stopStateHeartbeat() {
        stateHeartbeat.cancel(false);
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

            // Live sliding windows: loading + master/media FCUP until body swap.
            // VOD ENDLIST: push the known-good reverse /event EOS burst (itemPlayedToEnd →
            // stopped/reason=ended → itemRemoved → currentItemChanged), keep rate=1 pin.
            // Lone paused/stopped without that burst hung historically; do not FCUP-storm VOD.
            hls.setWaitingForMasterChange(true);
            hls.resetPostEosMediaSweep();
            if (hls.isLivePlaylist()) {
                log.info("HLS ended (live playlist), refreshing master (keep session) {}", session.getId());
                sendPlaybackStateEvent(session, "loading");
                requestMasterRefresh(session);
                scheduleMasterPoll(session);
            } else {
                log.info("HLS ended (VOD actionAtItemEnd={}), emit EOS reverse-event burst session={}",
                        hls.getActionAtItemEnd(), session.getId());
                hls.setPlaybackRate(1);
                sendVodEosEventBurst(session);
            }
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
            beginDisplayedPlayback(session);
            Double seek = hls.takePendingSeekSeconds();
            if (seek != null && seek > 0) {
                airPlayConsumer.onSeek(seek);
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
            beginDisplayedPlayback(session);
        } else {
            log.info("HLS media unchanged after EOS, polling master session {}", session.getId());
            scheduleMasterPoll(session);
        }
    }

    public void cancelAllMasterPolls() {
        for (Session session : sessionManager.allSessions()) {
            cancelMasterPoll(session.getId());
            cancelPresentationFallback(session.getId());
            var hls = session.getHlsPlaylistState();
            if (hls != null) {
                hls.setWaitingForMasterChange(false);
            }
        }
    }

    public void clearReverseQueue(Session session) {
        cancelPresentationFallback(session.getId());
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
                    log.info("Reverse /event accepted, {} still queued session={}",
                            queue.pending.size(), session.getId());
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

    /**
     * Start the player but keep the sender on {@code loading} until a frame is visible.
     * Announcing {@code playing} at {@code pipeline.play()} makes the phone run ahead of the display.
     */
    public void beginDisplayedPlayback(Session session) {
        var hls = session.getHlsPlaylistState();
        if (hls == null) {
            return;
        }
        hls.awaitPresentation();
        airPlayConsumer.onPlaylist(hls.getPlaylistUriLocal());
        schedulePresentationFallback(session);
    }

    /** Replacement picture after a seek — refresh {@code playing} with the real clock. */
    public void onSeekDisplayed() {
        for (Session session : sessionManager.allSessions()) {
            var hls = session.getHlsPlaylistState();
            if (hls == null) {
                continue;
            }
            hls.endScrubGesture();
            log.info("Announcing playing (seek picture) session={}", session.getId());
            sendPlaybackStateEvent(session, "playing");
        }
    }

    /** First picture is on the display. Announce {@code playing} once. */
    public void onPlaybackPresented() {
        for (Session session : sessionManager.allSessions()) {
            announcePlayingIfPending(session, "picture");
        }
    }

    private void schedulePresentationFallback(Session session) {
        cancelPresentationFallback(session.getId());
        String sessionId = session.getId();
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            presentationFallbacks.remove(sessionId);
            Session current = sessionManager.getSession(sessionId);
            if (current == null) {
                return;
            }
            announcePlayingIfPending(current, "fallback");
        }, PRESENTATION_FALLBACK_MS, TimeUnit.MILLISECONDS);
        presentationFallbacks.put(sessionId, future);
    }

    private void announcePlayingIfPending(Session session, String reason) {
        var hls = session.getHlsPlaylistState();
        if (hls == null || !hls.claimPresentation()) {
            return;
        }
        cancelPresentationFallback(session.getId());
        log.info("Announcing playing ({}) session={}", reason, session.getId());
        sendPlaybackStateEvent(session, "playing");
    }

    private void cancelPresentationFallback(String sessionId) {
        ScheduledFuture<?> future = presentationFallbacks.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * State to re-assert, or {@code null} when the sender must not hear one
     * (still loading the first frame, waiting for the next item, or at VOD end).
     */
    String playbackStateToAnnounce(Session session) {
        var hls = session.getHlsPlaylistState();
        if (hls == null || !hls.isPlaybackStarted() || hls.isAwaitingPresentation()
                || hls.isWaitingForMasterChange()) {
            return null;
        }
        if (hls.getPlaybackRate() <= 0) {
            return "paused";
        }
        if (!hls.isLivePlaylist()) {
            Playback.Info info = airPlayConsumer.info();
            double duration = hls.getMediaDurationSeconds() > 0
                    ? hls.getMediaDurationSeconds()
                    : info.duration();
            if (duration > 0 && info.position() + 0.25 >= duration) {
                return null;
            }
        }
        return "playing";
    }

    private void pushPlaybackStates() {
        for (Session session : sessionManager.allSessions()) {
            try {
                String state = playbackStateToAnnounce(session);
                if (state == null) {
                    continue;
                }
                offerPlaybackState(session, state);
            } catch (RuntimeException e) {
                log.debug("Playback state heartbeat failed session={}: {}", session.getId(), e.toString());
            }
        }
    }

    /**
     * Enqueue only when the reverse channel is idle. Checked under the queue lock so a
     * pause or end burst that arrived in the same moment is not followed by a stale
     * {@code playing}.
     */
    private void offerPlaybackState(Session session, String state) {
        var eventContext = session.getReverseContexts().get("event");
        if (eventContext == null || !eventContext.channel().isActive()) {
            return;
        }
        byte[] body = playbackStateBody(session, state);
        ReverseQueue queue = reverseQueues.computeIfAbsent(session.getId(), id -> new ReverseQueue());
        synchronized (queue) {
            if (queue.inFlight || !queue.pending.isEmpty()) {
                return;
            }
            queue.pending.add(body);
            flushReverseQueue(session, queue);
        }
    }

    public void sendPlaybackStateEvent(Session session, String state) {
        enqueueReverseEvent(session, playbackStateBody(session, state));
    }

    private byte[] playbackStateBody(Session session, String state) {
        var hls = session.getHlsPlaylistState();
        Playback.Info playback = null;
        if ("playing".equals(state)) {
            Playback.Info info = airPlayConsumer.info();
            double duration = info.duration();
            double position = info.position();
            if (hls != null && hls.getMediaDurationSeconds() > 0) {
                duration = hls.getMediaDurationSeconds();
            }
            if (duration > 0) {
                position = Math.min(Math.max(0, position), duration);
                // The event says playing, so the clock rate is 1 even if the player
                // was still paused when this sample was taken.
                playback = new Playback.Info(duration, position, 1);
            }
        }
        byte[] body;
        if (hls != null) {
            body = PropertyListUtil.preparePlaybackStateEvent(
                    state, hls.getReverseEventSessionId(), hls.getItemUuid(), null, playback);
        } else {
            body = PropertyListUtil.preparePlaybackStateEvent(state);
        }
        String xml = new String(body, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        if (playback != null) {
            log.info("Playback state event: {} position={} rate={} session={} xml={}",
                    state, playback.position(), playback.rate(), session.getId(), xml);
        } else {
            log.info("Playback state event: {} session={} xml={}", state, session.getId(), xml);
        }
        return body;
    }

    /**
     * Working receiver VOD EOS sequence on reverse {@code POST /event}
     * (after local player end). Queued FIFO — one in-flight request at a time.
     */
    public void sendVodEosEventBurst(Session session) {
        var hls = session.getHlsPlaylistState();
        if (hls == null) {
            return;
        }
        int sid = hls.getReverseEventSessionId();
        String itemUuid = hls.getItemUuid();
        String playId = session.getId();
        log.info("VOD EOS reverse burst playId={} reverseSessionId={} itemUuid={}",
                playId, sid, itemUuid);
        enqueueReverseEvent(session,
                PropertyListUtil.preparePlaybackStateEvent("loading", sid, itemUuid, null));
        enqueueReverseEvent(session,
                PropertyListUtil.prepareVideoTypedEvent("itemPlayedToEnd", sid, itemUuid));
        enqueueReverseEvent(session,
                PropertyListUtil.preparePlaybackStateEvent("stopped", sid, itemUuid, "ended"));
        enqueueReverseEvent(session,
                PropertyListUtil.prepareItemRemovedEvent(playId, itemUuid));
        enqueueReverseEvent(session,
                PropertyListUtil.prepareCurrentItemChangedEvent(sid));
        enqueueReverseEvent(session,
                PropertyListUtil.preparePlaybackStateEvent("stopped", sid, itemUuid, null));
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
