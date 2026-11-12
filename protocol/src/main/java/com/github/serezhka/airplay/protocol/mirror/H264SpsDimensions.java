package com.github.serezhka.airplay.protocol.mirror;

import java.util.Optional;

/**
 * Reads coded picture width/height (including frame crop) from an H.264 SPS NAL unit.
 * Used so AirPlay mirror codec headers match the bitstream some receivers enforce strictly.
 */
public final class H264SpsDimensions {

    private H264SpsDimensions() {
    }

    /**
     * @param spsNal complete SPS NAL (first byte is NAL header with type 7)
     */
    public static Optional<Dimensions> parse(byte[] spsNal) {
        if (spsNal == null || spsNal.length < 4) {
            return Optional.empty();
        }
        if ((spsNal[0] & 0x1f) != 7) {
            return Optional.empty();
        }
        byte[] rbsp = nalToRbsp(spsNal, 1);
        if (rbsp.length < 3) {
            return Optional.empty();
        }
        try {
            BitReader br = new BitReader(rbsp);
            int profileIdc = br.readBits(8);
            /* constraint_set0..5, reserved_zero_2bits */
            br.readBits(8);
            /* level_idc */
            br.readBits(8);
            /* seq_parameter_set_id */
            br.readUe();
            int chromaFormatIdc = 1;
            if (profileIdc == 100
                    || profileIdc == 110
                    || profileIdc == 122
                    || profileIdc == 244
                    || profileIdc == 44
                    || profileIdc == 83
                    || profileIdc == 86
                    || profileIdc == 118
                    || profileIdc == 128
                    || profileIdc == 138
                    || profileIdc == 139
                    || profileIdc == 134
                    || profileIdc == 153) {
                chromaFormatIdc = br.readUe();
                if (chromaFormatIdc == 3) {
                    br.readBit();
                }
                br.readUe();
                br.readUe();
                br.readBit();
                if (br.readBit() == 1) {
                    int limit = chromaFormatIdc == 3 ? 12 : 8;
                    for (int i = 0; i < limit; i++) {
                        if (br.readBit() == 1) {
                            int size = i < 6 ? 16 : 64;
                            skipScalingList(br, size);
                        }
                    }
                }
            }
            br.readUe();
            int picOrderCntType = br.readUe();
            if (picOrderCntType == 0) {
                br.readUe();
            } else if (picOrderCntType == 1) {
                br.readBit();
                int num = br.readUe();
                for (int i = 0; i <= num; i++) {
                    br.readSe();
                    br.readSe();
                    br.readSe();
                }
            }
            br.readUe();
            br.readBit();
            int picWidthInMbsMinus1 = br.readUe();
            int picHeightInMapUnitsMinus1 = br.readUe();
            int frameMbsOnly = br.readBit();
            br.readBit();
            int frameCropping = br.readBit();
            int cropLeft = 0;
            int cropRight = 0;
            int cropTop = 0;
            int cropBottom = 0;
            if (frameCropping == 1) {
                cropLeft = br.readUe();
                cropRight = br.readUe();
                cropTop = br.readUe();
                cropBottom = br.readUe();
            }
            int subWidthC = chromaFormatIdc == 1 || chromaFormatIdc == 2 ? 2 : 1;
            int subHeightC = chromaFormatIdc == 1 ? 2 : 1;

            int picWidthInSamples = (picWidthInMbsMinus1 + 1) * 16;
            int picHeightInMbs = (2 - frameMbsOnly) * (picHeightInMapUnitsMinus1 + 1);
            int picHeightInSamples = picHeightInMbs * 16;

            int cropUnitX = subWidthC;
            int cropUnitY = subHeightC * (2 - frameMbsOnly);

            int w = picWidthInSamples - (cropLeft + cropRight) * cropUnitX;
            int h = picHeightInSamples - (cropTop + cropBottom) * cropUnitY;
            if (w <= 0 || h <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Dimensions(w, h));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static void skipScalingList(BitReader br, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = br.readSe();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            if (nextScale != 0) {
                lastScale = nextScale;
            }
        }
    }

    private static byte[] nalToRbsp(byte[] nal, int headerLen) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(nal.length);
        int i = headerLen;
        while (i < nal.length) {
            if (i + 2 < nal.length && nal[i] == 0 && nal[i + 1] == 0 && nal[i + 2] == 3) {
                out.write(0);
                out.write(0);
                i += 3;
            } else {
                out.write(nal[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    public record Dimensions(int width, int height) {
    }

    private static final class BitReader {
        private final byte[] data;
        private int bitPos;

        private BitReader(byte[] data) {
            this.data = data;
        }

        private int readBit() {
            if (bitPos >= data.length * 8) {
                throw new IllegalStateException("eof");
            }
            int byteIndex = bitPos / 8;
            int bitInByte = 7 - (bitPos % 8);
            bitPos++;
            return (data[byteIndex] >> bitInByte) & 1;
        }

        private int readBits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | readBit();
            }
            return v;
        }

        private int readUe() {
            int zeros = 0;
            while (readBit() == 0) {
                zeros++;
                if (zeros > 31) {
                    throw new IllegalStateException("ue overflow");
                }
            }
            return (1 << zeros) - 1 + (zeros > 0 ? readBits(zeros) : 0);
        }

        private int readSe() {
            int codeNum = readUe();
            int k = codeNum + 1;
            return (k % 2 == 0) ? k / 2 : -(k / 2);
        }
    }
}
