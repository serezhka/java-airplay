package com.github.serezhka.airplay.server.internal.handler.control;

import org.junit.jupiter.api.Test;

import static com.github.serezhka.airplay.server.internal.handler.control.PresentationEndPolicy.Decision.END;
import static com.github.serezhka.airplay.server.internal.handler.control.PresentationEndPolicy.Decision.KEEP;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PresentationEndPolicyTest {

    @Test
    void youtubeOnlyStopEndsPresentation() {
        assertEquals(END, PresentationEndPolicy.afterHlsIdle(false, false, false));
    }

    @Test
    void stopWhileHlsStillLiveKeepsPresentation() {
        assertEquals(KEEP, PresentationEndPolicy.afterHlsIdle(true, false, false));
    }

    @Test
    void stopWithLeftoverMirrorVideoKeepsUntilTornDown() {
        assertEquals(KEEP, PresentationEndPolicy.afterHlsIdle(false, true, false));
    }

    @Test
    void stopAfterMirrorSeenKeepsForPossibleRemirror() {
        assertEquals(KEEP, PresentationEndPolicy.afterHlsIdle(false, false, true));
    }

    @Test
    void videoTeardownDuringHlsKeepsPresentation() {
        assertEquals(KEEP, PresentationEndPolicy.afterVideoTeardown(true));
    }

    @Test
    void videoTeardownWithoutHlsEndsPresentation() {
        assertEquals(END, PresentationEndPolicy.afterVideoTeardown(false));
    }

    @Test
    void fullTeardownWithoutHlsEndsPresentation() {
        assertEquals(END, PresentationEndPolicy.afterFullTeardown(false));
    }

    @Test
    void fullTeardownWhileOtherSessionHasHlsKeepsPresentation() {
        assertEquals(KEEP, PresentationEndPolicy.afterFullTeardown(true));
    }
}
