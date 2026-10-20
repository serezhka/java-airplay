package com.github.serezhka.airplay.app.common.test;

import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared must-have lifecycle scenarios for production {@link Playback} players.
 * Timeline: {@code app/common/src/testFixtures/docs/playback-lifecycle.md}.
 */
public final class PlaybackLifecycleHarness {

    /** Probe hooks that differ between GStreamer and FFmpeg sinks. */
    public interface Probe {
        Playback player();

        BooleanSupplier videoAlive();

        BooleanSupplier audioAlive();

        BooleanSupplier hlsActive();

        DoubleSupplier position();

        /** URI/path argument {@link Playback#onPlaylist(String)} accepts on this OS. */
        default String playlistArg(PlaybackFixture.HlsFixture hls) {
            return hls.mediaUri();
        }
    }

    /** Case E — audio-only (Music) then disconnect. */
    public static void audioOnlyThenDisconnect(Probe probe) throws Exception {
        Playback player = probe.player();
        try {
            player.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
            player.onVolume(0.55);
            assertEquals(0.55, player.volume(), 1e-6);
            PlaybackFixture.playAacLc(player);
            assertTrue(probe.audioAlive().getAsBoolean(), "audio sink never became alive");
        } finally {
            player.onAudioSrcDisconnect();
        }
        assertFalse(probe.audioAlive().getAsBoolean(), "audio still alive after disconnect");
    }

    /** Case F — drop audio mid-mirror, reconnect AAC-LC, picture stays up. */
    public static void audioReconnectWhileMirroring(Probe probe) throws Exception {
        Playback player = probe.player();
        try {
            player.onVideoFormat(new VideoStreamInfo("audio-reconnect"));
            player.onAudioFormat(PlaybackFixture.aacLcStreamInfo());

            Thread video = feedVideoAsync(player, 50, "av-reconnect-video");
            PlaybackFixture.playAacLc(player);
            assertTrue(probe.videoAlive().getAsBoolean(), "video died while first audio burst ran");
            assertTrue(probe.audioAlive().getAsBoolean(), "first audio sink never alive");

            player.onAudioSrcDisconnect();
            assertFalse(probe.audioAlive().getAsBoolean(), "audio still alive after disconnect");
            assertTrue(probe.videoAlive().getAsBoolean(), "video died when audio disconnected");

            player.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
            PlaybackFixture.playAacLc(player);
            assertTrue(probe.audioAlive().getAsBoolean(), "audio did not restart after reconnect");
            assertTrue(probe.videoAlive().getAsBoolean(), "video died during audio reconnect");
            video.join(15_000);
        } finally {
            player.onAudioSrcDisconnect();
            player.onVideoSrcDisconnect();
        }
        assertFalse(probe.audioAlive().getAsBoolean());
        assertFalse(probe.videoAlive().getAsBoolean());
    }

    /** Case G — volume clamp to [0, 1]. */
    public static void volumeClamp(Probe probe) {
        Playback player = probe.player();
        player.onVolume(-0.5);
        assertEquals(0.0, player.volume(), 1e-6);
        player.onVolume(1.5);
        assertEquals(1.0, player.volume(), 1e-6);
        player.onVolume(0.25);
        assertEquals(0.25, player.volume(), 1e-6);
    }

    /** Case H — double disconnect must not throw / leave sinks resurrected. */
    public static void idempotentDoubleDisconnect(Probe probe) throws Exception {
        Playback player = probe.player();
        player.onVideoFormat(new VideoStreamInfo("double-disconnect"));
        player.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
        PlaybackFixture.playVideo(player, 10);
        player.onVideoSrcDisconnect();
        player.onVideoSrcDisconnect();
        player.onAudioSrcDisconnect();
        player.onAudioSrcDisconnect();
        assertFalse(probe.videoAlive().getAsBoolean());
        assertFalse(probe.audioAlive().getAsBoolean());
    }

    /**
     * Case I — HLS volume while playing, stop mid-item, return to mirror.
     * Shorter than Case D (no pause/seek).
     */
    public static void hlsVolumeStopMidPlayThenMirror(Probe probe, Path hlsDir, String headlessProperty)
            throws Exception {
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        System.setProperty(headlessProperty, "true");
        Playback player = probe.player();
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
            PlaybackFixture.HlsFixture hls = PlaybackFixture.generateHlsPlaylist(hlsDir, 15);
            player.onPlaylist(probe.playlistArg(hls));
            player.onPlaylistContent(hls.playlistUri(),
                    "#EXTM3U\n#EXTINF:15.0,\nsmoke.ts\n#EXT-X-ENDLIST\n");

            boolean presented = presentedLatch.await(20, TimeUnit.SECONDS);
            PlaybackFixture.awaitTrue(probe.hlsActive(), 10_000, "HLS never became active");
            PlaybackFixture.awaitPosition(probe.position(), 0.3, 20_000);
            assertTrue(presented || probe.position().getAsDouble() >= 0.3,
                    "onPresented never fired and clock did not advance");

            player.onVolume(0.2);
            assertEquals(0.2, player.volume(), 1e-6);
            player.onVolume(1.0);
            assertEquals(1.0, player.volume(), 1e-6);

            player.onPlaylistRemoved();
            PlaybackFixture.awaitTrue(() -> !probe.hlsActive().getAsBoolean(), 10_000,
                    "HLS still active after remove");
            assertEquals(0.0, player.info().position(), 0.01);
            assertEquals(0.0, player.info().duration(), 0.01);

            player.onVideoFormat(new VideoStreamInfo("post-hls-midstop"));
            PlaybackFixture.playVideo(player, 20);
            assertTrue(probe.videoAlive().getAsBoolean(), "mirror did not start after mid-play HLS stop");
        } finally {
            player.onPlaylistRemoved();
            player.onVideoSrcDisconnect();
            System.clearProperty(headlessProperty);
            deleteQuietly(hlsDir);
        }
    }

    /**
     * Case J — starting mirror while HLS is active must tear the playlist sink
     * (shared display / GPU).
     */
    public static void mirrorTearsActiveHls(Probe probe, Path hlsDir, String headlessProperty)
            throws Exception {
        assumeTrue(PlaybackFixture.ffmpegAvailable(), "ffmpeg needed to build HLS fixture");
        System.setProperty(headlessProperty, "true");
        Playback player = probe.player();
        try {
            PlaybackFixture.HlsFixture hls = PlaybackFixture.generateHlsPlaylist(hlsDir, 12);
            player.onPlaylist(probe.playlistArg(hls));
            player.onPlaylistContent(hls.playlistUri(),
                    "#EXTM3U\n#EXTINF:12.0,\nsmoke.ts\n#EXT-X-ENDLIST\n");
            PlaybackFixture.awaitTrue(probe.hlsActive(), 10_000, "HLS never became active");
            PlaybackFixture.awaitPosition(probe.position(), 0.2, 20_000);

            player.onVideoFormat(new VideoStreamInfo("tear-hls"));
            PlaybackFixture.awaitTrue(() -> !probe.hlsActive().getAsBoolean(), 10_000,
                    "HLS still active after onVideoFormat");
            PlaybackFixture.playVideo(player, 15);
            assertTrue(probe.videoAlive().getAsBoolean(), "mirror never started after tearing HLS");
        } finally {
            player.onPlaylistRemoved();
            player.onVideoSrcDisconnect();
            System.clearProperty(headlessProperty);
            deleteQuietly(hlsDir);
        }
    }

    /** Native path on Windows; {@code file:} URI elsewhere (ffplay). */
    public static String ffplayMediaArg(String mediaUri) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return mediaUri;
        }
        try {
            return Path.of(URI.create(mediaUri)).toAbsolutePath().toString();
        } catch (Exception e) {
            return mediaUri;
        }
    }

    public static void deleteQuietly(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private static Thread feedVideoAsync(Playback player, int frames, String name) {
        Thread video = new Thread(() -> {
            try {
                PlaybackFixture.playVideo(player, frames);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name);
        video.start();
        return video;
    }

    private PlaybackLifecycleHarness() {
    }
}
