package com.github.serezhka.airplay.player.gstreamer;

/**
 * After a seek the demux clock can snap to an earlier keyframe. Hold the scrub
 * target (and keep advancing) until the demux clock catches up beside it.
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

    double report(double clockSeconds, double wallSeconds) {
        if (hold) {
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
