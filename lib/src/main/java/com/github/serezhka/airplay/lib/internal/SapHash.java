package com.github.serezhka.airplay.lib.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SapHash {

    private final HandGarble handGarble = new HandGarble();

    private byte rol8(byte input, int count) {
        return (byte) (((input << count) & 0xff) | (input & 0xff) >> (8 - count));
    }

    void sap_hash(byte[] blockIn, byte[] keyOut) {

        byte[] buffer0 = {-106, 95, -58, 83, -8, 70, -52, 24, -33, -66, -78, -8, 56, -41, -20, 34, 3, -47, 32, -113};
        byte[] buffer1 = new byte[210];
        byte[] buffer2 = {67, 84, 98, 122, 24, -61, -42, -77, -102, 86, -10, 28, 20, 63, 12, 29, 59, 54, -125, -79, 57, 81, 74, -86, 9, 62, -2, 68, -81, -34, -61, 32, -99, 66, 58};
        byte[] buffer3 = new byte[132];
        byte[] buffer4 = {-19, 37, -47, -69, -68, 39, -97, 2, -94, -87, 17, 0, 12, -77, 82, -64, -67, -29, 27, 73, -57};
        int[] i0_index = {18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30};
        byte w, x, y, z;

        ByteBuffer block_words = ByteBuffer.wrap(blockIn);
        block_words.order(ByteOrder.LITTLE_ENDIAN);

        // Load the input into the buffer
        for (int i = 0; i < 210; i++) {
            // We need to swap the byte order around so it is the right endianness
            int in_word = block_words.getInt(((i % 64) >> 2) * 4);
            byte in_byte = (byte) ((in_word >> ((3 - (i % 4)) << 3)) & 0xff);
            buffer1[i] = in_byte;
        }

        // Next a scrambling
        for (int i = 0; i < 840; i++) {
            // We have to do unsigned, 32-bit modulo, or we get the wrong indices
            x = buffer1[(int) (((i - 155) & 0xffffffffL) % 210)];
            y = buffer1[(int) (((i - 57) & 0xffffffffL) % 210)];
            z = buffer1[(int) (((i - 13) & 0xffffffffL) % 210)];
            w = buffer1[(int) ((i & 0xffffffffL) % 210)];
            buffer1[i % 210] = (byte) ((rol8(y, 5) + (rol8(z, 3) ^ w) - rol8(x, 7)) & 0xff);
        }

        // I have no idea what this is doing (yet), but it gives the right output
        handGarble.garble(buffer0, buffer1, buffer2, buffer3, buffer4);

        // Fill the output with 0xE1
        for (int i = 0; i < 16; i++) {
            keyOut[i] = (byte) 0xE1;
        }

        // Now we use all the buffers we have calculated to grind out the output. First buffer3
        for (int i = 0; i < 11; i++) {
            // Note that this is addition (mod 255) and not XOR
            // Also note that we only use certain indices
            // And that index 3 is hard-coded to be 0x3d (Maybe we can hack this up by changing buffer3[0] to be 0xdc?
            if (i == 3) {
                keyOut[i] = 0x3d;
            } else {
                keyOut[i] = (byte) ((keyOut[i] + buffer3[i0_index[i] * 4]) & 0xff);
            }
        }

        // Then buffer0
        for (int i = 0; i < 20; i++) {
            keyOut[i % 16] ^= buffer0[i];
        }

        // Then buffer2
        for (int i = 0; i < 35; i++) {
            keyOut[i % 16] ^= buffer2[i];
        }

        // Do buffer1
        for (int i = 0; i < 210; i++) {
            keyOut[(i % 16)] ^= buffer1[i];
        }

        // Now we do a kind of reverse-scramble
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                x = keyOut[(int) (((i - 7) & 0xffffffffL) % 16)];
                y = keyOut[i % 16];
                z = keyOut[(int) (((i - 37) & 0xffffffffL) % 16)];
                w = keyOut[(int) (((i - 177) & 0xffffffffL) % 16)];
                keyOut[i] = (byte) (rol8(x, 1) ^ y ^ rol8(z, 6) ^ rol8(w, 5));
            }
        }
    }
}
