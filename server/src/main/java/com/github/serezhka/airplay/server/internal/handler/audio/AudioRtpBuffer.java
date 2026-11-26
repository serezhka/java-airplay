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

    private final AudioPacket[] packets = new AudioPacket[CAPACITY];
    private final byte[] state = new byte[CAPACITY];
    private final long[] missingSinceNs = new long[CAPACITY];
    private final long[] lastResendNs = new long[CAPACITY];

    /** Next sequence to emit; {@code -1} before the first packet. */
    private int nextRead = -1;
    /** One past the highest contiguous received (or marked) sequence. */
    private int writeNext = -1;

    void reset() {
        Arrays.fill(state, EMPTY);
        Arrays.fill(packets, null);
        Arrays.fill(missingSinceNs, 0L);
        Arrays.fill(lastResendNs, 0L);
        nextRead = -1;
        writeNext = -1;
    }

    void offer(AudioPacket packet, long nowNs) {
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
        reset();
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
}
