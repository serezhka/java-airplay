package com.github.serezhka.airplay.player.h264dump;

import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Tag("h264-dump")
class H264DumpPlaybackTest {

    @Test
    void writesSyntheticAirPlayStreamWithoutChanges(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("dump.h264");
        H264Dump player = new H264Dump(output);

        player.onVideoFormat(new VideoStreamInfo("playback-test"));
        PlaybackFixture.play(player);
        player.onVideoSrcDisconnect();
        player.save();

        assertArrayEquals(PlaybackFixture.h264(), Files.readAllBytes(output));
    }
}
