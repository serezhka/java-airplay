package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;

/**
 * Builds {@link Playback.Info} for {@code GET /playback-info} from player clock + HLS session.
 * Pure: no Netty / side effects.
 */
final class HlsPlaybackInfo {

    private HlsPlaybackInfo() {
    }

    static Playback.Info resolve(HlsPlaylistState hls, Playback.Info fromPlayer) {
        double duration = fromPlayer.duration();
        double position = fromPlayer.position();
        double rate = fromPlayer.rate();
        if (hls != null && hls.isWaitingForMasterChange() && hls.isLivePlaylist()) {
            // Live post-EOS gap: duration=0 → buffer-empty / not ready (loading).
            return new Playback.Info(0, 0, 0);
        }
        if (hls != null) {
            if (hls.isLivePlaylist()) {
                duration = 0;
            } else {
                double playlistDur = hls.getMediaDurationSeconds();
                if (playlistDur > 0) {
                    duration = playlistDur;
                } else if (duration > 600) {
                    // Demux sometimes reports multi-hour clocks for short items.
                    duration = 0;
                }
            }
            if (hls.isWaitingForMasterChange() && duration > 0) {
                // VOD end pin: stay at D/D/rate=1. Prefer the shorter player length if
                // a later FCUP body inflated mediaDurationSeconds.
                double playerDur = fromPlayer.duration();
                if (playerDur > 0.5 && playerDur + 0.25 < duration) {
                    duration = playerDur;
                }
                position = duration;
                rate = 1;
            } else {
                if (duration > 0) {
                    position = Math.min(position, duration);
                }
                rate = hls.getPlaybackRate() <= 0 ? 0 : 1;
            }
            return new Playback.Info(duration, position, rate);
        }
        if (duration > 600) {
            duration = 0;
        }
        return new Playback.Info(duration, position, rate);
    }
}
