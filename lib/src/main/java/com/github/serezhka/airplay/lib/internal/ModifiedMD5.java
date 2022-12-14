package com.github.serezhka.airplay.lib.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ModifiedMD5 {

    private final int[] shift = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

    void modified_md5(byte[] originalblockIn, byte[] keyIn, byte[] keyOut) {
        byte[] blockIn = new byte[64];
        long A, B, C, D, Z, tmp;
        int i;

        System.arraycopy(originalblockIn, 0, blockIn, 0, 64);

        // Each cycle does something like this:
        ByteBuffer key_words = ByteBuffer.wrap(keyIn);
        key_words.order(ByteOrder.LITTLE_ENDIAN);
        A = key_words.getInt() & 0xffffffffL;
        B = key_words.getInt() & 0xffffffffL;
        C = key_words.getInt() & 0xffffffffL;
        D = key_words.getInt() & 0xffffffffL;
        for (i = 0; i < 64; i++) {
            int input;
            int j = 0;
            if (i < 16) {
                j = i;
            } else if (i < 32) {
                j = (5 * i + 1) % 16;
            } else if (i < 48) {
                j = (3 * i + 5) % 16;
            } else if (i < 64) {
                j = 7 * i % 16;
            }

            input = ((blockIn[4 * j] & 0xFF) << 24) | ((blockIn[4 * j + 1] & 0xFF) << 16) | ((blockIn[4 * j + 2] & 0xFF) << 8) | (blockIn[4 * j + 3] & 0xFF);
            Z = A + input + (long) ((1L << 32) * Math.abs(Math.sin(i + 1)));
            if (i < 16) {
                Z = rol(Z + F(B, C, D), shift[i]);
            } else if (i < 32) {
                Z = rol(Z + G(B, C, D), shift[i]);
            } else if (i < 48) {
                Z = rol(Z + H(B, C, D), shift[i]);
            } else if (i < 64) {
                Z = rol(Z + I(B, C, D), shift[i]);
            }
            Z = Z + B;
            tmp = D;
            D = C;
            C = B;
            B = Z;
            A = tmp;
            if (i == 31) {
                // swapsies
                swap(blockIn, 4 * (int) (A & 15), 4 * (int) (B & 15));
                swap(blockIn, 4 * (int) (C & 15), 4 * (int) (D & 15));
                swap(blockIn, 4 * (int) ((A & (15 << 4)) >> 4), 4 * (int) ((B & (15 << 4)) >> 4));
                swap(blockIn, 4 * (int) ((A & (15 << 8)) >> 8), 4 * (int) ((B & (15 << 8)) >> 8));
                swap(blockIn, 4 * (int) ((A & (15 << 12)) >> 12), 4 * (int) ((B & (15 << 12)) >> 12));
            }
        }

        ByteBuffer key_out = ByteBuffer.wrap(keyOut);
        key_out.order(ByteOrder.LITTLE_ENDIAN);
        key_out.putInt((int) (key_words.getInt(0) + A));
        key_out.putInt((int) (key_words.getInt(4) + B));
        key_out.putInt((int) (key_words.getInt(8) + C));
        key_out.putInt((int) (key_words.getInt(12) + D));
    }

    private long F(long B, long C, long D) {
        return (B & C) | (~B & D);
    }

    private long G(long B, long C, long D) {
        return (B & D) | (C & ~D);
    }

    private long H(long B, long C, long D) {
        return B ^ C ^ D;
    }

    private long I(long B, long C, long D) {
        return C ^ (B | ~D);
    }

    private long rol(long input, long count) {
        return ((input << count) & 0xffffffffL) | (input & 0xffffffffL) >> (32 - count);
    }

    private void swap(byte[] arr, int idxA, int idxB) {
        ByteBuffer wrap = ByteBuffer.wrap(arr);
        wrap.order(ByteOrder.LITTLE_ENDIAN);
        int a = wrap.getInt(idxA);
        int b = wrap.getInt(idxB);
        wrap.putInt(idxB, a);
        wrap.putInt(idxA, b);
    }
}
