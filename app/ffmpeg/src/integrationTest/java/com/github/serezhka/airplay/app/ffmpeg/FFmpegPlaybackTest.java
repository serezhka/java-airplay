package com.github.serezhka.airplay.app.ffmpeg;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
import com.github.serezhka.airplay.app.common.test.PlaybackLifecycleHarness;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Production {@link FFmpegPlayer} lifecycle. Timeline diagram:
 * {@code app/common/src/testFixtures/docs/playback-lifecycle.md}.
 */
@Tag("ffmpeg")
class FFmpegPlaybackTest {

    @Test
    @Timeout(60)
    void playsMirrorH264() throws Exception {
        FFmpegPlayer player = new FFmpegPlayer();
        try {
            player.onVideoFormat(new VideoStreamInfo("playback-test"));
            assertTrue(player.isVideoProcessAlive(), "ffplay did not start");
            PlaybackFixture.play(player);
            assertTrue(player.isVideoProcessAlive(), "ffplay stopped while receiving H264");
        } finally {
            player.onVideoSrcDisconnect();
        }
        assertFalse(player.isVideoProcessAlive(), "ffplay was not stopped after disconnect");
    }

    @Test
    @Timeout(90)
    void reconnectsMirrorTwice() throws Exception {
        FFmpegPlayer player = new FFmpegPlayer();
        try {
            for (int i = 0; i < 2; i++) {
                player.onVideoFormat(new VideoStreamInfo("reconnect-" + i));
                assertTrue(player.isVideoProcessAlive(), "ffplay did not start on pass " + i);
                PlaybackFixture.playVideo(player, 30);
                assertTrue(player.isVideoProcessAlive(), "ffplay died on reconnect pass " + i);
                player.onVideoSrcDisconnect();
                assertFalse(player.isVideoProcessAlive(), "ffplay still alive after disconnect " + i);
            }
        } finally {
            player.onVideoSrcDisconnect();
        }
    }

    @Test
    @Timeout(90)
    void mirrorWithAacLcAudioAndVolume() throws Exception {
        FFmpegPlayer player = new FFmpegPlayer();
        try {
            player.onVideoFormat(new VideoStreamInfo("av-test"));
            player.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
            player.onVolume(0.35);
            assertEquals(0.35, player.volume(), 1e-6);
            assertTrue(player.isAudioSinkAlive(), "PCM sink did not start for AAC-LC");

            Thread video = new Thread(() -> {
                try {
                    PlaybackFixture.playVideo(player, 40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "ffmpeg-av-video");
            video.start();
            PlaybackFixture.playAacLc(player);
            video.join(10_000);

            assertTrue(player.isVideoProcessAlive(), "ffplay died during A/V feed");
            assertTrue(player.isAudioSinkAlive(), "PCM sink died during AAC-LC feed");

            player.onVolume(0.0);
            assertEquals(0.0, player.volume(), 1e-6);
            player.onVolume(1.0);
        } finally {
            player.onAudioSrcDisconnect();
            player.onVideoSrcDisconnect();
        }
        assertFalse(player.isAudioSinkAlive());
    }

    @Test
    @Timeout(60)
    void audioOnlyThenDisconnect() throws Exception {
        PlaybackLifecycleHarness.audioOnlyThenDisconnect(probe(new FFmpegPlayer()));
    }

    @Test
    @Timeout(60)
    void alacOnlyThenDisconnect() throws Exception {
        PlaybackLifecycleHarness.alacOnlyThenDisconnect(probe(new FFmpegPlayer()));
    }

    @Test
    @Timeout(60)
    void aacEldOnlyThenDisconnect() throws Exception {
        PlaybackLifecycleHarness.aacEldOnlyThenDisconnect(probe(new FFmpegPlayer()));
    }

    @Test
    @Timeout(90)
    void audioReconnectWhileMirroring() throws Exception {
        PlaybackLifecycleHarness.audioReconnectWhileMirroring(probe(new FFmpegPlayer()));
    }

    @Test
    @Timeout(30)
    void volumeClamp() {
        PlaybackLifecycleHarness.volumeClamp(probe(new FFmpegPlayer()));
    }

    @Test
    @Timeout(60)
    void idempotentDoubleDisconnect() throws Exception {
        PlaybackLifecycleHarness.idempotentDoubleDisconnect(probe(new FFmpegPlayer()));
    }

    @Test
    @Timeout(120)
    void hlsVolumeStopMidPlayThenMirror() throws Exception {
        Path hlsDir = Files.createTempDirectory("airplay-ffmpeg-hls-vol-");
        PlaybackLifecycleHarness.hlsVolumeStopMidPlayThenMirror(
                probe(new FFmpegPlayer()), hlsDir, "airplay.ffmpeg.hls.headless");
    }

    @Test
    @Timeout(120)
    void mirrorTearsActiveHls() throws Exception {
        Path hlsDir = Files.createTempDirectory("airplay-ffmpeg-hls-tear-");
        PlaybackLifecycleHarness.mirrorTearsActiveHls(
                probe(new FFmpegPlayer()), hlsDir, "airplay.ffmpeg.hls.headless");
    }

    @Test
    @Timeout(120)
    void hlsPauseResumeSeekThenBackToMirror() throws Exception {
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        System.setProperty("airplay.ffmpeg.hls.headless", "true");
        Path hlsDir = Files.createTempDirectory("airplay-ffmpeg-hls-");
        FFmpegPlayer player = new FFmpegPlayer();
        CountDownLatch presentedLatch = new CountDownLatch(1);
        player.setObserver(new Playback.Observer() {
            @Override
            public void onEnded() {
            }

            @Override
            public void onPresented() {
                presentedLatch.countDown();
            }
        });

        try {
            player.onVideoFormat(new VideoStreamInfo("pre-hls"));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(player.isVideoProcessAlive());
            player.onVideoSrcDisconnect();

            // 20s media so pause/seek do not race ENDLIST. Feed the .ts — same
            // FfmpegHlsPipeline path as a playlist; local m3u8 demux is flaky on some CI ffplays.
            // Windows ffplay often fails to open file:// URIs; pass a native path there.
            PlaybackFixture.HlsFixture hls = PlaybackFixture.generateHlsPlaylist(hlsDir, 20);
            player.onPlaylist(PlaybackLifecycleHarness.ffplayMediaArg(hls.mediaUri()));
            player.onPlaylistContent(hls.playlistUri(),
                    "#EXTM3U\n#EXTINF:20.0,\nsmoke.ts\n#EXT-X-ENDLIST\n");

            boolean presented = presentedLatch.await(25, TimeUnit.SECONDS);
            PlaybackFixture.awaitTrue(player::isHlsActive, 5_000, "HLS never became active");
            PlaybackFixture.awaitPosition(() -> player.info().position(), 0.3, 25_000);
            assertTrue(presented || player.info().position() >= 0.3,
                    "onPresented never fired and clock did not advance");

            double beforePause = player.info().position();
            player.onPause();
            Thread.sleep(700);
            double duringPause = player.info().position();
            assertTrue(Math.abs(duringPause - beforePause) < 0.6,
                    "position should hold while paused: " + beforePause + " -> " + duringPause);

            player.onResume();
            PlaybackFixture.awaitPosition(() -> player.info().position(), duringPause + 0.2, 20_000);

            player.onSeek(3.0);
            PlaybackFixture.awaitNear(() -> player.info().position(), 3.0, 2.5, 25_000);

            player.onPlaylistRemoved();
            PlaybackFixture.awaitTrue(() -> !player.isHlsActive(), 10_000, "HLS still active after remove");
            Thread.sleep(300);

            player.onVideoFormat(new VideoStreamInfo("post-hls"));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(player.isVideoProcessAlive(), "mirror did not restart after HLS");
        } finally {
            player.onPlaylistRemoved();
            player.onVideoSrcDisconnect();
            System.clearProperty("airplay.ffmpeg.hls.headless");
            PlaybackLifecycleHarness.deleteQuietly(hlsDir);
        }
    }

    private static PlaybackLifecycleHarness.Probe probe(FFmpegPlayer player) {
        return new PlaybackLifecycleHarness.Probe() {
            @Override
            public Playback player() {
                return player;
            }

            @Override
            public BooleanSupplier videoAlive() {
                return player::isVideoProcessAlive;
            }

            @Override
            public BooleanSupplier audioAlive() {
                return player::isAudioSinkAlive;
            }

            @Override
            public BooleanSupplier hlsActive() {
                return player::isHlsActive;
            }

            @Override
            public DoubleSupplier position() {
                return () -> player.info().position();
            }

            @Override
            public String playlistArg(PlaybackFixture.HlsFixture hls) {
                return PlaybackLifecycleHarness.ffplayMediaArg(hls.mediaUri());
            }
        };
    }
}
