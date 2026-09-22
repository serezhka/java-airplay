package com.github.serezhka.airplay.player.vlc;

import com.github.serezhka.airplay.lib.HlsLifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlcHlsPipelineTest {

    private final VlcHlsPipeline hls = new VlcHlsPipeline();

    @AfterEach
    void tearDown() {
        hls.stop();
        HlsLifecycle.setOnEnded(null);
    }

    @Test
    void notesEndListDuration() {
        hls.noteMediaDuration(6.0);
        hls.noteMediaDuration(13.6);
        assertEquals(13.6, hls.durationSeconds(), 0.001);
        hls.noteMediaDuration(10.0);
        assertEquals(13.6, hls.durationSeconds(), 0.001);
    }

    @Test
    void ignoresAbsurdSeekTargets() {
        hls.noteMediaDuration(30);
        hls.seek(-1);
        hls.seek(Double.NaN);
        hls.seek(Double.POSITIVE_INFINITY);
        hls.seek(86_400.0 * 8);
        assertEquals(0, hls.currentPositionSeconds(), 0.001);
    }

    @Test
    void pauseFreezesReportedPosition() throws Exception {
        System.setProperty("airplay.vlc.hls.headless", "true");
        try {
            hls.start("http://127.0.0.1:9/missing.m3u8", 1.0);
            hls.noteMediaDuration(60);
            hls.seek(5);
            assertEquals(5, hls.currentPositionSeconds(), 0.1);
            hls.pause();
            assertTrue(hls.isPaused());
            double pausedAt = hls.currentPositionSeconds();
            Thread.sleep(200);
            assertEquals(pausedAt, hls.currentPositionSeconds(), 0.05);
            hls.resume();
            assertFalse(hls.isPaused());
        } finally {
            System.clearProperty("airplay.vlc.hls.headless");
        }
    }

    @Test
    void startWithInitialSeekDoesNotRequireFollowUpSeek() throws Exception {
        System.setProperty("airplay.vlc.hls.headless", "true");
        try {
            hls.start("http://127.0.0.1:9/missing.m3u8", 1.0, 42.5);
            hls.noteMediaDuration(120);
            assertEquals(42.5, hls.currentPositionSeconds(), 0.2);
        } finally {
            System.clearProperty("airplay.vlc.hls.headless");
        }
    }

    @Test
    void playerBakesSeekBeforePlaylistIntoStart() throws Exception {
        System.setProperty("airplay.vlc.headless", "true");
        System.setProperty("airplay.vlc.hls.headless", "true");
        VlcPlayer player = new VlcPlayer();
        try {
            player.onMediaPlaylistSeek(33.0);
            player.onMediaPlaylist("http://127.0.0.1:9/missing.m3u8");
            player.onMediaPlaylistContent(
                    "mlhls://localhost/itag/229/mediadata.m3u8",
                    "#EXTM3U\n#EXTINF:60.0,\nseg.ts\n#EXT-X-ENDLIST\n");
            Thread.sleep(80);
            assertEquals(33.0, player.playbackInfo().position(), 0.5);
        } finally {
            player.onMediaPlaylistRemove();
            System.clearProperty("airplay.vlc.headless");
            System.clearProperty("airplay.vlc.hls.headless");
        }
    }

    @Test
    void playerParsesEndListDurationIntoPlaybackInfo() throws Exception {
        System.setProperty("airplay.vlc.headless", "true");
        System.setProperty("airplay.vlc.hls.headless", "true");
        VlcPlayer player = new VlcPlayer();
        try {
            player.onMediaPlaylist("http://127.0.0.1:9/missing.m3u8");
            player.onMediaPlaylistContent(
                    "mlhls://localhost/itag/229/mediadata.m3u8",
                    "#EXTM3U\n#EXTINF:6.5,\nad.ts\n#EXTINF:7.1,\nad2.ts\n#EXT-X-ENDLIST\n");
            Thread.sleep(50);
            var info = player.playbackInfo();
            assertTrue(info.duration() >= 13.5 && info.duration() <= 13.7);
        } finally {
            player.onMediaPlaylistRemove();
            System.clearProperty("airplay.vlc.headless");
            System.clearProperty("airplay.vlc.hls.headless");
        }
    }

    @Test
    void stopDoesNotNotifyLifecycle() {
        var ends = new java.util.concurrent.atomic.AtomicInteger();
        HlsLifecycle.setOnEnded(ends::incrementAndGet);
        hls.noteMediaDuration(1.0);
        hls.stop();
        assertEquals(0, ends.get());
    }
}
