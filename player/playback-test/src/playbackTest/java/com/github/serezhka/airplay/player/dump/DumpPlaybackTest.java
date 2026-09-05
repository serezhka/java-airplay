package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dump")
class DumpPlaybackTest {

    @Test
    void recordsSyntheticAirPlayStreamBesideAPlayer(@TempDir Path tempDir) throws Exception {
        DumpConfig config = new DumpConfig();
        config.setDirectory(tempDir.toString());
        config.setProtocol(false);
        DumpPlayer dump = new DumpPlayer(config);
        DumpingAirPlayConsumer consumer = new DumpingAirPlayConsumer(new NoopPlayer(), dump);

        consumer.onVideoFormat(new VideoStreamInfo("playback-test"));
        PlaybackFixture.play(consumer);
        consumer.onVideoSrcDisconnect();
        consumer.close();

        Path dumped = dump.sessionDirectory().resolve("media").resolve("video-001.h264");
        assertTrue(Files.isRegularFile(dumped));
        assertArrayEquals(PlaybackFixture.h264(), Files.readAllBytes(dumped));
    }

    private static final class NoopPlayer implements AirPlayConsumer {
        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        }

        @Override
        public void onVideo(byte[] bytes) {
        }

        @Override
        public void onVideoSrcDisconnect() {
        }

        @Override
        public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        }

        @Override
        public void onAudio(byte[] bytes) {
        }

        @Override
        public void onAudioSrcDisconnect() {
        }
    }
}
