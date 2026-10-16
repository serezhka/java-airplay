package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.HlsUriRewrite;
import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns HLS session start/stop and the mirror→HLS handoff.
 * HTTP handlers stay in {@link ControlHandler}; FCUP/events stay in {@link HlsFcupService}.
 */
@Slf4j
final class HlsSessionControl {

    /** If video mediadata never arrives, start anyway so /play does not hang. */
    private static final long PLAYBACK_START_FALLBACK_MS = 8_000;

    private final SessionManager sessionManager;
    private final HlsFcupService hlsFcupService;
    private final Playback airPlayConsumer;
    private final ScheduledExecutorService hlsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "airplay-hls-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingPlaybackStarts = new ConcurrentHashMap<>();

    HlsSessionControl(SessionManager sessionManager,
                      HlsFcupService hlsFcupService,
                      Playback airPlayConsumer) {
        this.sessionManager = sessionManager;
        this.hlsFcupService = hlsFcupService;
        this.airPlayConsumer = airPlayConsumer;
    }

    void stopHlsPlayback(Session session) {
        cancelPlaybackStartFallback(session.getId());
        hlsFcupService.cancelAllMasterPolls();
        hlsFcupService.clearReverseQueue(session);
        session.setHlsPlaylistState(null);
        airPlayConsumer.onPlaylistRemoved();
    }

    /**
     * Start (or restart) media/HLS playback from a Content-Location URI.
     * Used by POST /play and by playlistInsert (e.g. quality switch).
     */
    void startMediaPlaylist(Session session, String playlistUri, String playlistBaseUrl,
                            String clientProcName, Double startPositionSeconds, String clientItemUuid) {
        // Do not tear mirror here. Some senders issue a follow-up /play while leaving the app;
        // stopping VideoServer on that /play drops mirror video when FCUP never reaches mediadata.
        // Tear mirror only when HLS actually starts the player.
        var playlistUriLocal = HlsUriRewrite.toLocalUri(playlistUri, playlistBaseUrl, session.getId());
        var remotePlaylistUri = playlistUri.split("\\?")[0];

        if (remotePlaylistUri.contains("master.m3u8")) {
            hlsFcupService.cancelAllMasterPolls();
            var hls = new HlsPlaylistState(remotePlaylistUri, playlistUriLocal, clientItemUuid);
            hls.setPlaybackRate(1);
            session.setHlsPlaylistState(hls);
            log.info("SESSION mode=HLS session={} itemUuid={} client={}",
                    session.getId(), hls.getItemUuid(), clientProcName);
            if (startPositionSeconds != null && startPositionSeconds > 0) {
                hls.setPendingSeekSeconds(startPositionSeconds);
            }
            log.debug("HLS FCUP prefetch localUri={} client={}", playlistUriLocal, clientProcName);
            // Signal loading while FCUP + first video playlist are still in flight.
            hlsFcupService.sendPlaybackStateEvent(session, "loading");
            hlsFcupService.sendFcupRequest(session, remotePlaylistUri);
        } else {
            stopMirrorVideoIfRunning(session);
            airPlayConsumer.onPlaylist(playlistUriLocal);
            if (startPositionSeconds != null && startPositionSeconds > 0) {
                airPlayConsumer.onSeek(startPositionSeconds);
            }
        }
    }

    void stopMirrorVideoIfRunning(Session session) {
        boolean anyVideo = false;
        for (Session other : sessionManager.allSessions()) {
            try {
                if (other.getVideoServer() != null && other.getVideoServer().isRunning()) {
                    anyVideo = true;
                    other.getVideoServer().stop();
                }
            } catch (Exception e) {
                log.debug("Mirror video stop before HLS ignored: {}", e.toString());
            }
        }
        if (anyVideo) {
            log.debug("Stopping screen-mirror video before HLS session={}", session.getId());
        }
        try {
            airPlayConsumer.onVideoSrcDisconnect();
        } catch (Exception e) {
            log.debug("Mirror video source disconnect ignored: {}", e.toString());
        }
    }

    void startHlsPlayback(Session session, HlsPlaylistState hls) {
        // First video mediadata is ready — drop mirror so HLS is the only picture.
        stopMirrorVideoIfRunning(session);
        hlsFcupService.beginDisplayedPlayback(session);
        Double seek = hls.takePendingSeekSeconds();
        if (seek != null && seek > 0) {
            airPlayConsumer.onSeek(seek);
        }
    }

    void maybeStartHlsPlayback(Session session, HlsPlaylistState hls, String reason) {
        if (hls == null || hls.isPlaybackStarted() || !hls.hasCachedVideoMedia()) {
            return;
        }
        hls.markPlaybackStarted();
        cancelPlaybackStartFallback(session.getId());
        log.info("HLS starting playback ({}): {}", reason, hls.getPlaylistUriLocal());
        startHlsPlayback(session, hls);
    }

    void schedulePlaybackStartFallback(Session session, HlsPlaylistState hls) {
        cancelPlaybackStartFallback(session.getId());
        String sessionId = session.getId();
        ScheduledFuture<?> future = hlsScheduler.schedule(() -> {
            pendingPlaybackStarts.remove(sessionId);
            if (hls.isPlaybackStarted()) {
                return;
            }
            if (session.getHlsPlaylistState() != hls) {
                return;
            }
            hls.markPlaybackStarted();
            log.debug("HLS starting playback (fallback after {}ms): {}",
                    PLAYBACK_START_FALLBACK_MS, hls.getPlaylistUriLocal());
            startHlsPlayback(session, hls);
        }, PLAYBACK_START_FALLBACK_MS, TimeUnit.MILLISECONDS);
        pendingPlaybackStarts.put(sessionId, future);
    }

    void cancelPlaybackStartFallback(String sessionId) {
        ScheduledFuture<?> future = pendingPlaybackStarts.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** Follow a scrub's trailing {@code rate=0} so the sender does not stay on pause. */
    void reannounceAfterScrubRateZero(Session session) {
        hlsFcupService.sendPlaybackStateEvent(session, "loading");
        hlsFcupService.sendPlaybackStateEvent(session, "playing");
    }

    /**
     * The playback-info already in flight with {@code rate=0} also arrives before {@code /scrub}.
     * The following poll is the pause: a scrub would have cleared the flag by then.
     */
    void commitPauseOnPlaybackInfo(Session session) {
        var hls = session.getHlsPlaylistState();
        if (hls == null || !hls.notePlaybackInfoForPause()) {
            return;
        }
        if (hls.isWaitingForMasterChange()) {
            hls.clearRateZero();
            return;
        }
        hls.clearRateZero();
        hls.setPlaybackRate(0);
        // Pipeline was already soft-paused on rate=0; keep onPause idempotent.
        airPlayConsumer.onPause();
        hlsFcupService.sendPlaybackStateEvent(session, "paused");
        log.debug("Pause committed on playback-info (no /scrub within scrub window) session={}",
                session.getId());
    }
}
