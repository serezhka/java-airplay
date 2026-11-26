package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRtpBufferTest {

    @Test
    void contiguousPacketsDrainInOrder() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(10), 0L);
        buf.offer(packet(11), 0L);
        buf.offer(packet(12), 0L);

        assertEquals(10, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(11, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(12, buf.pollReady().orElseThrow().getSequenceNumber());
        assertTrue(buf.pollReady().isEmpty());
        assertEquals(0, buf.missingCount());
    }

    @Test
    void gapIsMarkedMissingAndLateFillRestores() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(100), 0L);
        assertEquals(100, buf.pollReady().orElseThrow().getSequenceNumber());

        buf.offer(packet(103), 1_000L);
        assertEquals(2, buf.missingCount());
        assertTrue(buf.pollReady().isEmpty());

        buf.offer(packet(101), 2_000L);
        buf.offer(packet(102), 2_000L);
        assertEquals(0, buf.missingCount());
        assertEquals(101, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(102, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(103, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void resendDueAfterFirstCheckThenInterval() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(1), 0L);
        buf.pollReady();
        buf.offer(packet(4), 0L);

        assertTrue(buf.dueResends(AudioRtpBuffer.RESEND_FIRST_NS - 1).isEmpty());

        List<AudioRtpBuffer.Resend> first = buf.dueResends(AudioRtpBuffer.RESEND_FIRST_NS);
        assertEquals(List.of(new AudioRtpBuffer.Resend(2, 2)), first);

        assertTrue(buf.dueResends(AudioRtpBuffer.RESEND_FIRST_NS + 1).isEmpty());

        List<AudioRtpBuffer.Resend> retry = buf.dueResends(
                AudioRtpBuffer.RESEND_FIRST_NS + AudioRtpBuffer.RESEND_INTERVAL_NS);
        assertEquals(List.of(new AudioRtpBuffer.Resend(2, 2)), retry);
    }

    @Test
    void skipExpiredMissingAdvancesReadHead() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(50), 0L);
        buf.pollReady();
        buf.offer(packet(53), 0L);

        assertEquals(0, buf.skipExpiredMissing(AudioRtpBuffer.MISSING_GIVE_UP_NS - 1));
        assertEquals(2, buf.skipExpiredMissing(AudioRtpBuffer.MISSING_GIVE_UP_NS));
        assertEquals(53, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(0, buf.missingCount());
    }

    @Test
    void uint16WrapWorks() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(65534), 0L);
        buf.offer(packet(65535), 0L);
        buf.offer(packet(0), 0L);
        buf.offer(packet(1), 0L);

        assertEquals(65534, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(65535, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(0, buf.pollReady().orElseThrow().getSequenceNumber());
        assertEquals(1, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void farRestartResetsTimeline() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(5000), 0L);
        buf.offer(packet(5001), 0L);
        buf.pollReady();
        buf.pollReady();

        buf.offer(packet(40), 0L);
        assertEquals(40, buf.pollReady().orElseThrow().getSequenceNumber());
        buf.offer(packet(41), 0L);
        assertEquals(41, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void resetClearsWindow() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(200), 0L);
        buf.reset();
        buf.offer(packet(5), 0L);
        assertEquals(5, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void flushWithSeqReanchorsEmptyWindowAtTarget() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(10, 1000), 0L);
        buf.offer(packet(11, 1352), 0L);
        buf.offer(packet(12, 1704), 0L);
        assertEquals(3, buf.flush(AudioRtpInfo.of(20, null)));
        assertEquals(20, buf.nextRead());
        assertTrue(buf.pollReady().isEmpty());
        buf.offer(packet(20, 5000), 1L);
        assertEquals(20, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void flushWithRtpTimeDropsEarlierPacketsAndPendingRejectsLate() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(10, 1000), 0L);
        buf.offer(packet(11, 2000), 0L);
        buf.offer(packet(12, 3000), 0L);
        buf.flush(AudioRtpInfo.of(null, 2500L));
        assertEquals(12, buf.pollReady().orElseThrow().getSequenceNumber());
        assertTrue(buf.pollReady().isEmpty());

        // Pending rejects pre-flush timestamps until a post-flush packet arrives.
        buf.offer(packet(13, 2400), 2L);
        assertTrue(buf.pollReady().isEmpty());
        buf.offer(packet(13, 2600), 3L);
        assertEquals(13, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void flushWithSeqAndRtpTimeDropsPreFlushAndReanchors() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(10, 1000), 0L);
        buf.offer(packet(11, 2000), 0L);
        assertTrue(buf.flush(AudioRtpInfo.of(50, 9000L)) >= 2);
        assertEquals(50, buf.nextRead());
        buf.offer(packet(49, 8999), 1L); // before rtptime — dropped by pending
        assertTrue(buf.pollReady().isEmpty());
        buf.offer(packet(50, 9000), 2L);
        assertEquals(50, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void flushEmptyInfoResetsCompletely() {
        AudioRtpBuffer buf = new AudioRtpBuffer();
        buf.offer(packet(1, 1), 0L);
        buf.flush(AudioRtpInfo.empty());
        assertEquals(-1, buf.nextRead());
        buf.offer(packet(9, 9), 1L);
        assertEquals(9, buf.pollReady().orElseThrow().getSequenceNumber());
    }

    @Test
    void seqDiffIsSignedOnUint16Circle() {
        assertEquals(1, AudioRtpBuffer.seqDiff(10, 11));
        assertEquals(-1, AudioRtpBuffer.seqDiff(11, 10));
        assertEquals(1, AudioRtpBuffer.seqDiff(65535, 0));
        assertEquals(-1, AudioRtpBuffer.seqDiff(0, 65535));
    }

    private static AudioPacket packet(int seq) {
        return packet(seq, seq * 352L);
    }

    private static AudioPacket packet(int seq, long timestamp) {
        return AudioPacket.builder()
                .sequenceNumber(seq)
                .timestamp(timestamp)
                .available(true)
                .encodedAudioSize(2)
                .build()
                .encodedAudio(buf -> {
                    buf[0] = (byte) ((seq >> 8) & 0xff);
                    buf[1] = (byte) (seq & 0xff);
                });
    }
}
