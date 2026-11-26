package com.github.serezhka.airplay.server.internal.handler.audio;

import com.github.serezhka.airplay.server.internal.packet.AudioPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Power-of-two RTP reorder window: mark gaps, accept late fills, skip only after give-up.
 * Timing defaults match common AirPlay receiver practice (100 ms / 250 ms / ~750 ms).
 */
final class AudioRtpBuffer {

    static final int CAPACITY = 512;
    static final long RESEND_FIRST_NS = 100_000_000L;
    static final long RESEND_INTERVAL_NS = 250_000_000L;
    static final long MISSING_GIVE_UP_NS = 750_000_000L;

    record Resend(int firstSeq, int count) {
    }

    private static final byte EMPTY = 0;
    private static final byte MISSING = 1;
    private static final byte READY = 2;
    private static final long NO_PENDING_FLUSH = -1L;

    private final AudioPacket[] packets = new AudioPacket[CAPACITY];
    private final byte[] state = new byte[CAPACITY];
    private final long[] missingSinceNs = new long[CAPACITY];
    private final long[] lastResendNs = new long[CAPACITY];

    /** Next sequence to emit; {@code -1} before the first packet. */
    private int nextRead = -1;
    /** One past the highest contiguous received (or marked) sequence. */
    private int writeNext = -1;
    /**
     * When set, drop inbound packets with RTP timestamp strictly before this value
     * (FLUSH/RECORD {@code rtptime=} point, exclusive keep).
     */
    private long pendingFlushRtp = NO_PENDING_FLUSH;

    void reset() {
        Arrays.fill(state, EMPTY);
        Arrays.fill(packets, null);
        Arrays.fill(missingSinceNs, 0L);
        Arrays.fill(lastResendNs, 0L);
        nextRead = -1;
        writeNext = -1;
        pendingFlushRtp = NO_PENDING_FLUSH;
    }

    /**
     * Apply RTSP FLUSH/RECORD {@link AudioRtpInfo}.
     * Prefer {@code seq=} (re-anchor empty window at that seq); also honor {@code rtptime=}
     * so late pre-flush packets are discarded. With neither field, full {@link #reset()}.
     *
     * @return packets discarded from the window (0 if only pending / empty)
     */
    int flush(AudioRtpInfo info) {
        if (info == null || info.isEmpty()) {
            reset();
            return 0;
        }
        int dropped = 0;
        if (info.rtpTime().isPresent()) {
            pendingFlushRtp = info.rtpTime().get();
            dropped += dropReadyBeforeRtp(pendingFlushRtp);
        }
        if (info.seq().isPresent()) {
            dropped += reanchorAtSeq(info.seq().get());
        } else if (info.rtpTime().isPresent() && nextRead >= 0) {
            while (nextRead != writeNext && state[idx(nextRead)] == EMPTY) {
                nextRead = succ(nextRead);
            }
        }
        return dropped;
    }

    void offer(AudioPacket packet, long nowNs) {
        if (packet == null) {
            return;
        }
        if (pendingFlushRtp != NO_PENDING_FLUSH) {
            long ts = packet.getTimestamp() & 0xFFFF_FFFFL;
            // Drop when ts < flush point (exclusive keep at equality).
            if (signedTsDiff(ts, pendingFlushRtp) > 0) {
                packet.available(false);
                return;
            }
            // First packet at/after flush point — clear pending filter.
            pendingFlushRtp = NO_PENDING_FLUSH;
        }

        int seq = packet.getSequenceNumber() & 0xFFFF;
        if (nextRead < 0) {
            placeReady(seq, packet);
            nextRead = seq;
            writeNext = succ(seq);
            return;
        }

        int aheadOfWrite = seqDiff(writeNext, seq);
        if (aheadOfWrite == 0) {
            placeReady(seq, packet);
            writeNext = succ(writeNext);
            return;
        }
        if (aheadOfWrite > 0 && aheadOfWrite < CAPACITY - 1) {
            for (int i = 0; i < aheadOfWrite; i++) {
                markMissing((writeNext + i) & 0xFFFF, nowNs);
            }
            placeReady(seq, packet);
            writeNext = succ(seq);
            return;
        }

        int fromRead = seqDiff(nextRead, seq);
        if (fromRead >= 0 && fromRead < CAPACITY) {
            byte cur = state[idx(seq)];
            if (cur == MISSING || cur == EMPTY) {
                placeReady(seq, packet);
            }
            return;
        }

        // Far outside the window (track change / new timeline).
        long keepPending = pendingFlushRtp;
        reset();
        pendingFlushRtp = keepPending;
        offer(packet, nowNs);
    }

    Optional<AudioPacket> pollReady() {
        if (nextRead < 0 || state[idx(nextRead)] != READY) {
            return Optional.empty();
        }
        int seq = nextRead;
        AudioPacket packet = packets[idx(seq)];
        clear(seq);
        nextRead = succ(seq);
        return Optional.ofNullable(packet);
    }

    /**
     * If the read head has been missing past give-up, drop the contiguous hole.
     * @return number of sequences skipped (0 if still waiting)
     */
    int skipExpiredMissing(long nowNs) {
        if (nextRead < 0 || state[idx(nextRead)] != MISSING) {
            return 0;
        }
        if (nowNs - missingSinceNs[idx(nextRead)] < MISSING_GIVE_UP_NS) {
            return 0;
        }
        int skipped = 0;
        while (skipped < CAPACITY && state[idx(nextRead)] == MISSING) {
            clear(nextRead);
            nextRead = succ(nextRead);
            skipped++;
        }
        return skipped;
    }

    List<Resend> dueResends(long nowNs) {
        List<Resend> out = new ArrayList<>();
        if (nextRead < 0 || writeNext < 0) {
            return out;
        }
        int runStart = -1;
        int runLen = 0;
        for (int seq = nextRead; seq != writeNext; seq = succ(seq)) {
            int i = idx(seq);
            if (state[i] == MISSING && resendDue(i, nowNs)) {
                lastResendNs[i] = nowNs;
                if (runStart < 0) {
                    runStart = seq;
                    runLen = 1;
                } else {
                    runLen++;
                }
            } else if (runStart >= 0) {
                out.add(new Resend(runStart, runLen));
                runStart = -1;
                runLen = 0;
            }
        }
        if (runStart >= 0) {
            out.add(new Resend(runStart, runLen));
        }
        return out;
    }

    int nextRead() {
        return nextRead;
    }

    int missingCount() {
        if (nextRead < 0 || writeNext < 0) {
            return 0;
        }
        int n = 0;
        for (int seq = nextRead; seq != writeNext; seq = succ(seq)) {
            if (state[idx(seq)] == MISSING) {
                n++;
            }
        }
        return n;
    }

    long pendingFlushRtp() {
        return pendingFlushRtp;
    }

    private int reanchorAtSeq(int nextSeq) {
        int target = nextSeq & 0xFFFF;
        int dropped = 0;
        if (nextRead < 0) {
            nextRead = target;
            writeNext = target;
            return 0;
        }
        // Drop everything currently buffered; resume expecting {@code target}.
        for (int seq = nextRead; seq != writeNext; seq = succ(seq)) {
            if (state[idx(seq)] != EMPTY) {
                dropped++;
            }
            clear(seq);
        }
        nextRead = target;
        writeNext = target;
        return dropped;
    }

    private int dropReadyBeforeRtp(long rtpExclusive) {
        if (nextRead < 0 || writeNext < 0) {
            return 0;
        }
        int dropped = 0;
        for (int seq = nextRead; seq != writeNext; seq = succ(seq)) {
            int i = idx(seq);
            if (state[i] == READY) {
                AudioPacket p = packets[i];
                long ts = p != null ? p.getTimestamp() & 0xFFFF_FFFFL : 0L;
                if (signedTsDiff(ts, rtpExclusive) > 0) {
                    clear(seq);
                    dropped++;
                }
            } else if (state[i] == MISSING) {
                // Unknown timestamp for a hole before flush — drop the marker.
                clear(seq);
                dropped++;
            }
        }
        while (nextRead != writeNext && state[idx(nextRead)] == EMPTY) {
            nextRead = succ(nextRead);
        }
        return dropped;
    }

    private boolean resendDue(int i, long nowNs) {
        long age = nowNs - missingSinceNs[i];
        if (age < RESEND_FIRST_NS) {
            return false;
        }
        long last = lastResendNs[i];
        return last == 0L || nowNs - last >= RESEND_INTERVAL_NS;
    }

    private void placeReady(int seq, AudioPacket packet) {
        int i = idx(seq);
        packets[i] = packet;
        state[i] = READY;
        missingSinceNs[i] = 0L;
        lastResendNs[i] = 0L;
    }

    private void markMissing(int seq, long nowNs) {
        int i = idx(seq);
        if (state[i] == READY && (packets[i].getSequenceNumber() & 0xFFFF) == seq) {
            return;
        }
        packets[i] = null;
        state[i] = MISSING;
        missingSinceNs[i] = nowNs;
        lastResendNs[i] = 0L;
    }

    private void clear(int seq) {
        int i = idx(seq);
        AudioPacket p = packets[i];
        if (p != null) {
            p.available(false);
        }
        packets[i] = null;
        state[i] = EMPTY;
        missingSinceNs[i] = 0L;
        lastResendNs[i] = 0L;
    }

    private static int idx(int seq) {
        return seq & (CAPACITY - 1);
    }

    private static int succ(int seq) {
        return (seq + 1) & 0xFFFF;
    }

    /** Signed distance {@code to - from} on the uint16 circle. */
    static int seqDiff(int from, int to) {
        return (short) ((to - from) & 0xFFFF);
    }

    /** Signed distance {@code to - from} on the uint32 RTP timestamp circle. */
    static int signedTsDiff(long from, long to) {
        return (int) ((to - from) & 0xFFFF_FFFFL);
    }
}
