package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("bench")
class BenchPlaybackTest {

    @Test
    void feedsSyntheticVideoForThirtySecondsAndWritesMetrics(@TempDir Path tempDir) throws Exception {
        System.setProperty("airplay.harness.metrics", "true");
        RecordingConsumer consumer = new RecordingConsumer();
        try (PlaybackMetrics metrics = PlaybackMetrics.start("bench-synthetic-30s")) {
            consumer.onVideoFormat(new VideoStreamInfo("bench"));
            long deadline = System.nanoTime() + 30_000_000_000L;
            byte[] frame = PlaybackFixture.h264();
            while (System.nanoTime() < deadline) {
                consumer.onVideo(frame);
                Thread.sleep(33);
            }
            consumer.onVideoSrcDisconnect();
            metrics.finish(tempDir);
            assertTrue(consumer.videoFrames() > 100);
            assertTrue(tempDir.resolve("bench-synthetic-30s.json").toFile().isFile());
        } finally {
            System.clearProperty("airplay.harness.metrics");
        }
    }
}
