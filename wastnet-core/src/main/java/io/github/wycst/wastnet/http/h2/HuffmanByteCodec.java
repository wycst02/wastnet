/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.h2;
import java.util.Arrays;

/**
 * HTTP/2 HPACK Huffman codec for string encoding/decoding.
 *
 * @author wangyc
 */
public final class HuffmanByteCodec {

    // Masks for the number of bits
    private final static int[] MASKS = {0, 0x1, 0x3, 0x7, 0xF, 0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF};

    /** Huffman encode values (RFC 7541 raw hex, LSB-aligned), indexed by byte value (0-256), length 257 */
    private static final int[] ENCODE_VALUES = {0x1FF8, 0x7FFFD8, 0xFFFFFE2, 0xFFFFFE3, 0xFFFFFE4, 0xFFFFFE5, 0xFFFFFE6, 0xFFFFFE7, 0xFFFFFE8, 0xFFFFEA, 0x3FFFFFFC, 0xFFFFFE9, 0xFFFFFEA, 0x3FFFFFFD, 0xFFFFFEB, 0xFFFFFEC, 0xFFFFFED, 0xFFFFFEE, 0xFFFFFEF, 0xFFFFFF0, 0xFFFFFF1, 0xFFFFFF2, 0x3FFFFFFE, 0xFFFFFF3, 0xFFFFFF4, 0xFFFFFF5, 0xFFFFFF6, 0xFFFFFF7, 0xFFFFFF8, 0xFFFFFF9, 0xFFFFFFA, 0xFFFFFFB, 0x14, 0x3F8, 0x3F9, 0xFFA, 0x1FF9, 0x15, 0xF8, 0x7FA, 0x3FA, 0x3FB, 0xF9, 0x7FB, 0xFA, 0x16, 0x17, 0x18, 0x0, 0x1, 0x2, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x5C, 0xFB, 0x7FFC, 0x20, 0xFFB, 0x3FC, 0x1FFA, 0x21, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0xFC, 0x73, 0xFD, 0x1FFB, 0x7FFF0, 0x1FFC, 0x3FFC, 0x22, 0x7FFD, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26, 0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2A, 0x7, 0x2B, 0x76, 0x2C, 0x8, 0x9, 0x2D, 0x77, 0x78, 0x79, 0x7A, 0x7B, 0x7FFE, 0x7FC, 0x3FFD, 0x1FFD, 0xFFFFFFC, 0xFFFE6, 0x3FFFD2, 0xFFFE7, 0xFFFE8, 0x3FFFD3, 0x3FFFD4, 0x3FFFD5, 0x7FFFD9, 0x3FFFD6, 0x7FFFDA, 0x7FFFDB, 0x7FFFDC, 0x7FFFDD, 0x7FFFDE, 0xFFFFEB, 0x7FFFDF, 0xFFFFEC, 0xFFFFED, 0x3FFFD7, 0x7FFFE0, 0xFFFFEE, 0x7FFFE1, 0x7FFFE2, 0x7FFFE3, 0x7FFFE4, 0x1FFFDC, 0x3FFFD8, 0x7FFFE5, 0x3FFFD9, 0x7FFFE6, 0x7FFFE7, 0xFFFFEF, 0x3FFFDA, 0x1FFFDD, 0xFFFE9, 0x3FFFDB, 0x3FFFDC, 0x7FFFE8, 0x7FFFE9, 0x1FFFDE, 0x7FFFEA, 0x3FFFDD, 0x3FFFDE, 0xFFFFF0, 0x1FFFDF, 0x3FFFDF, 0x7FFFEB, 0x7FFFEC, 0x1FFFE0, 0x1FFFE1, 0x3FFFE0, 0x1FFFE2, 0x7FFFED, 0x3FFFE1, 0x7FFFEE, 0x7FFFEF, 0xFFFEA, 0x3FFFE2, 0x3FFFE3, 0x3FFFE4, 0x7FFFF0, 0x3FFFE5, 0x3FFFE6, 0x7FFFF1, 0x3FFFFE0, 0x3FFFFE1, 0xFFFEB, 0x7FFF1, 0x3FFFE7, 0x7FFFF2, 0x3FFFE8, 0x1FFFFEC, 0x3FFFFE2, 0x3FFFFE3, 0x3FFFFE4, 0x7FFFFDE, 0x7FFFFDF, 0x3FFFFE5, 0xFFFFF1, 0x1FFFFED, 0x7FFF2, 0x1FFFE3, 0x3FFFFE6, 0x7FFFFE0, 0x7FFFFE1, 0x3FFFFE7, 0x7FFFFE2, 0xFFFFF2, 0x1FFFE4, 0x1FFFE5, 0x3FFFFE8, 0x3FFFFE9, 0xFFFFFFD, 0x7FFFFE3, 0x7FFFFE4, 0x7FFFFE5, 0xFFFEC, 0xFFFFF3, 0xFFFED, 0x1FFFE6, 0x3FFFE9, 0x1FFFE7, 0x1FFFE8, 0x7FFFF3, 0x3FFFEA, 0x3FFFEB, 0x1FFFFEE, 0x1FFFFEF, 0xFFFFF4, 0xFFFFF5, 0x3FFFFEA, 0x7FFFF4, 0x3FFFFEB, 0x7FFFFE6, 0x3FFFFEC, 0x3FFFFED, 0x7FFFFE7, 0x7FFFFE8, 0x7FFFFE9, 0x7FFFFEA, 0x7FFFFEB, 0xFFFFFFE, 0x7FFFFEC, 0x7FFFFED, 0x7FFFFEE, 0x7FFFFEF, 0x7FFFFF0, 0x3FFFFEE, 0x3FFFFFFF};
    /** Huffman encode bit lengths, indexed by byte value (0-256), length 257 */
    private static final byte[] ENCODE_BITS = {13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28, 6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10, 13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6, 15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28, 20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24, 22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23, 26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27, 20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23, 26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26, 30};
    private static final byte[] DECODE_VALUES = {48, 48, 48, 48, 48, 48, 48, 48, 49, 49, 49, 49, 49, 49, 49, 49, 50, 50, 50, 50, 50, 50, 50, 50, 97, 97, 97, 97, 97, 97, 97, 97, 99, 99, 99, 99, 99, 99, 99, 99, 101, 101, 101, 101, 101, 101, 101, 101, 105, 105, 105, 105, 105, 105, 105, 105, 111, 111, 111, 111, 111, 111, 111, 111, 115, 115, 115, 115, 115, 115, 115, 115, 116, 116, 116, 116, 116, 116, 116, 116, 32, 32, 32, 32, 37, 37, 37, 37, 45, 45, 45, 45, 46, 46, 46, 46, 47, 47, 47, 47, 51, 51, 51, 51, 52, 52, 52, 52, 53, 53, 53, 53, 54, 54, 54, 54, 55, 55, 55, 55, 56, 56, 56, 56, 57, 57, 57, 57, 61, 61, 61, 61, 65, 65, 65, 65, 95, 95, 95, 95, 98, 98, 98, 98, 100, 100, 100, 100, 102, 102, 102, 102, 103, 103, 103, 103, 104, 104, 104, 104, 108, 108, 108, 108, 109, 109, 109, 109, 110, 110, 110, 110, 112, 112, 112, 112, 114, 114, 114, 114, 117, 117, 117, 117, 58, 58, 66, 66, 67, 67, 68, 68, 69, 69, 70, 70, 71, 71, 72, 72, 73, 73, 74, 74, 75, 75, 76, 76, 77, 77, 78, 78, 79, 79, 80, 80, 81, 81, 82, 82, 83, 83, 84, 84, 85, 85, 86, 86, 87, 87, 89, 89, 106, 106, 107, 107, 113, 113, 118, 118, 119, 119, 120, 120, 121, 121, 122, 122, 38, 42, 44, 59, 88, 90, 0, 0};
    private static final byte[] DECODE_BITS = {5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 10, 32};
    // private static final byte[] DECODE_MANTISSAS = {0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};

    /** Level-0 lookup table: follow(0-255) → (consumedBits << 8) | decodedByte, 0 = continue/error */
    private static final byte[] FF_LEVEL0 = {0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x3F, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x27, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x2B, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x7C, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x23, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0x3E, 0, 0, 0, 0, 0, 0, 0, 0, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x24, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x5B, 0x5B, 0x5B, 0x5B, 0x5B, 0x5B, 0x5B, 0x5B, 0x5D, 0x5D, 0x5D, 0x5D, 0x5D, 0x5D, 0x5D, 0x5D, 0x7E, 0x7E, 0x7E, 0x7E, 0x7E, 0x7E, 0x7E, 0x7E, 0x5E, 0x5E, 0x5E, 0x5E, 0x7D, 0x7D, 0x7D, 0x7D, 0x3C, 0x3C, 0x60, 0x60, 0x7B, 0x7B, 0, 0};

    private static final byte[] FE_10 = {'!', '"', '(', ')'};
    private static final byte[] FE_19 = {'\\', (byte) 195, (byte) 208};
    private static final byte[] FE_20 = {(byte) 128, (byte) 130, (byte) 131, (byte) 162, (byte) 184, (byte) 194, (byte) 224, (byte) 226};
    private static final byte[] FE_21 = {(byte) 153, (byte) 161, (byte) 167, (byte) 172};
    private static final byte[] FF_21 = {(byte) 176, (byte) 177, (byte) 179, (byte) 209, (byte) 216, (byte) 217, (byte) 227, (byte) 229, (byte) 230};
    private static final byte[] FF_22 = {(byte) 129, (byte) 132, (byte) 133, (byte) 134, (byte) 136, (byte) 146, (byte) 154, (byte) 156, (byte) 160, (byte) 163, (byte) 164, (byte) 169, (byte) 170, (byte) 173, (byte) 178, (byte) 181, (byte) 185, (byte) 186, (byte) 187, (byte) 189, (byte) 190, (byte) 196, (byte) 198, (byte) 228, (byte) 232, (byte) 233};
    private static final byte[] FF_23 = {1, (byte) 135, (byte) 137, (byte) 138, (byte) 139, (byte) 140, (byte) 141, (byte) 143, (byte) 147, (byte) 149, (byte) 150, (byte) 151, (byte) 152, (byte) 155, (byte) 157, (byte) 158, (byte) 165, (byte) 166, (byte) 168, (byte) 174, (byte) 175, (byte) 180, (byte) 182, (byte) 183, (byte) 188, (byte) 191, (byte) 197, (byte) 231, (byte) 239};
    private static final byte[] FF_24 = {9, (byte) 142, (byte) 144, (byte) 145, (byte) 148, (byte) 159, (byte) 171, (byte) 206, (byte) 215, (byte) 225, (byte) 236, (byte) 237};
    private static final byte[] FF_F8_26 = {(byte) 192, (byte) 193, (byte) 200, (byte) 201};
    private static final byte[] FF_F9_26 = {(byte) 202, (byte) 205, (byte) 210, (byte) 213};
    private static final byte[] FF_FA_26 = {(byte) 218, (byte) 219, (byte) 238, (byte) 240};
    private static final byte[] FF_FB_26 = {(byte) 242, (byte) 243, (byte) 255};
    private static final byte[] FF_FC_27 = {(byte) 211, (byte) 212, (byte) 214, (byte) 221, (byte) 222, (byte) 223, (byte) 241, (byte) 244};
    private static final byte[] FF_FD_27 = {(byte) 245, (byte) 246, (byte) 247, (byte) 248, (byte) 250, (byte) 251, (byte) 252, (byte) 253};
    private static final byte[] FF_FE_28 = {2, 3, 4, 5, 6, 7, 8, 11, 12, 14, 15, 16, 17, 18};
    private static final byte[] FF_FF_28 = {19, 20, 21, 23, 24, 25, 26, 27, 28, 29, 30, 31, 127, (byte) 220, (byte) 249};
    private static final short[] FF_FF_30 = {10, 13, 22, 256};

    /**
     * Compute the exact number of compressed bytes the input would produce.
     *
     * <p>Useful for pre-allocating the output buffer before calling
     * {@link #encodeData(byte[], int, int, byte[], int)}.</p>
     *
     * @param buf    input buffer
     * @param offset start offset in {@code buf}
     * @param len    number of bytes
     * @return Huffman output byte count (not including the HPACK length prefix)
     */
    public static int computeHuffmanLength(byte[] buf, int offset, int len) {
        int bits = 0;
        for (int i = offset, end = offset + len; i < end; ++i) bits += ENCODE_BITS[buf[i] & 0xFF];
        return (bits + 7) >> 3;
    }

    /**
     * Huffman-encode raw bytes into compressed output (data only, no length prefix).
     *
     * <p>This method produces only the Huffman-compressed byte stream. It does
     * <b>not</b> prepend an HPACK length prefix (H flag + length). To produce a
     * complete HPACK string literal, use {@link #encodeHpackLiteral(byte[], int, int, byte[], int)}.</p>
     *
     * @param buf    input buffer (plain text)
     * @param offset start offset in {@code buf}
     * @param len    number of bytes to encode
     * @param output output buffer for compressed data
     * @param outOff start offset in {@code output}
     * @return number of bytes written to {@code output}
     */
    public static int encodeData(byte[] buf, int offset, int len, byte[] output, int outOff) {
        int limit = offset + len, bits = 0, begin = outOff;
        long ev = 0;
        for (int i = offset; i < limit; ++i) {
            int idx = buf[i] & 0xFF, v = ENCODE_VALUES[idx], n = ENCODE_BITS[idx];
            ev = ev << n | v;
            bits += n;
            while (bits >= 8) {
                output[outOff++] = (byte) (ev >>> (bits -= 8));
            }
            ev &= MASKS[bits];
        }
        if(bits > 0) output[outOff++] = (byte) ((ev << (8 - bits)) | MASKS[8 - bits]);
        return outOff - begin;
    }

    /**
     * Huffman-encode raw bytes into a newly-allocated output array (data only, no length prefix).
     *
     * <p>This method produces only the Huffman-compressed byte stream. It does
     * <b>not</b> prepend an HPACK length prefix (H flag + length). To produce a
     * complete HPACK string literal, use {@link #encodeHpackLiteral(byte[], int, int, byte[], int)}.</p>
     *
     * @param buf   input buffer (plain text)
     * @param offset start offset in {@code buf}
     * @param len    number of bytes to encode
     * @return a newly-allocated byte array containing the compressed data
     */
    public static byte[] encodeData(byte[] buf, int offset, int len) {
        byte[] output = new byte[computeHuffmanLength(buf, offset, len)];
        encodeData(buf, offset, len, output, 0);
        return output;
    }

    /**
     * Huffman-encode raw bytes into a newly-allocated output array (data only, no length prefix).
     *
     * @param buf   input buffer (plain text)
     * @return a newly-allocated byte array containing the compressed data
     */
    public static byte[] encodeData(byte[] buf) {
        return encodeData(buf, 0, buf.length);
    }

    /**
     * Encode an HPACK string literal with Huffman encoding (H flag + length + Huffman data).
     *
     * <p>This produces the complete wire format for a Huffman-encoded HPACK string:
     * a length prefix (H flag = 1, 7-bit length or extended encoding) followed by the
     * Huffman-compressed payload. For non-Huffman encoding, write the raw length and
     * data yourself (see {@link Http2HpackCodec#encodeLength(int, byte[], int)} for the length part).</p>
     *
     * <p><b>Buffer sizing:</b> the caller must ensure {@code output} has enough space.
     * A safe upper bound is {@code len * 4 + 5} (max Huffman expansion + max length prefix).
     * Use {@link #computeHuffmanLength(byte[], int, int)} for the exact size.</p>
     *
     * @param buf    the source byte array
     * @param offset start offset in {@code buf}
     * @param len    number of bytes to encode
     * @param output the output byte array
     * @param outOff the offset into {@code output} where writing begins
     * @return the number of bytes written to {@code output}
     */
    public static int encodeHpackLiteral(byte[] buf, int offset, int len, byte[] output, int outOff) {
        int begin = outOff, hufLen = computeHuffmanLength(buf, offset, len);
        outOff += Http2HpackCodec.encodeLength(hufLen, output, outOff);
        int written = encodeData(buf, offset, len, output, outOff);
        return outOff + written - begin;
    }

    /**
     * Decodes Huffman-encoded bytes into the original byte sequence.
     *
     * @param buf    the Huffman-encoded source buffer
     * @param offset start offset of the encoded data within {@code buf}
     * @param len    number of encoded bytes to decode
     * @return the decoded bytes (length is the actual decoded size)
     * @throws IllegalArgumentException if the encoded data is malformed
     */
    public static byte[] decodeData(byte[] buf, int offset, int len) {
        byte[] output = new byte[len << 1];
        return Arrays.copyOf(output, decodeData(buf, offset, len, output, 0));
    }

    /**
     * Decode Huffman encoded bytes to string.(data only, no length prefix).
     *
     * @param buf    input buffer
     * @param offset start offset
     * @param len    input length
     * @param output output buffer
     * @param outOff start output offset
     * @return number of bytes written to output
     */
    public static int decodeData(byte[] buf, int offset, int len, byte[] output, int outOff) {
        if (len == 0) return 0;
        final int beginOffset = offset, beginOutOff = outOff, endOffset = offset + len, totalBits = len << 3;
        int totalRemBits = totalBits, remBits = 0, mantissa = 0;
        int b = buf[offset++] & 0xFF;
        while (true) {
            int idx = (mantissa << 8 | b) >> remBits, bits = DECODE_BITS[idx];
            if(bits <= 8) { // single byte: idx < 254
                output[outOff++] = DECODE_VALUES[idx];
                totalRemBits -= bits;
                if(remBits >= bits )  {
                    mantissa &= MASKS[remBits -= bits]; // gather an idx without relying on the next byte and continue directly
                    continue;
                } else {
                    mantissa = b & MASKS[remBits = totalRemBits & 7];
                }
            } else { // idx = 254 or 256
                mantissa = b & MASKS[remBits]; // remBits not change
                int mv = idx, level = 0;
                do {
                    if(offset == endOffset) {
                        int val = idx == 254 ? FE_10[mantissa >> (remBits - 2)] : decodeFF(mv << 8 | mantissa << (8 - remBits) | MASKS[8 - remBits] , level);
                        if(val >= ENCODE_BITS.length || (totalRemBits -= ENCODE_BITS[val]) < 0) throw new IllegalArgumentException("invalid Huffman code: trailing bits exhausted before completing symbol");
                        output[outOff++] = (byte) val;
                        mantissa = buf[offset - 1] & MASKS[remBits = totalRemBits];
                        break;
                    }
                    int octet = buf[offset++] & 0xFF, follow = (mantissa << 8 | octet) >> remBits;
                    mv = mv << 8 | follow;
                    mantissa = octet & MASKS[remBits];
                    int result = (idx == 254) ? FE_10[follow >> 6] : decodeFF(mv, level++);
                    if (result != mv) {
                        output[outOff++] = (byte) result;
                        totalRemBits -= ENCODE_BITS[result];
                        offset = beginOffset + (totalBits - 1 - totalRemBits >> 3); // accurately locate the offset and mantissa by consuming bits (offset < endOffset)
                        mantissa = buf[offset++] & MASKS[remBits = totalRemBits & 7];
                        break;
                    }
                } while (true);
            }
            // ===== Sole normal return path: end-of-stream reached; try EOS / trailing short code / error.
            if(offset == endOffset) { // last byte
                if(mantissa == MASKS[remBits]) return outOff - beginOutOff;
                idx = mantissa << (8 - remBits) | MASKS[8 - remBits];
                if(DECODE_BITS[idx] <= totalRemBits) {
                    output[outOff++] = DECODE_VALUES[idx];
                    return outOff - beginOutOff;
                }
                throw new IllegalArgumentException("invalid Huffman code: trailing bits do not form a valid symbol or EOS");
            } else {
                b = buf[offset++] & 0xFF; // Get next value
            }
        }
    }

    /**
     * Resolves an accumulated 0xFF/0xFE-range Huffman code to a decode index.
     *
     * @param val   the low-aligned bit accumulation from the caller
     * @param level packed byte count: 0 for 1 byte, 1 for 2 bytes, 2 for 3 or more bytes
     * @return the decode index in [0, 256] (256 = EOS), or {@code val} unchanged if more bits are needed
     * @throws IllegalArgumentException if the accumulated prefix is an invalid 0xFF/0xFE code
     */
    private static int decodeFF(int val, int level) {
        int b = val & 0xFF;
        if (level == 0) return b < 0xFE ? FF_LEVEL0[b] /*& 0xFF */: val;
        int b1 = val >> 8 & 0xFF;// 0xF6 ~ 0xFF
        if (level == 1) { // value is 24bits
            if (b1 == 0xFF) { // 0xFF 21bits ~ 24bits
                int flag = b >> 3;
                if (/*flag >= 0 && */flag < FF_21.length) return FF_21[flag] & 0xFF; // 21bits
                if ((flag = (b >> 2) - 0x12) >= 0 && flag < FF_22.length) return FF_22[flag] & 0xFF; // 22bits - 010010
                if ((flag = (b >> 1) - 0x58) >= 0 && flag < FF_23.length) return FF_23[flag] & 0xFF; // 23bits - 1011000
                if ((flag = b - 0xEA) >= 0 && flag < FF_24.length) return FF_24[flag] & 0xFF; // 24bits
                if (b >= 0xF6) return val; // 0xF6 ~ 0xFF
            } else { // 0xFE 19bits ~ 21bits
                int flag = b >> 5;
                if (/*flag >= 0 && */flag < FE_19.length) return FE_19[flag] & 0xFF; // 19bits
                if ((flag = (b >> 4) - 0x6) >= 0 && flag < FE_20.length) return FE_20[flag] & 0xFF; // 20bits
                if ((flag = (b >> 3) - 0x1C) >= 0/*&& flag < FE_21.length*/) return FE_21[flag] & 0xFF; // 21bits
            }
        } else { // value is 32bits
            switch (b1) {
                case 0xF6: return b < 128 ? 199 : 207; // 25 bits
                case 0xF7: return b < 128 ? 234 : 235; // 25 bits
                case 0xF8: return FF_F8_26[b >> 6] & 0xFF; // 26bits 00 01 10 11
                case 0xF9: return FF_F9_26[b >> 6] & 0xFF; // 26bits 00 01 10 11
                case 0xFA: return FF_FA_26[b >> 6] & 0xFF; // 26bits 00 01 10 11
                case 0xFB: { // 26bits 00 01 10 11
                    int flag = b >> 6;
                    if (flag < 3) return FF_FB_26[flag] & 0xFF;
                    return (b >> 5) == 6 ? 203 : 204; // 27bits
                }
                case 0xFC: return FF_FC_27[b >> 5] & 0xFF; // 27bits
                case 0xFD: return FF_FD_27[b >> 5] & 0xFF; // 27bits
                case 0xFE: {
                    int flag = b >> 5;
                    if (flag == 0) return 254; // 27bits
                    if ((flag = (b >> 4) - 0x2) >= 0) return FF_FE_28[flag] /*& 0xFF*/; // 28bits
                    break;
                }
                case 0xFF: {// 28bits
                    int flag = b >> 4;
                    if (flag < FF_FF_28.length) return FF_FF_28[flag] & 0xFF; // 0000 - 1110
                    return FF_FF_30[(b >> 2) & 0x3] /*& 0x1FF*/; // 30bits flag = 15  1111
                }
            }
        }
        throw new IllegalArgumentException("huffman decodeFF error: value = " + val + ", level = " + level);
    }
}
