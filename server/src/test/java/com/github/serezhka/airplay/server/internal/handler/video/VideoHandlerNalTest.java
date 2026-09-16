package com.github.serezhka.airplay.server.internal.handler.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoHandlerNalTest {

    @Test
    void convertsEveryAvccNalToAnnexB() {
        byte[] payload = {
                0, 0, 0, 2, 0x67, 0x42,
                0, 0, 0, 2, 0x68, 0x43,
                0, 0, 0, 1, 0x65
        };

        assertTrue(VideoHandler.toAnnexB(payload));

        assertArrayEquals(new byte[]{
                0, 0, 0, 1, 0x67, 0x42,
                0, 0, 0, 1, 0x68, 0x43,
                0, 0, 0, 1, 0x65
        }, payload);
    }

    @Test
    void rejectsCorruptLengthPrefixedNal() {
        byte[] payload = {0, 0, 0, 10, 0x65};
        assertFalse(VideoHandler.toAnnexB(payload));
    }
}
