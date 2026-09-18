package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HlsFcupServiceTest {

    private final SessionManager sessions = new SessionManager();
    private final List<String> playlists = new ArrayList<>();
    private final List<String> states = new CopyOnWriteArrayList<>();
    private final AtomicInteger pauseCalls = new AtomicInteger();
    private final AtomicInteger resumeCalls = new AtomicInteger();
    private final AirPlayConsumer consumer = new AirPlayConsumer() {
        @Override
        public void onVideoFormat(com.github.serezhka.airplay.lib.VideoStreamInfo videoStreamInfo) {
        }

        @Override
        public void onVideo(byte[] bytes) {
        }

        @Override
        public void onVideoSrcDisconnect() {
        }

        @Override
        public void onAudioFormat(com.github.serezhka.airplay.lib.AudioStreamInfo audioStreamInfo) {
        }

        @Override
        public void onAudio(byte[] bytes) {
        }

        @Override
        public void onAudioSrcDisconnect() {
        }

        @Override
        public void onMediaPlaylist(String playlistUri) {
            playlists.add(playlistUri);
        }

        @Override
        public void onMediaPlaylistPause() {
            pauseCalls.incrementAndGet();
        }

        @Override
        public void onMediaPlaylistResume() {
            resumeCalls.incrementAndGet();
        }
    };
    private final HlsFcupService service = new HlsFcupService(sessions, consumer) {
        @Override
        public void sendPlaybackStateEvent(
                com.github.serezhka.airplay.server.internal.handler.session.Session session, String state) {
            states.add(state);
            super.sendPlaybackStateEvent(session, state);
        }
    };

    @AfterEach
    void tearDown() {
        service.cancelAllMasterPolls();
    }

    @Test
    void vodEosSignalsLoadingAndStartsMediaSweepNotPaused() {
        // Dump 20260918-040320: paused + master-only poll never got playlistRemove.
        // Sep 16 working path: loading + media FCUP → playlistRemove + next /play.
        var session = sessions.getSession("vod-ad");
        var hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=vod-ad");
        hls.setActionAtItemEnd(1);
        hls.markPlaybackStarted();
        String mediaUri = "mlhls://localhost/itag/232/mediadata.m3u8";
        hls.putPlaylist(mediaUri, "#EXTM3U\n#EXTINF:6.0,\nad.ts\n#EXT-X-ENDLIST\n");
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmediadata.m3u8\n";
        hls.recordMasterIfChanged(master);
        hls.recordRawMasterIfChanged("raw-master");
        session.setHlsPlaylistState(hls);

        service.refreshActivePlaylists();

        assertTrue(hls.isWaitingForMasterChange());
        assertEquals(0.0, hls.getPlaybackRate());
        assertTrue(states.contains("loading"));
        assertFalse(states.contains("paused"));
        assertFalse(states.contains("stopped"));

        service.onMasterRefreshDuringPlayback(session, master, "raw-master", List.of(mediaUri));

        assertTrue(hls.isWaitingForMasterChange());
        assertTrue(hls.isPostEosMediaRefreshing());
        assertTrue(playlists.isEmpty());
    }

    @Test
    void liveEosStillUsesLoading() {
        var session = sessions.getSession("live");
        var hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=live");
        hls.setActionAtItemEnd(1);
        hls.markPlaybackStarted();
        hls.putPlaylist(
                "mlhls://localhost/itag/232/mediadata.m3u8",
                "#EXTM3U\n#EXTINF:6.0,\nseg.ts\n"); // no ENDLIST → live
        session.setHlsPlaylistState(hls);

        service.refreshActivePlaylists();

        assertTrue(hls.isWaitingForMasterChange());
        assertTrue(hls.isLivePlaylist());
        assertTrue(states.contains("loading"));
    }
}
