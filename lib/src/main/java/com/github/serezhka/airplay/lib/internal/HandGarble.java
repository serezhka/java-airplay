package com.github.serezhka.airplay.lib.internal;

public class HandGarble {

    // I do not know why it is doing all of this, and there is still a possibility for a gremlin or two to be lurking in the background
    // I DO know it is not trivial. It could be purely random garbage, of course.
    void garble(byte[] buffer0, byte[] buffer1, byte[] buffer2, byte[] buffer3, byte[] buffer4) {

        int tmp, tmp2, tmp3;
        int A, B, C, D, E, M, J, G, F, H, K, R, S, T, U, V, W, X, Y, Z;
        // buffer1[64] = A
        // (buffer1[99] / 3) = B
        // 0ABAAABB
        // Then we AND with a complex expression, and add 20 just for good measure
        buffer2[12] = (byte) (0x14 + ((((buffer1[64] & 0xff) & 92) | (((buffer1[99] & 0xff) / 3) & 35))
                & (buffer4[rol8x((buffer4[((buffer1[206] & 0xff) % 21)] & 0xff), 4) % 21] & 0xff)));

        // This is a bit simpler: 2*B*B/25
        buffer1[4] = (byte) (((buffer1[99] & 0xff) / 5) * ((buffer1[99] & 0xff) / 5) * 2);

        // Simpler still!
        buffer2[34] = (byte) 0xb8;

        // ...
        buffer1[153] ^= (buffer2[(buffer1[203] & 0xff) % 35] * buffer2[(buffer1[203] & 0xff) % 35] * (buffer1[190] & 0xff));

        // This one looks simple, but wow was it not :(
        buffer0[3] -= (((buffer4[(buffer1[205] & 0xff) % 21] >> 1) & 80) | 0xe6440);

        // This is always 0x93
        buffer0[16] = (byte) 0x93;

        // This is always 0x62
        buffer0[13] = 0x62;

        buffer1[33] -= (buffer4[(buffer1[36] & 0xff) % 21] & 0xf6);

        // This is always 7
        tmp2 = buffer2[(buffer1[67] & 0xff) % 35];
        buffer2[12] = 0x07;

        // This is pretty easy!
        tmp = buffer0[(buffer1[181] & 0xff) % 20];
        buffer1[2] -= 3136;

        buffer0[19] = buffer4[(buffer1[58] & 0xff) % 21];

        buffer3[0] = (byte) (92 - buffer2[(buffer1[32] & 0xff) % 35]);

        buffer3[4] = (byte) ((buffer2[(buffer1[15] & 0xff) % 35] & 0xff) + 0x9e);

        buffer1[34] += ((buffer4[(((buffer2[(buffer1[15] & 0xff) % 35] & 0xff) + 0x9e) & 0xff) % 21] & 0xff) / 5);

        buffer0[19] = (byte) ((buffer0[19] & 0xff) + 0xfffffee6 - (((buffer0[(buffer3[4] & 0xff) % 20] & 0xff) >> 1) & 102));

        // This LOOKS like it should be a rol8x, but it just doesnt work out because if the shift amount is 0, then the output is 0 too :(
        // FIXME: Switch to weird_ror8
        buffer1[15] = (byte) ((3 * ((((buffer1[72] & 0xff) >> ((buffer4[(buffer1[190] & 0xff) % 21] & 0xff) & 7)) ^ ((buffer1[72] & 0xff) << ((7 - ((buffer4[(buffer1[190] & 0xff) % 21] & 0xff) - 1) & 7)))) - (3 * buffer4[(buffer1[126] & 0xff) % 21]))) ^ buffer1[15]);

        buffer0[15] ^= (buffer2[(buffer1[181] & 0xff) % 35] & 0xff) * (buffer2[(buffer1[181] & 0xff) % 35] & 0xff) * (buffer2[(buffer1[181] & 0xff) % 35] & 0xff);

        buffer2[4] ^= (buffer1[202] & 0xff) / 3;

        // This could probably be quite a bit simpler.
        A = 92 - (buffer0[(buffer3[0] & 0xff) % 20] & 0xff);
        E = (A & 0xc6) | (~(buffer1[105] & 0xff) & 0xc6) | (A & (~(buffer1[105] & 0xff)));
        buffer2[1] += (E * E * E);

        buffer0[19] ^= ((224 | ((buffer4[(buffer1[92] & 0xff) % 21] & 0xff) & 27)) * (buffer2[(buffer1[41] & 0xff) % 35] & 0xff)) / 3;

        buffer1[140] += (byte) weird_ror8((byte) 92, (buffer1[5] & 0xff) & 7);

        // Is this as simple as it could be?
        buffer2[12] += ((((~(buffer1[4] & 0xff)) ^ (buffer2[(buffer1[12] & 0xff) % 35] & 0xff))
                | (buffer1[182] & 0xff)) & 192) | (((~(buffer1[4] & 0xff)) ^ (buffer2[(buffer1[12] & 0xff) % 35] & 0xff)) & (buffer1[182] & 0xff));

        buffer1[36] += 125;

        buffer1[124] = (byte) rol8x(((((74 & (buffer1[138] & 0xff)) | ((74 | (buffer1[138] & 0xff)) & (buffer0[15] & 0xff)))
                & (buffer0[(buffer1[43] & 0xff) % 20] & 0xff)) | (((74 & (buffer1[138] & 0xff)) | ((74 | (buffer1[138] & 0xff)) & (buffer0[15] & 0xff))
                | (buffer0[(buffer1[43] & 0xff) % 20] & 0xff)) & 95)), 4);

        buffer3[8] = (byte) ((((((buffer0[(buffer3[4] & 0xff) % 20] & 0xff) & 95)) & (((buffer4[(buffer1[68] & 0xff) % 21] & 0xff) & 46) << 1)) | 16) ^ 92);

        A = (buffer1[177] & 0xff) + (buffer4[(buffer1[79] & 0xff) % 21] & 0xff);
        D = (((A >> 1) | ((3 * (buffer1[148] & 0xff)) / 5)) & (buffer2[1] & 0xff)) | ((A >> 1) & ((3 * (buffer1[148] & 0xff)) / 5));
        buffer3[12] = (byte) (-34 - D);


        A = 8 - (((buffer2[22] & 0xff) & 7));     // FIXME: buffer2[22] = 74, so A is always 6 and B^C is just ror8(buffer1[33], 6)
        B = ((buffer1[33] & 0xff) >> (A & 7));
        C = (buffer1[33] & 0xff) << ((buffer2[22] & 0xff) & 7);
        buffer2[16] += (((buffer2[(buffer3[0] & 0xff) % 35] & 0xff) & 159) | (buffer0[(buffer3[4] & 0xff) % 20] & 0xff) | 8) - ((B ^ C) | 128);

        // This one was very easy so I just skipped ahead and did it
        buffer0[14] ^= (buffer2[(buffer3[12] & 0xff) % 35] & 0xff);

        // Monster goes here
        A = weird_rol8((buffer4[(buffer0[(buffer1[201] & 0xff) % 20] & 0xff) % 21] & 0xff), (((buffer2[(buffer1[112] & 0xff) % 35] & 0xff) << 1) & 7));
        D = (buffer0[(buffer1[208] & 0xff) % 20] & 131) | ((buffer0[(buffer1[164] & 0xff) % 20] & 0xff) & 124);
        buffer1[19] += (A & (D / 5)) | ((A | (D / 5)) & 37);

        buffer2[8] = (byte) (weird_ror8(140, (((buffer4[(buffer1[45] & 0xff) % 21] & 0xff) + 92) * ((buffer4[(buffer1[45] & 0xff) % 21] & 0xff) + 92)) & 7) & 0xff);

        buffer1[190] = 56;

        buffer2[8] ^= (buffer3[0] & 0xff);

        buffer1[53] = (byte) ~(((buffer0[(buffer1[83] & 0xff) % 20] & 0xff) | 204) / 5);

        buffer0[13] += (buffer0[(buffer1[41] & 0xff) % 20] & 0xff);

        buffer0[10] = (byte) ((((buffer2[(buffer3[0] & 0xff) % 35] & 0xff) & (buffer1[2] & 0xff)) | (((buffer2[(buffer3[0] & 0xff) % 35] & 0xff) | (buffer1[2] & 0xff)) & (buffer3[12] & 0xff))) / 15);

        A = (((56 | ((buffer4[(buffer1[2] & 0xff) % 21] & 0xff) & 68)) | (buffer2[(buffer3[8] & 0xff) % 35] & 0xff)) & 42) | ((((buffer4[(buffer1[2] & 0xff) % 21] & 0xff) & 68) | 56) & (buffer2[(buffer3[8] & 0xff) % 35] & 0xff));
        buffer3[16] = (byte) ((A * A) + 110);

        buffer3[20] = (byte) (202 - (buffer3[16] & 0xff));

        buffer3[24] = buffer1[151];

        buffer2[13] ^= buffer4[(buffer3[0] & 0xff) % 21];

        B = (((buffer2[(buffer1[179] & 0xff) % 35] & 0xff) - 38) & 177) | ((buffer3[12] & 0xff) & 177);
        C = (((buffer2[(buffer1[179] & 0xff) % 35] & 0xff) - 38)) & (buffer3[12] & 0xff);
        buffer3[28] = (byte) (30 + ((B | C) * (B | C)));

        buffer3[32] = (byte) (buffer3[28] + 62);

        // eek
        A = (((buffer3[20] & 0xff) + ((buffer3[0] & 0xff) & 74)) | ~(buffer4[(buffer3[0] & 0xff) % 21] & 0xff)) & 121;
        B = (((buffer3[20] & 0xff) + ((buffer3[0] & 0xff) & 74)) & ~(buffer4[(buffer3[0] & 0xff) % 21] & 0xff));
        tmp3 = (A | B);
        C = ((((A | B) ^ 0xffffffa6) | (buffer3[0] & 0xff)) & 4) | (((A | B) ^ 0xffffffa6) & (buffer3[0] & 0xff));
        buffer1[47] = (byte) (((buffer2[(buffer1[89] & 0xff) % 35] & 0xff) + C) ^ (buffer1[47] & 0xff));

        buffer3[36] = (byte) (((rol8((byte) ((tmp & 179) + 68), 2) & (buffer0[3] & 0xff)) | (tmp2 & ~(buffer0[3] & 0xff))) - 15);

        buffer1[123] ^= 221;

        A = (((buffer4[(buffer3[0] & 0xff) % 21]) & 0xff) / 3) - (buffer2[(buffer3[4] & 0xff) % 35] & 0xff);
        C = (((buffer3[0] & 163) + 92) & 246) | (buffer3[0] & 92);
        E = ((C | buffer3[24]) & 54) | (C & buffer3[24]);
        buffer3[40] = (byte) (A - E);

        buffer3[44] = (byte) (tmp3 ^ 81 ^ ((((buffer3[0] & 0xff) >> 1) & 101) + 26));

        buffer3[48] = (byte) ((buffer2[(buffer3[4] & 0xff) % 35] & 0xff) & 27);

        buffer3[52] = 27;

        buffer3[56] = (byte) 199;

        // caffeine
        buffer3[64] = (byte) ((buffer3[4] & 0xff) + ((((((((buffer3[40] & 0xff) | (buffer3[24] & 0xff)) & 177) | ((buffer3[40] & 0xff) & (buffer3[24] & 0xff)))
                & (((((buffer4[(buffer3[0] & 0xff) % 20] & 0xff) & 177) | 176)) | (((buffer4[(buffer3[0] & 0xff) % 21] & 0xff)) & ~3)))
                | (((((buffer3[40] & 0xff) & (buffer3[24] & 0xff)) | (((buffer3[40] & 0xff) | (buffer3[24] & 0xff)) & 177)) & 199)
                | ((((((buffer4[(buffer3[0] & 0xff) % 21] & 0xff) & 1) & 0xff) + 176) | ((buffer4[(buffer3[0] & 0xff) % 21] & 0xff) & ~3))
                & (buffer3[56] & 0xff)))) & (~(buffer3[52] & 0xff))) | (buffer3[48] & 0xff)));

        buffer2[33] ^= buffer1[26];

        buffer1[106] ^= buffer3[20] ^ 133;

        buffer2[30] = (byte) ((((buffer3[64] & 0xff) / 3) - (275 | ((buffer3[0] & 0xff) & 247))) ^ (buffer0[(buffer1[122] & 0xff) % 20] & 0xff));

        buffer1[22] = (byte) (((buffer2[(buffer1[90] & 0xff) % 35] & 0xff) & 95) | 68);

        A = ((buffer4[(buffer3[36] & 0xff) % 21] & 0xff) & 184) | ((buffer2[(buffer3[44] & 0xff) % 35] & 0xff) & ~184);
        buffer2[18] += ((A * A * A) >> 1);

        buffer2[5] -= (buffer4[(buffer1[92] & 0xff) % 21] & 0xff);

        A = ((((buffer1[41] & 0xff) & ~24) | ((buffer2[(buffer1[183] & 0xff) % 35] & 0xff) & 24)) & ((buffer3[16] & 0xff) + 53)) | (buffer3[20] & (buffer2[(buffer3[20] & 0xff) % 35] & 0xff));
        B = ((buffer1[17] & 0xff) & (~(buffer3[44] & 0xff))) | ((buffer0[(buffer1[59] & 0xff) % 20] & 0xff) & (buffer3[44] & 0xff));
        buffer2[18] ^= (A * B);

        A = weird_ror8((buffer1[11] & 0xff), (buffer2[(buffer1[28] & 0xff) % 35] & 0xff) & 7) & 7;
        B = ((((buffer0[(buffer1[93] & 0xff) % 20] & 0xff) & ~(buffer0[14] & 0xff)) | ((buffer0[14] & 0xff) & 150)) & ~28) | ((buffer1[7] & 0xff) & 28);
        buffer2[22] = (byte) (((((B | weird_rol8((buffer2[(buffer3[0] & 0xff) % 35] & 0xff), A)) & (buffer2[33] & 0xff)) | (B & weird_rol8((buffer2[(buffer3[0] & 0xff) % 35] & 0xff), A))) + 74) & 0xff);

        A = buffer4[((buffer0[(buffer1[39] & 0xff) % 20] & 0xff) ^ 217) % 21] & 0xff;
        buffer0[15] -= (((((buffer3[20] & 0xff) | (buffer3[0] & 0xff)) & 214) | ((buffer3[20] & 0xff) & (buffer3[0] & 0xff))) & A)
                | (((((buffer3[20] & 0xff) | (buffer3[0] & 0xff)) & 214) | ((buffer3[20] & 0xff) & (buffer3[0] & 0xff)) | A) & (buffer3[32] & 0xff));

        // We need to save T here, and boy is it complicated to calculate!
        B = (((buffer2[(buffer1[57] & 0xff) % 35] & buffer0[(buffer3[64] & 0xff) % 20]) | ((buffer0[(buffer3[64] & 0xff) % 20] | buffer2[(buffer1[57] & 0xff) % 35]) & 95) | (buffer3[64] & 45) | 82) & 32);
        C = ((buffer2[(buffer1[57] & 0xff) % 35] & buffer0[(buffer3[64] & 0xff) % 20]) | ((buffer2[(buffer1[57] & 0xff) % 35] | buffer0[(buffer3[64] & 0xff) % 20]) & 95)) & ((buffer3[64] & 45) | 82);
        D = (((((buffer3[0] & 0xff) / 3) - ((buffer3[64] & 0xff) | (buffer1[22] & 0xff)))) ^ ((buffer3[28] & 0xff) + 62) ^ ((B | C)));
        T = (buffer0[(D & 0xff) % 20] & 0xff);

        buffer3[68] = (byte) (((buffer0[(buffer1[99] & 0xff) % 20] & 0xff)
                * (buffer0[(buffer1[99] & 0xff) % 20] & 0xff)
                * (buffer0[(buffer1[99] & 0xff) % 20] & 0xff)
                * (buffer0[(buffer1[99] & 0xff) % 20] & 0xff))
                | (buffer2[(buffer3[64] & 0xff) % 35] & 0xff));

        U = buffer0[(buffer1[50] & 0xff) % 20] & 0xff; // this is also v100
        W = buffer2[(buffer1[138] & 0xff) % 35] & 0xff;
        X = buffer4[(buffer1[39] & 0xff) % 21] & 0xff;
        Y = buffer0[(buffer1[4] & 0xff) % 20] & 0xff; // this is also v120
        Z = buffer4[(buffer1[202] & 0xff) % 21] & 0xff; // also v124
        V = buffer0[(buffer1[151] & 0xff) % 20] & 0xff;
        S = buffer2[(buffer1[14] & 0xff) % 35] & 0xff;
        R = buffer0[(buffer1[145] & 0xff) % 20] & 0xff;

        A = ((buffer2[(buffer3[68] & 0xff) % 35] & 0xff) & (buffer0[(buffer1[209] & 0xff) % 20] & 0xff)) | (((buffer2[(buffer3[68] & 0xff) % 35] & 0xff) | (buffer0[(buffer1[209] & 0xff) % 20] & 0xff)) & 24);
        B = weird_rol8((buffer4[(buffer1[127] & 0xff) % 21] & 0xff), (buffer2[(buffer3[68] & 0xff) % 35] & 0xff) & 7);
        C = (A & (buffer0[10] & 0xff)) | (B & ~(buffer0[10] & 0xff));
        D = 7 ^ ((buffer4[(buffer2[(buffer3[36] & 0xff) % 35] & 0xff) % 21] & 0xff) << 1);
        buffer3[72] = (byte) ((C & 71) | (D & ~71));

        buffer2[2] += ((((buffer0[(buffer3[20] & 0xff) % 20] & 0xff) << 1) & 159)
                | ((buffer4[(buffer1[190] & 0xff) % 21] & 0xff) & ~159))
                & (((((buffer4[(buffer3[64] & 0xff) % 21] & 0xff) & 110)
                | ((buffer0[(buffer1[25] & 0xff) % 20] & 0xff) & ~110)) & ~150) | ((buffer1[25] & 0xff) & 150));

        buffer2[14] -= (((buffer2[(buffer3[20] & 0xff) % 35] & 0xff) & ((buffer3[72] & 0xff) ^ (buffer2[(buffer1[100] & 0xff) % 35] & 0xff))) & ~34) | ((buffer1[97] & 0xff) & 34);

        buffer0[17] = 115;

        buffer1[23] ^= (((((((buffer4[(buffer1[17] & 0xff) % 21] & 0xff) | (buffer0[(buffer3[20] & 0xff) % 20] & 0xff)) & (buffer3[72] & 0xff))
                | ((buffer4[(buffer1[17] & 0xff) % 21] & 0xff) & (buffer0[(buffer3[20] & 0xff) % 20] & 0xff))) & ((buffer1[50] & 0xff) / 3)) |
                (((((buffer4[(buffer1[17] & 0xff) % 21] & 0xff) | (buffer0[(buffer3[20] & 0xff) % 20] & 0xff)) & (buffer3[72] & 0xff))
                        | ((buffer4[(buffer1[17] & 0xff) % 21] & 0xff) & buffer0[(buffer3[20] & 0xff) % 20]) | ((buffer1[50] & 0xff) / 3)) & 246)) << 1);

        buffer0[13] = (byte) (((((((buffer0[(buffer3[40] & 0xff) % 20] & 0xff) | (buffer1[10] & 0xff)) & 82)
                | ((buffer0[(buffer3[40] & 0xff) % 20] & 0xff) & (buffer1[10] & 0xff))) & 209) |
                (((buffer0[(buffer1[39] & 0xff) % 20] & 0xff) << 1) & 46)) >> 1);

        buffer2[33] -= buffer1[113] & 9;

        buffer2[28] -= ((((2 | (buffer1[110] & 222)) >> 1) & ~223) | (buffer3[20] & 223));

        J = weird_rol8((V | Z), (U & 7));                   // OK
        A = ((buffer2[16] & 0xff) & T) | (W & (~(buffer2[16] & 0xff)));
        B = ((buffer1[33] & 0xff) & 17) | (X & ~17);
        E = ((Y | ((A + B) / 5)) & 147) |
                (Y & ((A + B) / 5));                                   // OK
        M = ((buffer3[40] & 0xff) & (buffer4[(((buffer3[8] & 0xff) + J + E) & 0xff) % 21] & 0xff)) |
                (((buffer3[40] & 0xff) | (buffer4[(((buffer3[8] & 0xff) + J + E) & 0xff) % 21] & 0xff)) & (buffer2[23] & 0xff));

        buffer0[15] = (byte) (((((buffer4[(buffer3[20] & 0xff) % 21] & 0xff) - 48) & (~(buffer1[184] & 0xff))) | (((buffer4[(buffer3[20] & 0xff) % 21] & 0xff) - 48) & 189) | (189 & ~(buffer1[184] & 0xff))) & (M * M * M));

        buffer2[22] += buffer1[183];

        buffer3[76] = (byte) ((3 * buffer4[(buffer1[1] & 0xff) % 21]) ^ buffer3[0]);

        A = buffer2[(((buffer3[8] & 0xff) + (J + E)) & 0xff) % 35] & 0xff;
        F = ((((buffer4[(buffer1[178] & 0xff) % 21] & 0xff) & A) | (((buffer4[(buffer1[178] & 0xff) % 21] & 0xff) | A) & 209)) * (buffer0[(buffer1[13] & 0xff) % 20] & 0xff)) * ((buffer4[(buffer1[26] & 0xff) % 21] & 0xff) >> 1);
        G = (F + 0x733ffff9) * 198 - (((F + 0x733ffff9) * 396 + 212) & 212) + 85;
        buffer3[80] = (byte) ((buffer3[36] & 0xff) + (G ^ 148) + ((G ^ 107) << 1) - 127);

        buffer3[84] = (byte) ((((buffer2[(buffer3[64] & 0xff) % 35] & 0xff)) & 245) | ((buffer2[(buffer3[20] & 0xff) % 35] & 0xff) & 10));

        A = (buffer0[(buffer3[68] & 0xff) % 20] & 0xff) | 81;
        buffer2[18] -= ((A * A * A) & ~buffer0[15]) | (((buffer3[80] & 0xff) / 15) & (buffer0[15] & 0xff));

        buffer3[88] = (byte) ((buffer3[8] & 0xff) + J + E - (buffer0[(buffer1[160] & 0xff) % 20] & 0xff)
                + ((buffer4[(buffer0[((buffer3[8] + J + E) & 255) % 20] & 0xff) % 21] & 0xff) / 3));

        B = ((R ^ (buffer3[72] & 0xff)) & ~198) | ((S * S) & 198);
        F = ((buffer4[(buffer1[69] & 0xff) % 21] & 0xff) & (buffer1[172] & 0xff))
                | (((buffer4[(buffer1[69] & 0xff) % 21] & 0xff) | (buffer1[172] & 0xff)) & (((buffer3[12] & 0xff) - B) + 77));
        buffer0[16] = (byte) (147 - (((buffer3[72] & 0xff) & ((F & 251) | 1)) | (((F & 250) | (buffer3[72] & 0xff)) & 198)));

        C = ((buffer4[(buffer1[168] & 0xff) % 21] & 0xff) & buffer0[(buffer1[29] & 0xff) % 20] & 7) | ((buffer4[(buffer1[168] & 0xff) % 21] | buffer0[(buffer1[29] & 0xff) % 20]) & 6);
        F = ((buffer4[(buffer1[155] & 0xff) % 21] & 0xff) & (buffer1[105] & 0xff)) | (((buffer4[(buffer1[155] & 0xff) % 21] & 0xff) | (buffer1[105] & 0xff)) & 141);
        buffer0[3] -= buffer4[weird_rol32(F, C) % 21];

        buffer1[5] = (byte) (weird_ror8((byte) (buffer0[12] & 0xff), (((buffer0[(buffer1[61] & 0xff) % 20] & 0xff) / 5) & 7)) ^ (((~buffer2[(buffer3[84] & 0xff) % 35]) & 0xffffffffL) / 5));

        buffer1[198] += buffer1[3];

        A = (162 | (buffer2[(buffer3[64] & 0xff) % 35] & 0xff));
        buffer1[164] += ((A * A) / 5);

        G = weird_ror8(139, ((buffer3[80] & 0xff) & 7));
        C = (((buffer4[(buffer3[64] & 0xff) % 21] & 0xff) * (buffer4[(buffer3[64] & 0xff) % 21] & 0xff) * (buffer4[(buffer3[64] & 0xff) % 21] & 0xff)) & 95) | ((buffer0[(buffer3[40] & 0xff) % 20] & 0xff) & ~95);
        buffer3[92] = (byte) ((G & 12) | ((buffer0[(buffer3[20] & 0xff) % 20] & 0xff) & 12) | (G & (buffer0[(buffer3[20] & 0xff) % 20] & 0xff)) | C);

        buffer2[12] += (((buffer1[103] & 0xff) & 32) | ((buffer3[92] & 0xff) & (((buffer1[103] & 0xff) | 60))) | 16) / 3;

        buffer3[96] = buffer1[143];

        buffer3[100] = 27;

        buffer3[104] = (byte) (((((buffer3[40] & 0xff) & ~(buffer2[8] & 0xff)) | ((buffer1[35] & 0xff) & (buffer2[8] & 0xff))) & (buffer3[64] & 0xff)) ^ 119);

        buffer3[108] = (byte) (238 & (((((buffer3[40] & 0xff) & ~(buffer2[8] & 0xff)) | ((buffer1[35] & 0xff) & (buffer2[8] & 0xff))) & (buffer3[64] & 0xff)) << 1));

        buffer3[112] = (byte) ((~(buffer3[64] & 0xff) & ((buffer3[84] & 0xff) / 3)) ^ 49);

        buffer3[116] = (byte) (98 & ((~(buffer3[64] & 0xff) & ((buffer3[84] & 0xff) / 3)) << 1));

        // finale
        A = ((buffer1[35] & 0xff) & (buffer2[8] & 0xff)) | ((buffer3[40] & 0xff) & ~(buffer2[8] & 0xff));
        B = (A & buffer3[64]) | ((((buffer3[84] & 0xff) / 3) & ~(buffer3[64] & 0xff)));
        buffer1[143] = (byte) ((buffer3[96] & 0xff) - ((B & (86 + (((buffer1[172] & 0xff) & 64) >> 1)))
                | ((((((buffer1[172] & 0xff) & 65) >> 1) ^ 86) | ((~(buffer3[64] & 0xff) & ((buffer3[84] & 0xff) / 3))
                | ((((buffer3[40] & 0xff) & ~(buffer2[8] & 0xff)) | ((buffer1[35] & 0xff) & (buffer2[8] & 0xff))) & (buffer3[64] & 0xff)))) & (buffer3[100] & 0xff))));

        buffer2[29] = (byte) 162;

        A = (((((buffer4[(buffer3[88] & 0xff) % 21] & 0xff)) & 160) | ((buffer0[(buffer1[125] & 0xff) % 20] & 0xff) & 95)) >> 1);
        B = (buffer2[(buffer1[149] & 0xff) % 35] & 0xff) ^ ((buffer1[43] & 0xff) * (buffer1[43] & 0xff));

        buffer0[15] += (B & A) | ((A | B) & 115);

        buffer3[120] = (byte) ((buffer3[64] & 0xff) - (buffer0[(buffer3[40] & 0xff) % 20] & 0xff));

        buffer1[95] = buffer4[(buffer3[20] & 0xff) % 21];

        A = weird_ror8((buffer2[(buffer3[80] & 0xff) % 35] & 0xff), ((buffer2[(buffer1[17] & 0xff) % 35] & 0xff)
                * (buffer2[(buffer1[17] & 0xff) % 35] & 0xff) * (buffer2[(buffer1[17] & 0xff) % 35] & 0xff)) & 7);
        buffer0[7] -= ((A * A));

        buffer2[8] = (byte) ((buffer2[8] & 0xff) - (buffer1[184] & 0xff) + ((buffer4[(buffer1[202] & 0xff) % 21] & 0xff) * (buffer4[(buffer1[202] & 0xff) % 21] & 0xff) * (buffer4[(buffer1[202] & 0xff) % 21] & 0xff)));

        buffer0[16] = (byte) (((buffer2[(buffer1[102] & 0xff) % 35] & 0xff) << 1) & 132);

        buffer3[124] = (byte) (((buffer4[(buffer3[40] & 0xff) % 21] & 0xff) >> 1) ^ (buffer3[68] & 0xff));

        buffer0[7] -= ((buffer0[(buffer1[191] & 0xff) % 20] & 0xff) - ((((buffer4[(buffer1[80] & 0xff) % 21] & 0xff) << 1) & ~177) | ((buffer4[(buffer4[(buffer3[88] & 0xff) % 21] & 0xff) % 21] & 0xff) & 177)));

        buffer0[6] = buffer0[(buffer1[119] & 0xff) % 20];

        A = (buffer4[(buffer1[190] & 0xff) % 21] & ~209) | (buffer1[118] & 209);
        B = buffer0[(buffer3[120] & 0xff) % 20] * buffer0[(buffer3[120] & 0xff) % 20];
        buffer0[12] = (byte) ((buffer0[(buffer3[84] & 0xff) % 20] ^ (buffer2[(buffer1[71] & 0xff) % 35] + buffer2[(buffer1[15] & 0xff) % 35])) & ((A & B) | ((A | B) & 27)));

        B = ((buffer1[32] & 0xff) & (buffer2[(buffer3[88] & 0xff) % 35] & 0xff)) | (((buffer1[32] & 0xff) | (buffer2[(buffer3[88] & 0xff) % 35] & 0xff)) & 23);
        D = ((((buffer4[(buffer1[57] & 0xff) % 21] & 0xff) * 231) & 169) | (B & 86));
        F = ((((buffer0[(buffer1[82] & 0xff) % 20] & 0xff) & ~29) | ((buffer4[(buffer3[124] & 0xff) % 21] & 0xff) & 29)) & 190) | ((buffer4[(D / 5) % 21] & 0xff) & ~190);
        H = (buffer0[(buffer3[40] & 0xff) % 20] & 0xff) * (buffer0[(buffer3[40] & 0xff) % 20] & 0xff) * (buffer0[(buffer3[40] & 0xff) % 20] & 0xff);
        K = (H & (buffer1[82] & 0xff)) | (H & 92) | ((buffer1[82] & 0xff) & 92);
        buffer3[128] = (byte) (((F & K) | ((F | K) & 192)) ^ (D / 5));

        buffer2[25] ^= (((buffer0[(buffer3[120] & 0xff) % 20] & 0xff) << 1) * (buffer1[5] & 0xff)) - (weird_rol8((buffer3[76] & 0xff), ((buffer4[(buffer3[124] & 0xff) % 21] & 0xff) & 7)) & ((buffer3[20] & 0xff) + 110));
    }

    private byte rol8(byte input, int count) {
        return (byte) (((input << count) & 0xff) | (input & 0xff) >> (8 - count));
    }

    private int rol8x(int input, int count) {
        return ((input << count)) | (input) >> (8 - count);
    }

    private int weird_ror8(int input, int count) {
        if (count == 0)
            return 0;
        return ((input >> count) & 0xff) | (input & 0xff) << (8 - count);

    }

    private int weird_rol8(int input, int count) {
        if (count == 0)
            return 0;
        return ((input << count) & 0xff) | (input & 0xff) >> (8 - count);
    }

    private int weird_rol32(int input, int count) {
        if (count == 0)
            return 0;
        return (input << count) ^ (input >> (8 - count));
    }
}
