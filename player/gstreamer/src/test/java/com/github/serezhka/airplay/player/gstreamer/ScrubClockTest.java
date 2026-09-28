package com.github.serezhka.airplay.player.gstreamer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScrubClockTest {

    @Test
    void doesNotStepBackwardOntoEarlierKeyframe() {
        ScrubClock clock = new ScrubClock();
        clock.arm(103.10);
        // First sample matches the scrub clock and must not release the guard.
        assertEquals(103.10, clock.report(103.10, 103.10), 0.001);
        // Session 20260926-154407: next demux answer was 101.76.
        assertEquals(104.6, clock.report(101.76, 104.6), 0.001);
        assertEquals(105.6, clock.report(102.72, 105.6), 0.001);
    }

    @Test
    void ignoresStaleClockAfterBackwardScrub() {
        ScrubClock clock = new ScrubClock();
        clock.arm(186.4);
        assertEquals(186.5, clock.report(289.0, 186.5), 0.001);
        assertEquals(188.0, clock.report(183.4, 188.0), 0.001);
    }

    @Test
    void followsDemuxOnceItCatchesTheScrubClock() {
        ScrubClock clock = new ScrubClock();
        clock.arm(100);
        assertEquals(103.0, clock.report(103.0, 103.2), 0.001);
        // Guard released; later playback follows the demux, including a small lead.
        assertEquals(110.0, clock.report(110.0, 109.0), 0.001);
    }
}
