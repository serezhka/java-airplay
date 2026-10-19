package com.github.serezhka.airplay.app.gstreamer;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the production {@link GstPlayer} path (JNI + appsrc), not a CLI parse-only stand-in.
 * Headless knobs ({@code airplay.gst.appsink}, {@code airplay.gst.hls.headless}) come from CI only.
 */
@Tag("gstreamer")
class GStreamerPlaybackTest {

    @TempDir
    Path temp;

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
    @Timeout(120)
    void hlsPauseResumeSeekThenBackToMirror() throws Exception {
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        System.setProperty("airplay.gst.hls.headless", "true");
        GstPlayer player = new GstPlayer();
        CountDownLatch presentedLatch = new CountDownLatch(1);
        player.setObserver(new com.github.serezhka.airplay.server.Playback.Observer() {
            @Override
            public void onEnded() {
            }

            @Override
            public void onPresented() {
                presentedLatch.countDown();
            }
        });

        try {
            // 1) Mirror
            player.onVideoFormat(new VideoStreamInfo("pre-hls"));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(player.isVideoPipelinePlaying());
            player.onVideoSrcDisconnect();

            // 2) HLS VOD (pause / resume / scrub)
            String uri = PlaybackFixture.generateHlsPlaylist(temp.resolve("hls"), 8);
            player.onPlaylist(uri);
            player.onPlaylistContent(uri, "#EXTM3U\n#EXTINF:8.0,\nsmoke.ts\n#EXT-X-ENDLIST\n");

            presentedLatch.await(20, TimeUnit.SECONDS);
            PlaybackFixture.awaitTrue(player::isHlsActive, 15_000, "HLS never became active");
            PlaybackFixture.awaitPosition(() -> player.info().position(), 0.3, 20_000);

            double beforePause = player.info().position();
            player.onPause();
            Thread.sleep(700);
            double duringPause = player.info().position();
            assertTrue(Math.abs(duringPause - beforePause) < 0.5,
                    "position should hold while paused: " + beforePause + " -> " + duringPause);
            assertEquals(0.0, player.info().rate(), 1e-6);

            player.onResume();
            PlaybackFixture.awaitPosition(() -> player.info().position(), duringPause + 0.25, 15_000);

            double beforeSeek = player.info().position();
            double target = Math.max(1.0, beforeSeek + 1.5);
            player.onSeek(target);
            PlaybackFixture.awaitNear(() -> player.info().position(), target, 2.0, 20_000);

            player.onPlaylistRemoved();
            PlaybackFixture.awaitTrue(() -> !player.isHlsActive(), 10_000, "HLS still active after remove");

            // 3) Back to mirror
            player.onVideoFormat(new VideoStreamInfo("post-hls"));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(player.isVideoPipelinePlaying(), "mirror did not restart after HLS");
        } finally {
            player.onPlaylistRemoved();
            player.onVideoSrcDisconnect();
            System.clearProperty("airplay.gst.hls.headless");
        }
    }

    private static boolean forceAppsink() {
        return Boolean.parseBoolean(System.getProperty("airplay.gst.appsink", "false"))
                || Boolean.parseBoolean(System.getenv().getOrDefault("AIRPLAY_GST_APPSINK", "false"));
    }
}
