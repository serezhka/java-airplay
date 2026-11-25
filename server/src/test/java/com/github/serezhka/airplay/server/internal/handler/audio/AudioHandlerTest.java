package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioHandlerTest {

    @Test
    void contiguousSeqEmitsInOrder() {
        List<Integer> emitted = new ArrayList<>();
        AudioHandler handler = handler(recording(emitted), AudioResendRequests.NOOP, new AtomicLong());

        handler.channelRead(null, packet(10));
        handler.channelRead(null, packet(11));
        handler.channelRead(null, packet(12));

        assertEquals(List.of(10, 11, 12), emitted);
    }

    @Test
    void gapRequestsResendThenEmitsAfterLateFill() {
        List<Integer> emitted = new ArrayList<>();
        List<AudioRtpBuffer.Resend> resends = new ArrayList<>();
        AtomicLong clock = new AtomicLong(0L);
        AudioHandler handler = handler(recording(emitted), (first, count) ->
                resends.add(new AudioRtpBuffer.Resend(first, count)), clock);

        handler.channelRead(null, packet(100));
        handler.channelRead(null, packet(103));
        assertEquals(List.of(100), emitted);
        assertTrue(resends.isEmpty(), "too early for first resend check");

        clock.set(AudioRtpBuffer.RESEND_FIRST_NS);
        handler.channelRead(null, packet(104));
        assertEquals(List.of(new AudioRtpBuffer.Resend(101, 2)), resends);

        handler.channelRead(null, packet(101));
        handler.channelRead(null, packet(102));
        assertEquals(List.of(100, 101, 102, 103, 104), emitted);
        assertEquals(0, handler.missingCount());
    }

    @Test
    void expiredGapSkipsAndSignalsDiscontinuity() {
        List<Integer> emitted = new ArrayList<>();
        AtomicInteger discontinuities = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0L);
        AudioHandler handler = handler(recording(emitted, discontinuities), AudioResendRequests.NOOP, clock);

        handler.channelRead(null, packet(100));
        handler.channelRead(null, packet(102));
        assertEquals(List.of(100), emitted);

        clock.set(AudioRtpBuffer.MISSING_GIVE_UP_NS);
        handler.channelRead(null, packet(103));

        assertTrue(emitted.contains(102), emitted.toString());
        assertTrue(emitted.contains(103), emitted.toString());
        assertEquals(1, discontinuities.get());
    }

    @Test
    void uint16WrapContinuesEmitting() {
        List<Integer> emitted = new ArrayList<>();
        AudioHandler handler = handler(recording(emitted), AudioResendRequests.NOOP, new AtomicLong());

        handler.channelRead(null, packet(65534));
        handler.channelRead(null, packet(65535));
        handler.channelRead(null, packet(0));
        handler.channelRead(null, packet(1));

        assertEquals(List.of(65534, 65535, 0, 1), emitted);
    }

    @Test
    void largeSeqRestartAcceptsNewTimeline() {
        List<Integer> emitted = new ArrayList<>();
        AudioHandler handler = handler(recording(emitted), AudioResendRequests.NOOP, new AtomicLong());

        handler.channelRead(null, packet(5000));
        handler.channelRead(null, packet(5001));
        handler.channelRead(null, packet(40));
        handler.channelRead(null, packet(41));

        assertEquals(List.of(5000, 5001, 40, 41), emitted);
    }

    @Test
    void resetClearsTimelineLikeFlush() {
        List<Integer> emitted = new ArrayList<>();
        AudioHandler handler = handler(recording(emitted), AudioResendRequests.NOOP, new AtomicLong());

        handler.channelRead(null, packet(200));
        handler.reset();
        handler.channelRead(null, packet(5));

        assertEquals(List.of(200, 5), emitted);
        assertEquals(6, handler.nextRead());
    }

    @Test
    void decryptFailureDoesNotMuteFollowingFrames() {
        List<Integer> emitted = new ArrayList<>();
        AtomicInteger decryptCalls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        AudioHandler handler = new AudioHandler((a, n) -> {
            if (decryptCalls.incrementAndGet() == 2) {
                throw new IllegalStateException("boom");
            }
        }, recording(emitted), AudioResendRequests.NOOP, clock::get);

        handler.channelRead(null, packet(1));
        handler.channelRead(null, packet(2));
        handler.channelRead(null, packet(3));

        assertEquals(List.of(1, 3), emitted);
    }

    private static AudioHandler handler(Playback playback, AudioResendRequests resend, AtomicLong clock) {
        return new AudioHandler((a, n) -> {
        }, playback, resend, clock::get);
    }

    private static AudioPacket packet(int seq) {
        byte[] payload = new byte[]{
                (byte) ((seq >> 8) & 0xff),
                (byte) (seq & 0xff)
        };
        AudioPacket p = AudioPacket.builder()
                .sequenceNumber(seq)
                .available(true)
                .encodedAudioSize(payload.length)
                .build();
        p.encodedAudio(buf -> System.arraycopy(payload, 0, buf, 0, payload.length));
        return p;
    }

    private static Playback recording(List<Integer> emittedSeqs) {
        return recording(emittedSeqs, null);
    }

    private static Playback recording(List<Integer> emittedSeqs, AtomicInteger discontinuities) {
        return new Playback() {
            @Override
            public void onAudio(byte[] bytes) {
                onAudio(bytes, 0, bytes == null ? 0 : bytes.length);
            }

            @Override
            public void onAudio(byte[] bytes, int offset, int length) {
                int seq = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
                emittedSeqs.add(seq);
            }

            @Override
            public void onAudioDiscontinuity() {
                if (discontinuities != null) {
                    discontinuities.incrementAndGet();
                }
            }

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
            public void onAudioSrcDisconnect() {
            }
        };
    }
}
