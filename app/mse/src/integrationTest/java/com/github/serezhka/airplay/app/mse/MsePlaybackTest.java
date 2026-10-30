package com.github.serezhka.airplay.app.mse;

import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
import com.github.serezhka.airplay.app.common.test.PlaybackLifecycleHarness;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
 * Production {@link MsePlayer} lifecycle (Chromium + hls.js + jmuxer).
 * Timeline: {@code app/common/src/testFixtures/docs/playback-lifecycle.md}.
 *
 * <p>CI: {@code -Dairplay.mse.headless=true} and a resolvable Chromium binary
 * ({@code -Dairplay.mse.browser=} or PATH). Local file HLS fixtures need
 * {@code -Dairplay.mse.allow-file-hls=true}.
 */
@Tag("mse")
class MsePlaybackTest {

    @BeforeAll
    static void ciDefaults() {
        System.setProperty("airplay.mse.headless",
                System.getProperty("airplay.mse.headless", "true"));
        System.setProperty("airplay.mse.allow-file-hls",
                System.getProperty("airplay.mse.allow-file-hls", "true"));
    }

    @AfterEach
    void clearEndedPin() {
        // no-op — each test closes its player
    }

    @Test
    @Timeout(90)
    void playsMirrorH264() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            player.onVideoFormat(new VideoStreamInfo("mse-mirror"));
            assertTrue(awaitBrowser(player), "Chromium page never connected");
            assertTrue(player.isMirrorActive(), "mirror not active");
            PlaybackFixture.play(player);
            assertTrue(player.isMirrorActive(), "mirror stopped while receiving H264");
        } finally {
            player.onVideoSrcDisconnect();
            player.close();
        }
        assertFalse(player.isMirrorActive());
    }

    @Test
    @Timeout(120)
    void reconnectsMirrorTwice() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            for (int i = 0; i < 2; i++) {
                player.onVideoFormat(new VideoStreamInfo("mse-reconnect-" + i));
                assertTrue(awaitBrowser(player), "browser not ready on pass " + i);
                assertTrue(player.isMirrorActive(), "mirror not active on pass " + i);
                PlaybackFixture.playVideo(player, 30);
                player.onVideoSrcDisconnect();
                assertFalse(player.isMirrorActive(), "mirror still active after disconnect " + i);
            }
        } finally {
            player.onVideoSrcDisconnect();
            player.close();
        }
    }

    @Test
    @Timeout(90)
    void mirrorWithAacEldAudioAndVolume() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            player.onVideoFormat(new VideoStreamInfo("mse-av"));
            assertTrue(awaitBrowser(player));
            player.onAudioFormat(PlaybackFixture.aacEldStreamInfo());
            player.onVolume(0.35);
            assertEquals(0.35, player.volume(), 1e-6);
            assertTrue(player.isAudioDecoderOpen(), "AAC-ELD decoder did not open");

            Thread video = new Thread(() -> {
                try {
                    PlaybackFixture.playVideo(player, 40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "mse-av-video");
            video.start();
            PlaybackFixture.playAacEld(player);
            video.join(15_000);

            assertTrue(player.isMirrorActive(), "mirror died during A/V feed");
            assertTrue(player.isAudioDecoderOpen(), "decoder closed during AAC-ELD feed");
            player.onVolume(0.0);
            assertEquals(0.0, player.volume(), 1e-6);
            player.onVolume(1.0);
        } finally {
            player.onAudioSrcDisconnect();
            player.onVideoSrcDisconnect();
            player.close();
        }
    }

    @Test
    @Timeout(60)
    void audioOnlyThenDisconnect() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.audioOnlyThenDisconnect(probe(player));
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(60)
    void alacOnlyThenDisconnect() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.alacOnlyThenDisconnect(probe(player));
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(60)
    void aacEldOnlyThenDisconnect() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.aacEldOnlyThenDisconnect(probe(player));
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(90)
    void audioReconnectWhileMirroring() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.audioReconnectWhileMirroring(probe(player));
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(30)
    void volumeClamp() {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.volumeClamp(probe(player));
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(60)
    void idempotentDoubleDisconnect() throws Exception {
        assumeBrowser();
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.idempotentDoubleDisconnect(probe(player));
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(120)
    void hlsVolumeStopMidPlayThenMirror() throws Exception {
        assumeBrowser();
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        Path hlsDir = Files.createTempDirectory("airplay-mse-hls-vol-");
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.hlsVolumeStopMidPlayThenMirror(
                    probe(player), hlsDir, "airplay.mse.headless");
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(120)
    void mirrorTearsActiveHls() throws Exception {
        assumeBrowser();
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        Path hlsDir = Files.createTempDirectory("airplay-mse-hls-tear-");
        MsePlayer player = new MsePlayer(30);
        try {
            PlaybackLifecycleHarness.mirrorTearsActiveHls(
                    probe(player), hlsDir, "airplay.mse.headless");
        } finally {
            player.close();
        }
    }

    @Test
    @Timeout(120)
    void hlsPauseResumeSeekThenBackToMirror() throws Exception {
        assumeBrowser();
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        Path hlsDir = Files.createTempDirectory("airplay-mse-hls-");
        MsePlayer player = new MsePlayer(30);
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
            assertTrue(awaitBrowser(player));
            PlaybackFixture.playVideo(player, 20);
            player.onVideoSrcDisconnect();

            PlaybackFixture.HlsFixture hls = PlaybackFixture.generateHlsPlaylist(hlsDir, 20);
            player.onPlaylist(hls.playlistUri());
            player.onPlaylistContent(hls.playlistUri(),
                    "#EXTM3U\n#EXTINF:20.0,\nsmoke.ts\n#EXT-X-ENDLIST\n");

            PlaybackFixture.awaitTrue(player::isHlsActive, 5_000, "HLS never became active");
            PlaybackFixture.awaitPosition(() -> player.info().position(), 0.3, 15_000);

            double beforePause = player.info().position();
            player.onPause();
            Thread.sleep(700);
            double duringPause = player.info().position();
            assertTrue(Math.abs(duringPause - beforePause) < 0.6,
                    "position should hold while paused: " + beforePause + " -> " + duringPause);
            assertEquals(0.0, player.info().rate(), 0.001);

            player.onResume();
            PlaybackFixture.awaitPosition(() -> player.info().position(), duringPause + 0.2, 15_000);
            assertEquals(1.0, player.info().rate(), 0.001);

            player.onSeek(3.0);
            PlaybackFixture.awaitNear(() -> player.info().position(), 3.0, 2.5, 10_000);

            player.onPlaylistRemoved();
            PlaybackFixture.awaitTrue(() -> !player.isHlsActive(), 10_000, "HLS still active after remove");

            player.onVideoFormat(new VideoStreamInfo("post-hls"));
            assertTrue(awaitBrowser(player));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(player.isMirrorActive(), "mirror did not restart after HLS");
        } finally {
            player.onPlaylistRemoved();
            player.onVideoSrcDisconnect();
            player.close();
            PlaybackLifecycleHarness.deleteQuietly(hlsDir);
        }
    }

    private static void assumeBrowser() {
        assumeTrue(MseBrowsers.resolveForPlayback() != null,
                "Chromium/Chrome required for MSE playback tests");
    }

    private static boolean awaitBrowser(MsePlayer player) throws InterruptedException {
        // Windows GHA Chrome cold-start + control WS can exceed 20s under load.
        long waitMs = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? 45_000 : 20_000;
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            if (player.isBrowserReady() || player.isMirrorActive() || player.isHlsActive()) {
                // Mirror/HLS start ensures browser; give control WS a moment after first format.
                if (player.isBrowserReady()) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return player.isBrowserReady();
    }

    private static PlaybackLifecycleHarness.Probe probe(MsePlayer player) {
        return new PlaybackLifecycleHarness.Probe() {
            @Override
            public Playback player() {
                return player;
            }

            @Override
            public BooleanSupplier videoAlive() {
                return player::isMirrorActive;
            }

            @Override
            public BooleanSupplier audioAlive() {
                return player::isAudioDecoderOpen;
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
                return hls.playlistUri();
            }
        };
    }
}
