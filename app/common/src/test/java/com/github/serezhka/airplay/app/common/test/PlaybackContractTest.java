package com.github.serezhka.airplay.app.common.test;

import com.github.serezhka.airplay.app.common.harness.RecordingConsumer;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract every {@link Playback} implementation must satisfy for idle defaults
 * and control callbacks — independent of a native decoder.
 */
class PlaybackContractTest {

    @Test
    void idleInfoDefaultsAreSafeForPlaybackInfo() {
        Playback playback = new RecordingConsumer();
        Playback.Info info = playback.info();
        assertEquals(0, info.duration(), 0.0);
        assertEquals(0, info.position(), 0.0);
        assertEquals(1, info.rate(), 0.0);
        assertEquals(1.0, playback.volume(), 0.0);
    }

    @Test
    void controlCallbacksAreIdempotentNoopsUntilWired() {
        Playback playback = new RecordingConsumer();
        playback.onPause();
        playback.onResume();
        playback.onSeek(1.5);
        playback.onVolume(0.5);
        playback.onPlaylist("http://127.0.0.1/playlist/master.m3u8");
        playback.onPlaylistRemoved();
        assertEquals(0.5, playback.volume(), 0.0);
    }

    @Test
    void volumeClampsToUnitInterval() {
        RecordingConsumer playback = new RecordingConsumer();
        playback.onVolume(-1.0);
        assertEquals(0.0, playback.volume(), 0.0);
        playback.onVolume(2.0);
        assertEquals(1.0, playback.volume(), 0.0);
    }

    @Test
    void controlEventOrderForHlsRoundTrip() {
        RecordingConsumer playback = new RecordingConsumer();
        playback.onPlaylist("http://127.0.0.1/mediadata.m3u8");
        playback.onPlaylistContent("http://127.0.0.1/mediadata.m3u8", "#EXTM3U\n");
        playback.onVolume(0.3);
        playback.onPause();
        playback.onResume();
        playback.onSeek(2.0);
        playback.onPlaylistRemoved();
        assertTrue(playback.eventsContainInOrder(
                "playlist", "playlistContent", "volume", "pause", "resume", "seek", "playlistRemoved"));
    }

    @Test
    void reconnectRecordsDisconnects() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        for (int i = 0; i < 2; i++) {
            consumer.onVideoFormat(new VideoStreamInfo("r" + i));
            PlaybackFixture.playVideo(consumer, 5);
            consumer.onVideoSrcDisconnect();
        }
        assertEquals(2, consumer.videoDisconnects());
        assertTrue(consumer.videoFrames() > 0);
        assertTrue(consumer.eventsContainInOrder(
                "videoFormat", "videoDisconnect", "videoFormat", "videoDisconnect"));
    }

    @Test
    void doubleDisconnectIsIdempotent() {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.onVideoFormat(new VideoStreamInfo("d"));
        consumer.onAudioFormat(PlaybackFixture.aacLcStreamInfo());
        consumer.onVideoSrcDisconnect();
        consumer.onVideoSrcDisconnect();
        consumer.onAudioSrcDisconnect();
        consumer.onAudioSrcDisconnect();
        assertEquals(2, consumer.videoDisconnects());
        assertEquals(2, consumer.audioDisconnects());
    }

    @Test
    void observerOnEndedIsInvokable() {
        AtomicInteger ends = new AtomicInteger();
        RecordingConsumer playback = new RecordingConsumer();
        playback.setObserver(ends::incrementAndGet);
        playback.fireEnded();
        assertEquals(1, ends.get());
    }

    @Test
    void videoFeedIncrementsRecordingConsumer() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        consumer.onVideoFormat(new VideoStreamInfo("contract"));
        PlaybackFixture.play(consumer);
        consumer.onVideoSrcDisconnect();
        assertTrue(consumer.videoFrames() > 0);
    }
}
