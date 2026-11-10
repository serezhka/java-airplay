package com.github.serezhka.airplay.server.internal.handler.video;

import com.github.serezhka.airplay.protocol.mirror.MirrorVideoFraming;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void skipsEmptyOrShortType1CodecPayload() {
        assertNull(VideoHandler.prepareSpsPpsNALUnits(null));
        assertNull(VideoHandler.prepareSpsPpsNALUnits(new byte[0]));
        assertNull(VideoHandler.prepareSpsPpsNALUnits(new byte[6]));
    }

    @Test
    void parsesType1CodecIntoAnnexBSpsPps() {
        byte[] sps = {0x67, 0x42, (byte) 0xc0, 0x1e};
        byte[] pps = {0x68, (byte) 0xce, 0x06, (byte) 0xe2};
        byte[] type1 = MirrorVideoFraming.buildType1CodecData(sps, pps);

        byte[] annexB = VideoHandler.prepareSpsPpsNALUnits(type1);
        assertArrayEquals(new byte[]{
                0, 0, 0, 1, 0x67, 0x42, (byte) 0xc0, 0x1e,
                0, 0, 0, 1, 0x68, (byte) 0xce, 0x06, (byte) 0xe2
        }, annexB);
    }
}
