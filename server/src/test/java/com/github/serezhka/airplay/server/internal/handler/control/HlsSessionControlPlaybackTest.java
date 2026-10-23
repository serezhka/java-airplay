package com.github.serezhka.airplay.server.internal.handler.control;

import com.github.serezhka.airplay.server.internal.handler.session.Session;
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package-private {@link HlsSessionControl} → {@link com.github.serezhka.airplay.server.Playback}
 * without Netty parent sockets (EmbeddedChannel has no ServerSocketChannel parent).
 */
class HlsSessionControlPlaybackTest {

    private SessionManager sessions;
    private RecordingPlayback playback;
    private HlsFcupService fcup;
    private HlsSessionControl hlsSession;

    @BeforeEach
    void setUp() {
        sessions = new SessionManager();
        playback = new RecordingPlayback();
        fcup = new HlsFcupService(sessions, playback);
        hlsSession = new HlsSessionControl(sessions, fcup, playback);
    }

    @AfterEach
    void tearDown() {
        fcup.stopStateHeartbeat();
    }

    @Test
    void mediaPlaylistStartsWithSeekAndTearsMirror() {
        Session session = sessions.getSession("hls-ctrl");
        hlsSession.startMediaPlaylist(
                session,
                "http://127.0.0.1/cdn/mediadata.m3u8",
                "http://127.0.0.1:7000/playlist",
                "test-client",
                2.5,
                "ITEM-UUID");

        assertTrue(playback.eventsContainInOrder("videoDisconnect", "playlist", "seek"));
        assertEquals("http://127.0.0.1/cdn/mediadata.m3u8", playback.lastPlaylist());
        assertEquals(2.5, playback.lastSeek(), 1e-6);
    }

    @Test
    void stopHlsPlaybackRemovesPlaylist() {
        Session session = sessions.getSession("hls-stop");
        hlsSession.startMediaPlaylist(
                session,
                "http://127.0.0.1/cdn/mediadata.m3u8",
                "http://127.0.0.1:7000/playlist",
                "test-client",
                null,
                null);
        playback.clearEvents();

        hlsSession.stopHlsPlayback(session);

        assertTrue(playback.eventsContainInOrder("playlistRemoved"));
        assertNull(session.getHlsPlaylistState());
    }

    @Test
    void masterPlaylistDoesNotStartPlayerUntilMediadata() {
        Session session = sessions.getSession("hls-master");
        hlsSession.startMediaPlaylist(
                session,
                "mlhls://localhost/master.m3u8",
                "http://127.0.0.1:7000/playlist",
                "YouTube",
                1.0,
                "AD-1");

        assertTrue(session.getHlsPlaylistState() != null);
        assertTrue(playback.events().stream().noneMatch(e -> e.equals("playlist")));
        assertEquals(1.0, session.getHlsPlaylistState().getPendingSeekSeconds(), 1e-6);
    }
}
