package com.github.serezhka.airplay.server.internal.handler.video;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class VideoHandlerNalTest {

    @Test
    void convertsEveryAvccNalToAnnexB() {
        byte[] payload = {
                0, 0, 0, 2, 0x67, 0x42,
                0, 0, 0, 2, 0x68, 0x43,
                0, 0, 0, 1, 0x65
        };

        VideoHandler.toAnnexB(payload);

        assertArrayEquals(new byte[]{
                0, 0, 0, 1, 0x67, 0x42,
                0, 0, 0, 1, 0x68, 0x43,
                0, 0, 0, 1, 0x65
        }, payload);
    }
}
