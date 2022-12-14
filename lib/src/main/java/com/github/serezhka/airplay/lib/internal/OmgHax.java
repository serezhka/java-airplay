package com.github.serezhka.airplay.lib.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static com.github.serezhka.airplay.lib.internal.OmgHaxConst.*;

public class OmgHax {

    private final ModifiedMD5 modifiedMD5 = new ModifiedMD5();
    private final SapHash sapHash = new SapHash();

    void decryptAesKey(byte[] message3, byte[] cipherText, byte[] keyOut) {
        byte[] chunk1 = Arrays.copyOfRange(cipherText, 16, cipherText.length);
        byte[] chunk2 = Arrays.copyOfRange(cipherText, 56, cipherText.length);
        byte[] blockIn = new byte[16];
        byte[] sapKey = new byte[16];
        int[][] key_schedule = new int[11][4];
        generate_session_key(default_sap, message3, sapKey);
        generate_key_schedule(sapKey, key_schedule);
        z_xor(chunk2, blockIn, 1);
        cycle(blockIn, key_schedule);
        for (int i = 0; i < 16; i++) {
            keyOut[i] = (byte) (blockIn[i] ^ chunk1[i]);
        }
        x_xor(keyOut, keyOut, 1);
        z_xor(keyOut, keyOut, 1);
    }

    void decryptMessage(byte[] messageIn, byte[] decryptedMessage) {
        byte[] buffer = new byte[16];
        byte tmp;
        int mode = messageIn[12];  // 0,1,2,3

        // For M0-M6 we follow the same pattern
        for (int i = 0; i < 8; i++) {
            // First, copy in the nth block (we must start with the last one)
            for (int j = 0; j < 16; j++) {
                if (mode == 3) {
                    buffer[j] = messageIn[(0x80 - 0x10 * i) + j];
                } else if (mode == 2 || mode == 1 || mode == 0) {
                    buffer[j] = messageIn[(0x10 * (i + 1)) + j];
                }
            }
            // do this permutation and update 9 times. Could this be cycle(), or the reverse of cycle()?
            for (int j = 0; j < 9; j++) {
                int base = 0x80 - 0x10 * j;

                buffer[0x0] = (byte) (message_table_index(base + 0x0)[buffer[0x0] & 0xFF] ^ message_key[mode][base + 0x0]);
                buffer[0x4] = (byte) (message_table_index(base + 0x4)[buffer[0x4] & 0xFF] ^ message_key[mode][base + 0x4]);
                buffer[0x8] = (byte) (message_table_index(base + 0x8)[buffer[0x8] & 0xFF] ^ message_key[mode][base + 0x8]);
                buffer[0xc] = (byte) (message_table_index(base + 0xc)[buffer[0xc] & 0xFF] ^ message_key[mode][base + 0xc]);

                tmp = buffer[0x0d];
                buffer[0xd] = (byte) (message_table_index(base + 0xd)[buffer[0x9] & 0xFF] ^ message_key[mode][base + 0xd]);
                buffer[0x9] = (byte) (message_table_index(base + 0x9)[buffer[0x5] & 0xFF] ^ message_key[mode][base + 0x9]);
                buffer[0x5] = (byte) (message_table_index(base + 0x5)[buffer[0x1] & 0xFF] ^ message_key[mode][base + 0x5]);
                buffer[0x1] = (byte) (message_table_index(base + 0x1)[tmp & 0xFF] ^ message_key[mode][base + 0x1]);

                tmp = buffer[0x02];
                buffer[0x2] = (byte) (message_table_index(base + 0x2)[buffer[0xa] & 0xFF] ^ message_key[mode][base + 0x2]);
                buffer[0xa] = (byte) (message_table_index(base + 0xa)[tmp & 0xFF] ^ message_key[mode][base + 0xa]);
                tmp = buffer[0x06];
                buffer[0x6] = (byte) (message_table_index(base + 0x6)[buffer[0xe] & 0xFF] ^ message_key[mode][base + 0x6]);
                buffer[0xe] = (byte) (message_table_index(base + 0xe)[tmp & 0xFF] ^ message_key[mode][base + 0xe]);

                tmp = buffer[0x3];
                buffer[0x3] = (byte) (message_table_index(base + 0x3)[buffer[0x7] & 0xFF] ^ message_key[mode][base + 0x3]);
                buffer[0x7] = (byte) (message_table_index(base + 0x7)[buffer[0xb] & 0xFF] ^ message_key[mode][base + 0x7]);
                buffer[0xb] = (byte) (message_table_index(base + 0xb)[buffer[0xf] & 0xFF] ^ message_key[mode][base + 0xb]);
                buffer[0xf] = (byte) (message_table_index(base + 0xf)[tmp & 0xFF] ^ message_key[mode][base + 0xf]);

                // Now we must replace the entire buffer with 4 words that we read and xor together

                ByteBuffer block = ByteBuffer.wrap(buffer);
                block.order(ByteOrder.LITTLE_ENDIAN);

                block.putInt(table_s9[0x000 + (buffer[0x0] & 0xFF)] ^
                        table_s9[0x100 + (buffer[0x1] & 0xFF)] ^
                        table_s9[0x200 + (buffer[0x2] & 0xFF)] ^
                        table_s9[0x300 + (buffer[0x3] & 0xFF)]);
                block.putInt(table_s9[0x000 + (buffer[0x4] & 0xFF)] ^
                        table_s9[0x100 + (buffer[0x5] & 0xFF)] ^
                        table_s9[0x200 + (buffer[0x6] & 0xFF)] ^
                        table_s9[0x300 + (buffer[0x7] & 0xFF)]);
                block.putInt(table_s9[0x000 + (buffer[0x8] & 0xFF)] ^
                        table_s9[0x100 + (buffer[0x9] & 0xFF)] ^
                        table_s9[0x200 + (buffer[0xa] & 0xFF)] ^
                        table_s9[0x300 + (buffer[0xb] & 0xFF)]);
                block.putInt(table_s9[0x000 + (buffer[0xc] & 0xFF)] ^
                        table_s9[0x100 + (buffer[0xd] & 0xFF)] ^
                        table_s9[0x200 + (buffer[0xe] & 0xFF)] ^
                        table_s9[0x300 + (buffer[0xf] & 0xFF)]);
            }
            // Next, another permute with a different table
            buffer[0x0] = table_s10[(0x0 << 8) + (buffer[0x0] & 0xFF)];
            buffer[0x4] = table_s10[(0x4 << 8) + (buffer[0x4] & 0xFF)];
            buffer[0x8] = table_s10[(0x8 << 8) + (buffer[0x8] & 0xFF)];
            buffer[0xc] = table_s10[(0xc << 8) + (buffer[0xc] & 0xFF)];

            tmp = buffer[0x0d];
            buffer[0xd] = table_s10[(0xd << 8) + (buffer[0x9] & 0xFF)];
            buffer[0x9] = table_s10[(0x9 << 8) + (buffer[0x5] & 0xFF)];
            buffer[0x5] = table_s10[(0x5 << 8) + (buffer[0x1] & 0xFF)];
            buffer[0x1] = table_s10[(0x1 << 8) + (tmp & 0xFF)];

            tmp = buffer[0x02];
            buffer[0x2] = table_s10[(0x2 << 8) + (buffer[0xa] & 0xFF)];
            buffer[0xa] = table_s10[(0xa << 8) + (tmp & 0xFF)];
            tmp = buffer[0x06];
            buffer[0x6] = table_s10[(0x6 << 8) + (buffer[0xe] & 0xFF)];
            buffer[0xe] = table_s10[(0xe << 8) + (tmp & 0xFF)];

            tmp = buffer[0x3];
            buffer[0x3] = table_s10[(0x3 << 8) + (buffer[0x7] & 0xFF)];
            buffer[0x7] = table_s10[(0x7 << 8) + (buffer[0xb] & 0xFF)];
            buffer[0xb] = table_s10[(0xb << 8) + (buffer[0xf] & 0xFF)];
            buffer[0xf] = table_s10[(0xf << 8) + (tmp & 0xFF)];

            // And finally xor with the previous block of the message, except in mode-2 where we do this in reverse
            byte[] xorResult = new byte[16];
            if (mode == 2 || mode == 1 || mode == 0) {
                if (i > 0) {
                    xor_blocks(buffer, Arrays.copyOfRange(messageIn, 0x10 * i, 0x10 * i + 16), xorResult); // remember that the first 0x10 bytes are the header
                    System.arraycopy(xorResult, 0, decryptedMessage, 0x10 * i, 16);
                } else {
                    xor_blocks(buffer, message_iv[mode], xorResult);
                    System.arraycopy(xorResult, 0, decryptedMessage, 0x10 * i, 16);
                }

            } else {
                if (i < 7) {
                    xor_blocks(buffer, Arrays.copyOfRange(messageIn, 0x70 - 0x10 * i, (0x70 - 0x10 * i) + 16), xorResult);
                    System.arraycopy(xorResult, 0, decryptedMessage, 0x70 - 0x10 * i, 16);
                } else {
                    xor_blocks(buffer, message_iv[mode], xorResult);
                    System.arraycopy(xorResult, 0, decryptedMessage, 0x70 - 0x10 * i, 16);
                }
            }
        }
    }

    void generate_key_schedule(byte[] key_material, int[][] key_schedule) {
        int[] key_data = new int[4];
        for (int i = 0; i < 11; i++) {
            key_schedule[i][0] = 0xdeadbeef;
            key_schedule[i][1] = 0xdeadbeef;
            key_schedule[i][2] = 0xdeadbeef;
            key_schedule[i][3] = 0xdeadbeef;
        }
        byte[] buffer = new byte[16];
        int ti = 0;
        // G
        t_xor(key_material, buffer);

        ByteBuffer wrap = ByteBuffer.wrap(buffer);
        wrap.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            key_data[i] = wrap.getInt();
        }

        for (int round = 0; round < 11; round++) {
            // H
            key_schedule[round][0] = key_data[0];
            // I
            byte[] table1 = table_index(ti);
            byte[] table2 = table_index(ti + 1);
            byte[] table3 = table_index(ti + 2);
            byte[] table4 = table_index(ti + 3);
            ti += 4;

            buffer[0] ^= table1[buffer[0x0d] & 0xFF] ^ index_mangle[round];
            buffer[1] ^= table2[buffer[0x0e] & 0xFF];
            buffer[2] ^= table3[buffer[0x0f] & 0xFF];
            buffer[3] ^= table4[buffer[0x0c] & 0xFF];

            key_data[0] = wrap.getInt(0);

            // H
            key_schedule[round][1] = key_data[1];
            // J
            key_data[1] ^= key_data[0];
            wrap.putInt(4, key_data[1]);
            // H
            key_schedule[round][2] = key_data[2];
            // J
            key_data[2] ^= key_data[1];
            wrap.putInt(8, key_data[2]);
            // K and L
            // Implement K and L to fill in other bits of the key schedule
            key_schedule[round][3] = key_data[3];
            // J again
            key_data[3] ^= key_data[2];
            wrap.putInt(12, key_data[3]);
        }
        for (int i = 0; i < 11; i++) {
            byte[] tmp = new byte[16];
            wrap = ByteBuffer.wrap(tmp);
            wrap.order(ByteOrder.LITTLE_ENDIAN);
            for (int j = 0; j < 4; j++) {
                wrap.putInt(key_schedule[i][j]);
            }

        }
    }

    void generate_session_key(byte[] oldSap, byte[] messageIn, byte[] sessionKey) {
        byte[] decryptedMessage = new byte[128];
        byte[] newSap = new byte[320];
        int round;
        byte[] md5 = new byte[16];

        decryptMessage(messageIn, decryptedMessage);

        System.arraycopy(static_source_1, 0, newSap, 0, 0x11);
        System.arraycopy(decryptedMessage, 0, newSap, 0x11, 0x80);
        System.arraycopy(oldSap, 0x80, newSap, 0x091, 0x80);
        System.arraycopy(static_source_2, 0, newSap, 0x111, 0x2f);
        System.arraycopy(initial_session_key, 0, sessionKey, 0, 16);

        for (round = 0; round < 5; round++) {
            byte[] base = Arrays.copyOfRange(newSap, round * 64, newSap.length);
            modifiedMD5.modified_md5(base, sessionKey, md5);
            sapHash.sap_hash(base, sessionKey);
            ByteBuffer md5Wrap = ByteBuffer.wrap(md5);
            md5Wrap.order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer sessionKeyWrap = ByteBuffer.wrap(sessionKey);
            sessionKeyWrap.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 4; i++) {
                sessionKeyWrap.putInt(i * 4, (int) ((sessionKeyWrap.getInt(i * 4) + md5Wrap.getInt(i * 4)) & 0xffffffffL));
            }
        }

        for (int i = 0; i < 16; i += 4) {
            byte tmp = sessionKey[i];
            sessionKey[i] = sessionKey[i + 3];
            sessionKey[i + 3] = tmp;
            tmp = sessionKey[i + 1];
            sessionKey[i + 1] = sessionKey[i + 2];
            sessionKey[i + 2] = tmp;
        }

        // Finally the whole thing is XORd with 121:
        for (int i = 0; i < 16; i++) {
            sessionKey[i] ^= 121;
        }
    }

    void cycle(byte[] block, int[][] key_schedule) {
        int ptr1, ptr2, ptr3, ptr4, ab;

        ByteBuffer bWords = ByteBuffer.wrap(block);
        bWords.order(ByteOrder.LITTLE_ENDIAN);
        bWords.putInt(0, bWords.getInt(0) ^ key_schedule[10][0]);
        bWords.putInt(4, bWords.getInt(4) ^ key_schedule[10][1]);
        bWords.putInt(8, bWords.getInt(8) ^ key_schedule[10][2]);
        bWords.putInt(12, bWords.getInt(12) ^ key_schedule[10][3]);
        // First, these are permuted
        permute_block_1(block);

        for (int round = 0; round < 9; round++) {
            // E
            // Note that table_s5 is a table of 4-byte words. Therefore we do not need to <<2 these indices
            // TODO: Are these just T-tables?

            byte[] key = new byte[16];
            ByteBuffer wrap = ByteBuffer.wrap(key);
            wrap.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 4; i++) {
                wrap.putInt(key_schedule[9 - round][i]);
            }

            ptr1 = table_s5[(block[3] & 0xff) ^ (key[3] & 0xff)];
            ptr2 = table_s6[(block[2] & 0xff) ^ (key[2] & 0xff)];
            ptr3 = table_s8[(block[0] & 0xff) ^ (key[0] & 0xff)];
            ptr4 = table_s7[(block[1] & 0xff) ^ (key[1] & 0xff)];

            // A B
            ab = ptr1 ^ ptr2 ^ ptr3 ^ ptr4;

            // C
            bWords.putInt(0, ab);

            ptr2 = table_s5[(block[7] & 0xff) ^ (key[7] & 0xff)];
            ptr1 = table_s6[(block[6] & 0xff) ^ (key[6] & 0xff)];
            ptr4 = table_s7[(block[5] & 0xff) ^ (key[5] & 0xff)];
            ptr3 = table_s8[(block[4] & 0xff) ^ (key[4] & 0xff)];
            // A B again
            ab = ptr1 ^ ptr2 ^ ptr3 ^ ptr4;

            // D is a bit of a nightmare, but it is really not as complicated as you might think
            bWords.putInt(4, ab);
            bWords.putInt(8, table_s5[(block[11] & 0xff) ^ (key[11] & 0xff)] ^
                    table_s6[(block[10] & 0xff) ^ (key[10] & 0xff)] ^
                    table_s7[(block[9] & 0xff) ^ (key[9] & 0xff)] ^
                    table_s8[(block[8] & 0xff) ^ (key[8] & 0xff)]);

            bWords.putInt(12, table_s5[(block[15] & 0xff) ^ (key[15] & 0xff)] ^
                    table_s6[(block[14] & 0xff) ^ (key[14] & 0xff)] ^
                    table_s7[(block[13] & 0xff) ^ (key[13] & 0xff)] ^
                    table_s8[(block[12] & 0xff) ^ (key[12] & 0xff)]);

            // In the last round, instead of the permute, we do F
            permute_block_2(block, 8 - round);
        }

        bWords.putInt(0, bWords.getInt(0) ^ key_schedule[0][0]);
        bWords.putInt(4, bWords.getInt(4) ^ key_schedule[0][1]);
        bWords.putInt(8, bWords.getInt(8) ^ key_schedule[0][2]);
        bWords.putInt(12, bWords.getInt(12) ^ key_schedule[0][3]);
    }

    private void xor_blocks(byte[] a, byte[] b, byte[] out) {
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
    }

    private void z_xor(byte[] in, byte[] out, int blocks) {
        for (int j = 0; j < blocks; j++) {
            for (int i = 0; i < 16; i++) {
                out[j * 16 + i] = (byte) (in[j * 16 + i] ^ z_key[i]);
            }
        }
    }

    private void x_xor(byte[] in, byte[] out, int blocks) {
        for (int j = 0; j < blocks; j++) {
            for (int i = 0; i < 16; i++) {
                out[j * 16 + i] = (byte) (in[j * 16 + i] ^ x_key[i]);
            }
        }
    }

    private void t_xor(byte[] in, byte[] out) {
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (in[i] ^ t_key[i]);
        }
    }

    private byte[] table_index(int i) {
        return Arrays.copyOfRange(table_s1, ((31 * i) % 0x28) << 8, table_s1.length);
    }

    private byte[] message_table_index(int i) {
        return Arrays.copyOfRange(table_s2, (97 * i % 144) << 8, table_s2.length);
    }

    private void permute_block_1(byte[] block) {
        block[0] = table_s3[block[0] & 0xff];
        block[4] = table_s3[0x400 + (block[4] & 0xff)];
        block[8] = table_s3[0x800 + (block[8] & 0xff)];
        block[12] = table_s3[0xc00 + (block[12] & 0xff)];

        byte tmp = block[13];
        block[13] = table_s3[0x100 + (block[9] & 0xff)];
        block[9] = table_s3[0xd00 + (block[5] & 0xff)];
        block[5] = table_s3[0x900 + (block[1] & 0xff)];
        block[1] = table_s3[0x500 + (tmp & 0xff)];

        tmp = block[2];
        block[2] = table_s3[0xa00 + (block[10] & 0xff)];
        block[10] = table_s3[0x200 + (tmp & 0xff)];
        tmp = block[6];
        block[6] = table_s3[0xe00 + (block[14] & 0xff)];
        block[14] = table_s3[0x600 + (tmp & 0xff)];

        tmp = block[3];
        block[3] = table_s3[0xf00 + (block[7] & 0xff)];
        block[7] = table_s3[0x300 + (block[11] & 0xff)];
        block[11] = table_s3[0x700 + (block[15] & 0xff)];
        block[15] = table_s3[0xb00 + (tmp & 0xff)];
    }

    private byte[] permute_table_2(int i) {
        return Arrays.copyOfRange(table_s4, ((71 * i) % 144) << 8, table_s4.length);
    }

    private void permute_block_2(byte[] block, int round) {
        block[0] = permute_table_2(round * 16 + 0)[(block[0] & 0xff)];
        block[4] = permute_table_2(round * 16 + 4)[(block[4] & 0xff)];
        block[8] = permute_table_2(round * 16 + 8)[(block[8] & 0xff)];
        block[12] = permute_table_2(round * 16 + 12)[(block[12] & 0xff)];

        byte tmp = block[13];
        block[13] = permute_table_2(round * 16 + 13)[(block[9] & 0xff)];
        block[9] = permute_table_2(round * 16 + 9)[(block[5] & 0xff)];
        block[5] = permute_table_2(round * 16 + 5)[(block[1] & 0xff)];
        block[1] = permute_table_2(round * 16 + 1)[(tmp & 0xff)];

        tmp = block[2];
        block[2] = permute_table_2(round * 16 + 2)[(block[10] & 0xff)];
        block[10] = permute_table_2(round * 16 + 10)[(tmp & 0xff)];
        tmp = block[6];
        block[6] = permute_table_2(round * 16 + 6)[(block[14] & 0xff)];
        block[14] = permute_table_2(round * 16 + 14)[(tmp & 0xff)];

        tmp = block[3];
        block[3] = permute_table_2(round * 16 + 3)[(block[7] & 0xff)];
        block[7] = permute_table_2(round * 16 + 7)[(block[11] & 0xff)];
        block[11] = permute_table_2(round * 16 + 11)[(block[15] & 0xff)];
        block[15] = permute_table_2(round * 16 + 15)[(tmp & 0xff)];
    }
}
