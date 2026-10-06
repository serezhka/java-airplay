package com.github.serezhka.airplay.player.harness;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.player.test.PlaybackFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("loopback")
class LoopbackMediaCallbackTest {

    @Test
    void videoOnlySyntheticFeed() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.onVideoFormat(new VideoStreamInfo("loopback-video"));
        PlaybackFixture.play(consumer);
        consumer.onVideoSrcDisconnect();
        assertTrue(consumer.videoFrames() > 0);
        assertEquals(0, consumer.audioFrames());
    }

    @Test
    void audioOnlySyntheticFeed() {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.onAudioFormat(new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_LC_44100_2)
                .samplesPerFrame(1024)
                .build());
        byte[] frame = new byte[256];
        for (int i = 0; i < 30; i++) {
            consumer.onAudio(frame);
        }
        consumer.onAudioSrcDisconnect();
        assertEquals(30, consumer.audioFrames());
        assertEquals(0, consumer.videoFrames());
    }

    @Test
    void audioAndVideoTogether() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.onVideoFormat(new VideoStreamInfo("loopback-av"));
        consumer.onAudioFormat(new AudioStreamInfo.AudioStreamInfoBuilder()
                .compressionType(AudioStreamInfo.CompressionType.AAC)
                .audioFormat(AudioStreamInfo.AudioFormat.AAC_LC_44100_2)
                .samplesPerFrame(1024)
                .build());
        PlaybackFixture.play(consumer);
        consumer.onAudio(new byte[128]);
        consumer.onAudio(new byte[128]);
        consumer.onVideoSrcDisconnect();
        consumer.onAudioSrcDisconnect();
        assertTrue(consumer.videoFrames() > 0);
        assertTrue(consumer.audioFrames() >= 2);
    }
}
