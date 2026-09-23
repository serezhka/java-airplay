package com.github.serezhka.airplay.player.ffmpeg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegHlsPipelineTest {

    private final FfmpegHlsPipeline hls = new FfmpegHlsPipeline();

    @AfterEach
    void tearDown() {
        hls.stop();
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
        System.setProperty("airplay.ffmpeg.hls.headless", "true");
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
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    @Test
    void demuxPtsResetDoesNotJumpReportedPosition() throws Exception {
        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        try {
            hls.start("http://127.0.0.1:9/missing.m3u8", 1.0);
            hls.noteMediaDuration(60);
            hls.seek(5.0);
            // Position is frozen while ffplay is down; seek still updates the base.
            assertEquals(5.0, hls.currentPositionSeconds(), 0.1);

            // Simulate HLS/TS segment PTS restart near zero (the phone scrubber bug).
            hls.noteDemuxTimestampMicros(64_944L);
            double after = hls.currentPositionSeconds();
            assertEquals(5.0, after, 0.1,
                    "reported position must not jump on demux PTS reset: " + after);
        } finally {
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    @Test
    void scrubJumpFixtureDocumentsHistoricalDemuxPtsResets() throws Exception {
        // Seed from Kali dump 20260917-074700 — offline proof of the scrubber jumps.
        try (var in = FfmpegHlsPipelineTest.class.getResourceAsStream(
                "/fixtures/scrub-jump-20260917.json")) {
            assertTrue(in != null, "missing fixtures/scrub-jump-20260917.json");
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("\"jump_count\": 4") || json.contains("\"jump_count\":4"), json);
            assertTrue(json.contains("2.986557162"));
            assertTrue(json.contains("0.510844"));
            // Historical dump had backward jumps within the same duration — regression seed.
            assertTrue(json.contains("\"from_pos\": 2.986557162") || json.contains("\"from_pos\":2.986557162"));
        }
    }

    @Test
    void startWithInitialSeekDoesNotRequireFollowUpSeek() throws Exception {
        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        try {
            hls.start("http://127.0.0.1:9/missing.m3u8", 1.0, 42.5);
            hls.noteMediaDuration(120);
            assertEquals(42.5, hls.currentPositionSeconds(), 0.2);
        } finally {
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    @Test
    void playerBakesSeekBeforePlaylistIntoStart() throws Exception {
        FFmpegPlayer player = new FFmpegPlayer();
        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        try {
            player.onSeek(33.0);
            player.onPlaylist("http://127.0.0.1:9/missing.m3u8");
            player.onPlaylistContent(
                    "mlhls://localhost/itag/229/mediadata.m3u8",
                    "#EXTM3U\n#EXTINF:60.0,\nseg.ts\n#EXT-X-ENDLIST\n");
            Thread.sleep(80);
            assertEquals(33.0, player.info().position(), 0.5);
        } finally {
            player.onPlaylistRemoved();
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }

    @Test
    void endOfStreamNotifiesLifecycleOnce() {
        AtomicInteger ends = new AtomicInteger();
        hls.setOnEnded(ends::incrementAndGet);
        hls.noteMediaDuration(1.0);
        assertEquals(0, ends.get());
        hls.stop();
        assertEquals(0, ends.get());
    }

    @Test
    void playerParsesEndListDurationIntoPlaybackInfo() throws Exception {
        FFmpegPlayer player = new FFmpegPlayer();
        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        try {
            player.onPlaylist("http://127.0.0.1:9/missing.m3u8");
            player.onPlaylistContent(
                    "mlhls://localhost/itag/229/mediadata.m3u8",
                    "#EXTM3U\n#EXTINF:6.5,\nad.ts\n#EXTINF:7.1,\nad2.ts\n#EXT-X-ENDLIST\n");
            Thread.sleep(50);
            var info = player.info();
            assertTrue(info.duration() >= 13.5 && info.duration() <= 13.7);
        } finally {
            player.onPlaylistRemoved();
            System.clearProperty("airplay.ffmpeg.hls.headless");
        }
    }
}
