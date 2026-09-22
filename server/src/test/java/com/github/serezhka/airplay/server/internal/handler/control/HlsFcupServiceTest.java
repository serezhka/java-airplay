package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.AirPlayConsumer;
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
    private final List<String> reverseBodies = new CopyOnWriteArrayList<>();
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
            reverseBodies.add("state:" + state);
            super.sendPlaybackStateEvent(session, state);
        }

        @Override
        public void sendVodEosEventBurst(
                com.github.serezhka.airplay.server.internal.handler.session.Session session) {
            // Capture labels without needing an active reverse channel.
            reverseBodies.add("state:loading");
            reverseBodies.add("type:itemPlayedToEnd");
            reverseBodies.add("state:stopped:ended");
            reverseBodies.add("type:itemRemoved");
            reverseBodies.add("type:currentItemChanged");
            reverseBodies.add("state:stopped");
        }
    };

    @AfterEach
    void tearDown() {
        service.cancelAllMasterPolls();
    }

    @Test
    void vodEosEmitsReverseEventBurstWithoutMediaSweep() {
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
        assertEquals(1.0, hls.getPlaybackRate());
        assertEquals(List.of(
                "state:loading",
                "type:itemPlayedToEnd",
                "state:stopped:ended",
                "type:itemRemoved",
                "type:currentItemChanged",
                "state:stopped"), reverseBodies);
        assertFalse(hls.isPostEosMediaRefreshing());
        assertTrue(playlists.isEmpty());
        assertFalse(reverseBodies.stream().anyMatch(s -> s.equals("state:paused")));
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
        assertTrue(reverseBodies.contains("state:loading"));
        assertFalse(reverseBodies.contains("type:itemPlayedToEnd"));
    }

    @Test
    void vodEosBurstXmlShapes() {
        var session = sessions.getSession("shape");
        var hls = new HlsPlaylistState(
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1/playlist/master.m3u8?session=shape");
        session.setHlsPlaylistState(hls);
        // Exercise real PropertyListUtil path via a service that records bodies.
        List<String> xmls = new ArrayList<>();
        HlsFcupService recording = new HlsFcupService(sessions, consumer) {
            @Override
            public void sendVodEosEventBurst(
                    com.github.serezhka.airplay.server.internal.handler.session.Session s) {
                var st = s.getHlsPlaylistState();
                int sid = st.getReverseEventSessionId();
                String item = st.getItemUuid();
                xmls.add(new String(com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
                        .preparePlaybackStateEvent("loading", sid, item, null), StandardCharsets.UTF_8));
                xmls.add(new String(com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
                        .prepareVideoTypedEvent("itemPlayedToEnd", sid, item), StandardCharsets.UTF_8));
                xmls.add(new String(com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
                        .preparePlaybackStateEvent("stopped", sid, item, "ended"), StandardCharsets.UTF_8));
                xmls.add(new String(com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
                        .prepareItemRemovedEvent(s.getId(), item), StandardCharsets.UTF_8));
                xmls.add(new String(com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
                        .prepareCurrentItemChangedEvent(sid), StandardCharsets.UTF_8));
            }
        };
        recording.sendVodEosEventBurst(session);

        assertTrue(xmls.get(0).contains("<string>loading</string>"));
        assertTrue(xmls.get(0).contains(hls.getItemUuid()));
        assertTrue(xmls.get(1).contains("<string>itemPlayedToEnd</string>"));
        assertTrue(xmls.get(2).contains("<string>ended</string>"));
        assertTrue(xmls.get(3).contains("<string>itemRemoved</string>"));
        assertTrue(xmls.get(3).contains("<string>shape</string>"));
        assertTrue(xmls.get(4).contains("<string>currentItemChanged</string>"));
        recording.cancelAllMasterPolls();
    }
}
