package com.github.serezhka.airplay.player.dump;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DumpingAirPlayConsumerTest {

    @TempDir
    Path tempDir;

    @Test
    void playsThenDumpsVideo() throws Exception {
        RecordingConsumer player = new RecordingConsumer();
        DumpPlayer dump = dumpPlayer();
        DumpingAirPlayConsumer consumer = new DumpingAirPlayConsumer(player, dump);
        byte[] frame = {0, 0, 0, 1, 0x65};

        consumer.onVideoFormat(new VideoStreamInfo("conn-1"));
        consumer.onVideo(frame);
        consumer.onVideoSrcDisconnect();
        consumer.close();

        assertEquals(1, player.videoFrames.size());
        assertArrayEquals(frame, player.videoFrames.get(0));
        Path dumped = dump.sessionDirectory().resolve("media").resolve("video-001.h264");
        assertArrayEquals(frame, Files.readAllBytes(dumped));
    }

    @Test
    void dumpFailureDoesNotFailPlayer() {
        RecordingConsumer player = new RecordingConsumer();
        DumpPlayer dump = new DumpPlayer(config(), Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC)) {
            @Override
            public synchronized void onVideo(byte[] bytes) {
                throw new IllegalStateException("dump failed");
            }
        };
        DumpingAirPlayConsumer consumer = new DumpingAirPlayConsumer(player, dump);
        byte[] frame = {1, 2, 3};

        assertDoesNotThrow(() -> {
            consumer.onVideoFormat(new VideoStreamInfo("conn-1"));
            consumer.onVideo(frame);
        });
        assertEquals(1, player.videoFrames.size());
        assertArrayEquals(frame, player.videoFrames.get(0));
    }

    @Test
    void playbackInfoComesFromPlayer() {
        RecordingConsumer player = new RecordingConsumer();
        player.playbackInfo = new AirPlayConsumer.PlaybackInfo(10, 3);
        DumpingAirPlayConsumer consumer = new DumpingAirPlayConsumer(player, dumpPlayer());

        assertEquals(new AirPlayConsumer.PlaybackInfo(10, 3), consumer.playbackInfo());
    }

    private DumpPlayer dumpPlayer() {
        return new DumpPlayer(config(), Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC));
    }

    private DumpConfig config() {
        DumpConfig config = new DumpConfig();
        config.setDirectory(tempDir.toString());
        config.setProtocol(false);
        return config;
    }

    private static final class RecordingConsumer implements AirPlayConsumer {
        private final List<byte[]> videoFrames = new ArrayList<>();
        private AirPlayConsumer.PlaybackInfo playbackInfo = new AirPlayConsumer.PlaybackInfo(0, 0);

        @Override
        public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        }

        @Override
        public void onVideo(byte[] bytes) {
            videoFrames.add(bytes);
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

        @Override
        public AirPlayConsumer.PlaybackInfo playbackInfo() {
            return playbackInfo;
        }
    }
}
