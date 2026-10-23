package com.github.serezhka.airplay.app.common.harness;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.app.common.test.PlaybackFixture;
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
        assertEquals(1, consumer.videoDisconnects());
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
        assertEquals(1, consumer.audioDisconnects());
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

    @Test
    void audioDisconnectWhileVideoContinuesThenReconnect() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.onVideoFormat(new VideoStreamInfo("loopback-audio-reconnect"));
        consumer.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
        PlaybackFixture.playVideo(consumer, 10);
        consumer.onAudio(new byte[64]);
        consumer.onAudioSrcDisconnect();
        assertEquals(1, consumer.audioDisconnects());

        consumer.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
        consumer.onAudio(new byte[64]);
        consumer.onAudio(new byte[64]);
        PlaybackFixture.playVideo(consumer, 5);
        consumer.onAudioSrcDisconnect();
        consumer.onVideoSrcDisconnect();

        assertEquals(2, consumer.audioDisconnects());
        assertEquals(1, consumer.videoDisconnects());
        assertTrue(consumer.videoFrames() > 0);
        assertTrue(consumer.audioFrames() >= 3);
        assertTrue(consumer.eventsContainInOrder(
                "videoFormat", "audioFormat", "audioDisconnect", "audioFormat", "audioDisconnect",
                "videoDisconnect"));
    }

    @Test
    void mirrorReconnectTwice() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        for (int i = 0; i < 2; i++) {
            consumer.onVideoFormat(new VideoStreamInfo("reconnect-" + i));
            PlaybackFixture.playVideo(consumer, 8);
            consumer.onVideoSrcDisconnect();
        }
        assertEquals(2, consumer.videoDisconnects());
        assertTrue(consumer.videoFrames() > 0);
    }
}
