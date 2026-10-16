package com.github.serezhka.airplay.server.internal.handler.session;

/**
 * Holds a bare {@code rate=0} open long enough that a following {@code /scrub} is not
 * committed as a real pause. Observed scrub gaps were ~0.2–1.8s before {@code /scrub}.
 */
final class HlsRateZeroWindow {

    /** Minimum hold before a bare {@code rate=0} becomes a real pause. */
    static final long PAUSE_DELAY_NS = 2_500_000_000L;

    private boolean pending;
    private int playbackInfosSinceArm;
    private long armedAtNanos;

    void arm() {
        pending = true;
        playbackInfosSinceArm = 0;
        armedAtNanos = System.nanoTime();
    }

    void clear() {
        pending = false;
        playbackInfosSinceArm = 0;
        armedAtNanos = 0;
    }

    boolean isPending() {
        return pending;
    }

    /**
     * @return true when this poll may commit a pause: at least two playback-info probes
     *         <b>and</b> {@link #PAUSE_DELAY_NS} since arm.
     */
    boolean notePlaybackInfoForPause() {
        if (!pending) {
            return false;
        }
        playbackInfosSinceArm++;
        if (playbackInfosSinceArm < 2) {
            return false;
        }
        long armedAt = armedAtNanos;
        return armedAt > 0 && System.nanoTime() - armedAt >= PAUSE_DELAY_NS;
    }

    /** Test helper: pretend {@code elapsedNs} already passed since arm. */
    void advanceForTest(long elapsedNs) {
        if (armedAtNanos > 0) {
            armedAtNanos = System.nanoTime() - elapsedNs;
        }
    }
}
