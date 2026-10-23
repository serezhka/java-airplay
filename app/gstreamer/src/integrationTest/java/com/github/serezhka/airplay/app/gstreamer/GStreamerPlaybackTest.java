package com.github.serezhka.airplay.app.gstreamer;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
import com.github.serezhka.airplay.app.common.test.PlaybackLifecycleHarness;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import org.junit.jupiter.api.AfterAll;
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
 * Production {@link GstPlayer} lifecycle. Timeline diagram:
 * {@code app/common/src/testFixtures/docs/playback-lifecycle.md}.
 */
@Tag("gstreamer")
class GStreamerPlaybackTest {

    /**
     * Native GStreamer threads can keep the Gradle test worker alive after a green
     * suite (especially Windows CI). {@code Gst.deinit()} also hangs — schedule a
     * delayed hard exit only when the CI flag is set so local runs stay interactive.
     */
    @AfterAll
    static void haltCiWorkerIfStuck() {
        if (!Boolean.parseBoolean(System.getProperty("airplay.gst.ci.haltAfterSuite", "false"))
                && !"true".equalsIgnoreCase(System.getenv("AIRPLAY_GST_CI_HALT"))) {
            return;
        }
        Thread halt = new Thread(() -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Runtime.getRuntime().halt(0);
        }, "airplay-gst-ci-halt");
        halt.setDaemon(true);
        halt.start();
    }

    @Test
    @Timeout(60)
    void playsMirrorH264() throws Exception {
        GstPlayer player = new GstPlayer();
        if (!forceAppsink()) {
            assertTrue(player.isFullscreenConfigured(), "Selected GStreamer sink is not configured for fullscreen");
        }
        try {
            player.onVideoFormat(new VideoStreamInfo("playback-test"));
            PlaybackFixture.play(player);
            assertTrue(player.isVideoPipelinePlaying(), "GStreamer pipeline stopped while receiving H264");
        } finally {
            player.onVideoSrcDisconnect();
        }
        assertFalse(player.isVideoPipelinePlaying(), "video pipeline should stop after disconnect");
    }

    @Test
    @Timeout(90)
    void reconnectsMirrorTwice() throws Exception {
        GstPlayer player = new GstPlayer();
        try {
            for (int i = 0; i < 2; i++) {
                player.onVideoFormat(new VideoStreamInfo("reconnect-" + i));
                PlaybackFixture.playVideo(player, 30);
                assertTrue(player.isVideoPipelinePlaying(), "pipeline dead on reconnect pass " + i);
                player.onVideoSrcDisconnect();
                assertFalse(player.isVideoPipelinePlaying(), "pipeline still playing after disconnect " + i);
            }
        } finally {
            player.onVideoSrcDisconnect();
        }
    }

    @Test
    @Timeout(90)
    void mirrorWithAacLcAudioAndVolume() throws Exception {
        GstPlayer player = new GstPlayer();
        try {
            player.onVideoFormat(new VideoStreamInfo("av-test"));
            player.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
            player.onVolume(0.4);
            assertEquals(0.4, player.volume(), 1e-6);

            Thread video = new Thread(() -> {
                try {
                    PlaybackFixture.playVideo(player, 40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "gst-av-video");
            video.start();
            PlaybackFixture.playAacLc(player);
            video.join(10_000);

            assertTrue(player.isVideoPipelinePlaying(), "video pipeline died during A/V feed");
            assertTrue(player.isAudioPipelinePlaying(), "AAC-LC pipeline did not stay PLAYING");

            player.onVolume(0.0);
            assertEquals(0.0, player.volume(), 1e-6);
            player.onVolume(1.0);
        } finally {
            player.onAudioSrcDisconnect();
            player.onVideoSrcDisconnect();
        }
        assertFalse(player.isAudioPipelinePlaying());
    }

    @Test
    @Timeout(60)
    void audioOnlyThenDisconnect() throws Exception {
        PlaybackLifecycleHarness.audioOnlyThenDisconnect(probe(new GstPlayer()));
    }

    @Test
    @Timeout(60)
    void alacOnlyThenDisconnect() throws Exception {
        PlaybackLifecycleHarness.alacOnlyThenDisconnect(probe(new GstPlayer()));
    }

    @Test
    @Timeout(60)
    void aacEldOnlyThenDisconnect() throws Exception {
        PlaybackLifecycleHarness.aacEldOnlyThenDisconnect(probe(new GstPlayer()));
    }

    @Test
    @Timeout(90)
    void audioReconnectWhileMirroring() throws Exception {
        PlaybackLifecycleHarness.audioReconnectWhileMirroring(probe(new GstPlayer()));
    }

    @Test
    @Timeout(30)
    void volumeClamp() {
        PlaybackLifecycleHarness.volumeClamp(probe(new GstPlayer()));
    }

    @Test
    @Timeout(60)
    void idempotentDoubleDisconnect() throws Exception {
        PlaybackLifecycleHarness.idempotentDoubleDisconnect(probe(new GstPlayer()));
    }

    @Test
    @Timeout(120)
    void hlsVolumeStopMidPlayThenMirror() throws Exception {
        Path hlsDir = Files.createTempDirectory("airplay-gst-hls-vol-");
        PlaybackLifecycleHarness.hlsVolumeStopMidPlayThenMirror(
                probe(new GstPlayer()), hlsDir, "airplay.gst.hls.headless");
    }

    @Test
    @Timeout(120)
    void mirrorTearsActiveHls() throws Exception {
        Path hlsDir = Files.createTempDirectory("airplay-gst-hls-tear-");
        PlaybackLifecycleHarness.mirrorTearsActiveHls(
                probe(new GstPlayer()), hlsDir, "airplay.gst.hls.headless");
    }

    @Test
    @Timeout(120)
    void hlsPauseResumeSeekThenBackToMirror() throws Exception {
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        System.setProperty("airplay.gst.hls.headless", "true");
        Path hlsDir = Files.createTempDirectory("airplay-gst-hls-");
        GstPlayer player = new GstPlayer();
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
            assertTrue(player.isVideoPipelinePlaying());
            player.onVideoSrcDisconnect();

            // Long enough that pause/seek finish before ENDLIST. Feed .ts (same playbin path);
            // local m3u8 demux is flaky for first-picture probes on some Linux runners.
            PlaybackFixture.HlsFixture hls = PlaybackFixture.generateHlsPlaylist(hlsDir, 20);
            player.onPlaylist(hls.mediaUri());
            player.onPlaylistContent(hls.playlistUri(),
                    "#EXTM3U\n#EXTINF:20.0,\nsmoke.ts\n#EXT-X-ENDLIST\n");

            boolean presented = presentedLatch.await(20, TimeUnit.SECONDS);
            PlaybackFixture.awaitTrue(player::isHlsActive, 15_000, "HLS never became active");
            PlaybackFixture.awaitPosition(() -> player.info().position(), 0.3, 20_000);
            assertTrue(presented || player.info().position() >= 0.3,
                    "onPresented never fired and clock did not advance");

            double beforePause = player.info().position();
            assumeTrue(beforePause < 10.0, "clock already near EOS before pause; fixture too short?");
            player.onPause();
            Thread.sleep(700);
            double duringPause = player.info().position();
            assertTrue(Math.abs(duringPause - beforePause) < 0.5,
                    "position should hold while paused: " + beforePause + " -> " + duringPause);

            player.onResume();
            PlaybackFixture.awaitPosition(() -> player.info().position(), duringPause + 0.25, 15_000);

            player.onSeek(3.0);
            PlaybackFixture.awaitNear(() -> player.info().position(), 3.0, 2.0, 20_000);

            player.onPlaylistRemoved();
            PlaybackFixture.awaitTrue(() -> !player.isHlsActive(), 10_000, "HLS still active after remove");
            Thread.sleep(300);

            player.onVideoFormat(new VideoStreamInfo("post-hls"));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(player.isVideoPipelinePlaying(), "mirror did not restart after HLS");
        } finally {
            player.onPlaylistRemoved();
            player.onVideoSrcDisconnect();
            System.clearProperty("airplay.gst.hls.headless");
            PlaybackLifecycleHarness.deleteQuietly(hlsDir);
        }
    }

    private static PlaybackLifecycleHarness.Probe probe(GstPlayer player) {
        return new PlaybackLifecycleHarness.Probe() {
            @Override
            public Playback player() {
                return player;
            }

            @Override
            public BooleanSupplier videoAlive() {
                return player::isVideoPipelinePlaying;
            }

            @Override
            public BooleanSupplier audioAlive() {
                return player::isAudioPipelinePlaying;
            }

            @Override
            public BooleanSupplier hlsActive() {
                return player::isHlsActive;
            }

            @Override
            public DoubleSupplier position() {
                return () -> player.info().position();
            }
        };
    }

    private static boolean forceAppsink() {
        return Boolean.parseBoolean(System.getProperty("airplay.gst.appsink", "false"))
                || Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_GST_APPSINK", "false"));
    }
}
