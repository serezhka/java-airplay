package com.github.serezhka.airplay.player.gstreamer;

/**
 * After a seek the demux clock can snap to an earlier keyframe. Publishing that
 * walks /playback-info backward, and the sender freezes its timeline.
 *
 * <p>The first sample after a seek often equals the scrub target (the wall clock
 * we already know). That must not drop the guard: the next sample is the keyframe
 * a couple of seconds earlier. While the guard is up the reported position stays
 * on the scrub clock and keeps advancing. A new {@link #arm(double)} allows a
 * backward scrub.
 */
final class ScrubClock {

    private volatile boolean hold;
    private volatile double floor;
    private volatile double armedAt;

    void arm(double targetSeconds) {
        armedAt = targetSeconds;
        floor = targetSeconds;
        hold = true;
    }

    void clear() {
        hold = false;
        floor = 0;
        armedAt = 0;
    }

    /**
     * @param clockSeconds demux position, which right after a seek may still be the wall clock
     * @param wallSeconds  scrub target plus time since the seek
     */
    double report(double clockSeconds, double wallSeconds) {
        if (hold) {
            // Demux has moved past the scrub point and sits beside the scrub clock.
            boolean caughtUp = clockSeconds >= armedAt + 0.5
                    && Math.abs(clockSeconds - wallSeconds) <= 0.25;
            if (caughtUp) {
                hold = false;
                floor = clockSeconds;
                return clockSeconds;
            }
            double chosen = Math.max(floor, wallSeconds);
            floor = chosen;
            return chosen;
        }
        if (clockSeconds + 0.05 < floor) {
            return floor;
        }
        if (clockSeconds > floor) {
            floor = clockSeconds;
        }
        return clockSeconds;
    }
}
