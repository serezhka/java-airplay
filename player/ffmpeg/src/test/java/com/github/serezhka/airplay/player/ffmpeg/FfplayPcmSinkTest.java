package com.github.serezhka.airplay.player.ffmpeg;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FfplayPcmSinkTest {

    @Test
    void muteZerosSamples() {
        byte[] pcm = le16(1000, -1000, 32_000);
        FfplayPcmSink.scaleS16Le(pcm, 0);
        assertArrayEquals(le16(0, 0, 0), pcm);
    }

    @Test
    void halfGainScalesInPlace() {
        byte[] pcm = le16(2000, -2000);
        FfplayPcmSink.scaleS16Le(pcm, 0.5);
        assertEquals(1000, sampleAt(pcm, 0));
        assertEquals(-1000, sampleAt(pcm, 2));
    }

    @Test
    void unityLeavesBufferAlone() {
        byte[] pcm = le16(12345, -7);
        byte[] original = pcm.clone();
        FfplayPcmSink.scaleS16Le(pcm, 1.0);
        assertArrayEquals(original, pcm);
    }

    private static byte[] le16(int... samples) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            pcm[i * 2] = (byte) samples[i];
            pcm[i * 2 + 1] = (byte) (samples[i] >> 8);
        }
        return pcm;
    }

    private static int sampleAt(byte[] pcm, int offset) {
        return (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
    }
}
