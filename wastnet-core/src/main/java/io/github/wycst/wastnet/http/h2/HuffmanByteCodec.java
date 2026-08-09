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

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

/**
 * HTTP/2 HPACK Huffman codec for string encoding/decoding.
 *
 * @author wangyc
 */
public final class HuffmanByteCodec {

    static final Log log = LogFactory.getLog(HuffmanByteCodec.class);

    // Masks for the number of bits
    private final static int[] MASKS = {0, 0x1, 0x3, 0x7, 0xF, 0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF};

    /** Huffman encode values (RFC 7541 raw hex, LSB-aligned), indexed by byte value (0-255) */
    private static final int[] ENCODE_VALUES = {0x1FF8, 0x7FFFD8, 0xFFFFFE2, 0xFFFFFE3, 0xFFFFFE4, 0xFFFFFE5, 0xFFFFFE6, 0xFFFFFE7, 0xFFFFFE8, 0xFFFFEA, 0x3FFFFFFC, 0xFFFFFE9, 0xFFFFFEA, 0x3FFFFFFD, 0xFFFFFEB, 0xFFFFFEC, 0xFFFFFED, 0xFFFFFEE, 0xFFFFFEF, 0xFFFFFF0, 0xFFFFFF1, 0xFFFFFF2, 0x3FFFFFFE, 0xFFFFFF3, 0xFFFFFF4, 0xFFFFFF5, 0xFFFFFF6, 0xFFFFFF7, 0xFFFFFF8, 0xFFFFFF9, 0xFFFFFFA, 0xFFFFFFB, 0x14, 0x3F8, 0x3F9, 0xFFA, 0x1FF9, 0x15, 0xF8, 0x7FA, 0x3FA, 0x3FB, 0xF9, 0x7FB, 0xFA, 0x16, 0x17, 0x18, 0x0, 0x1, 0x2, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x5C, 0xFB, 0x7FFC, 0x20, 0xFFB, 0x3FC, 0x1FFA, 0x21, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0xFC, 0x73, 0xFD, 0x1FFB, 0x7FFF0, 0x1FFC, 0x3FFC, 0x22, 0x7FFD, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26, 0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2A, 0x7, 0x2B, 0x76, 0x2C, 0x8, 0x9, 0x2D, 0x77, 0x78, 0x79, 0x7A, 0x7B, 0x7FFE, 0x7FC, 0x3FFD, 0x1FFD, 0xFFFFFFC, 0xFFFE6, 0x3FFFD2, 0xFFFE7, 0xFFFE8, 0x3FFFD3, 0x3FFFD4, 0x3FFFD5, 0x7FFFD9, 0x3FFFD6, 0x7FFFDA, 0x7FFFDB, 0x7FFFDC, 0x7FFFDD, 0x7FFFDE, 0xFFFFEB, 0x7FFFDF, 0xFFFFEC, 0xFFFFED, 0x3FFFD7, 0x7FFFE0, 0xFFFFEE, 0x7FFFE1, 0x7FFFE2, 0x7FFFE3, 0x7FFFE4, 0x1FFFDC, 0x3FFFD8, 0x7FFFE5, 0x3FFFD9, 0x7FFFE6, 0x7FFFE7, 0xFFFFEF, 0x3FFFDA, 0x1FFFDD, 0xFFFE9, 0x3FFFDB, 0x3FFFDC, 0x7FFFE8, 0x7FFFE9, 0x1FFFDE, 0x7FFFEA, 0x3FFFDD, 0x3FFFDE, 0xFFFFF0, 0x1FFFDF, 0x3FFFDF, 0x7FFFEB, 0x7FFFEC, 0x1FFFE0, 0x1FFFE1, 0x3FFFE0, 0x1FFFE2, 0x7FFFED, 0x3FFFE1, 0x7FFFEE, 0x7FFFEF, 0xFFFEA, 0x3FFFE2, 0x3FFFE3, 0x3FFFE4, 0x7FFFF0, 0x3FFFE5, 0x3FFFE6, 0x7FFFF1, 0x3FFFFE0, 0x3FFFFE1, 0xFFFEB, 0x7FFF1, 0x3FFFE7, 0x7FFFF2, 0x3FFFE8, 0x1FFFFEC, 0x3FFFFE2, 0x3FFFFE3, 0x3FFFFE4, 0x7FFFFDE, 0x7FFFFDF, 0x3FFFFE5, 0xFFFFF1, 0x1FFFFED, 0x7FFF2, 0x1FFFE3, 0x3FFFFE6, 0x7FFFFE0, 0x7FFFFE1, 0x3FFFFE7, 0x7FFFFE2, 0xFFFFF2, 0x1FFFE4, 0x1FFFE5, 0x3FFFFE8, 0x3FFFFE9, 0xFFFFFFD, 0x7FFFFE3, 0x7FFFFE4, 0x7FFFFE5, 0xFFFEC, 0xFFFFF3, 0xFFFED, 0x1FFFE6, 0x3FFFE9, 0x1FFFE7, 0x1FFFE8, 0x7FFFF3, 0x3FFFEA, 0x3FFFEB, 0x1FFFFEE, 0x1FFFFEF, 0xFFFFF4, 0xFFFFF5, 0x3FFFFEA, 0x7FFFF4, 0x3FFFFEB, 0x7FFFFE6, 0x3FFFFEC, 0x3FFFFED, 0x7FFFFE7, 0x7FFFFE8, 0x7FFFFE9, 0x7FFFFEA, 0x7FFFFEB, 0xFFFFFFE, 0x7FFFFEC, 0x7FFFFED, 0x7FFFFEE, 0x7FFFFEF, 0x7FFFFF0, 0x3FFFFEE};

    /** Huffman encode bit lengths, indexed by byte value (0-255) */
    private static final byte[] ENCODE_BITS = {13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28, 6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10, 13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6, 15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5, 6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28, 20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23, 24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24, 22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23, 21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23, 26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25, 19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27, 20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23, 26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26};
    private static final byte[] DECODE_VALUES = {48, 48, 48, 48, 48, 48, 48, 48, 49, 49, 49, 49, 49, 49, 49, 49, 50, 50, 50, 50, 50, 50, 50, 50, 97, 97, 97, 97, 97, 97, 97, 97, 99, 99, 99, 99, 99, 99, 99, 99, 101, 101, 101, 101, 101, 101, 101, 101, 105, 105, 105, 105, 105, 105, 105, 105, 111, 111, 111, 111, 111, 111, 111, 111, 115, 115, 115, 115, 115, 115, 115, 115, 116, 116, 116, 116, 116, 116, 116, 116, 32, 32, 32, 32, 37, 37, 37, 37, 45, 45, 45, 45, 46, 46, 46, 46, 47, 47, 47, 47, 51, 51, 51, 51, 52, 52, 52, 52, 53, 53, 53, 53, 54, 54, 54, 54, 55, 55, 55, 55, 56, 56, 56, 56, 57, 57, 57, 57, 61, 61, 61, 61, 65, 65, 65, 65, 95, 95, 95, 95, 98, 98, 98, 98, 100, 100, 100, 100, 102, 102, 102, 102, 103, 103, 103, 103, 104, 104, 104, 104, 108, 108, 108, 108, 109, 109, 109, 109, 110, 110, 110, 110, 112, 112, 112, 112, 114, 114, 114, 114, 117, 117, 117, 117, 58, 58, 66, 66, 67, 67, 68, 68, 69, 69, 70, 70, 71, 71, 72, 72, 73, 73, 74, 74, 75, 75, 76, 76, 77, 77, 78, 78, 79, 79, 80, 80, 81, 81, 82, 82, 83, 83, 84, 84, 85, 85, 86, 86, 87, 87, 89, 89, 106, 106, 107, 107, 113, 113, 118, 118, 119, 119, 120, 120, 121, 121, 122, 122, 38, 42, 44, 59, 88, 90, 0, 0};
    private static final byte[] DECODE_BITS = {5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 10, 32};
    private static final byte[] DECODE_MANTISSAS = {0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0};

    /** Level-0 lookup table: follow(0-255) → (consumedBits << 8) | decodedByte, 0 = continue/error */
    private static final short[] FF_LEVEL0 = {0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x63F, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x527, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x52B, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x57C, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x423, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x43E, 0x300, 0x300, 0x300, 0x300, 0x300, 0x300, 0x300, 0x300, 0x324, 0x324, 0x324, 0x324, 0x324, 0x324, 0x324, 0x324, 0x340, 0x340, 0x340, 0x340, 0x340, 0x340, 0x340, 0x340, 0x35B, 0x35B, 0x35B, 0x35B, 0x35B, 0x35B, 0x35B, 0x35B, 0x35D, 0x35D, 0x35D, 0x35D, 0x35D, 0x35D, 0x35D, 0x35D, 0x37E, 0x37E, 0x37E, 0x37E, 0x37E, 0x37E, 0x37E, 0x37E, 0x25E, 0x25E, 0x25E, 0x25E, 0x27D, 0x27D, 0x27D, 0x27D, 0x13C, 0x13C, 0x160, 0x160, 0x17B, 0x17B, 0, 0};

    private static final short[] FE_10 = {0x600 | '!', 0x600 | '"', 0x600 | '(', 0x600 | ')'};
    private static final short[] FE_19 = {0x500 | '\\', 0x500 | 195, 0x500 | 208};
    private static final short[] FE_20 = {0x400 | 128, 0x400 | 130, 0x400 | 131, 0x400 | 162, 0x400 | 184, 0x400 | 194, 0x400 | 224, 0x400 | 226};
    private static final short[] FE_21 = {0x300 | 153, 0x300 | 161, 0x300 | 167, 0x300 | 172};
    private static final short[] FF_21 = {0x300 | 176, 0x300 | 177, 0x300 | 179, 0x300 | 209, 0x300 | 216, 0x300 | 217, 0x300 | 227, 0x300 | 229, 0x300 | 230};
    private static final short[] FF_22 = {0x200 | 129, 0x200 | 132, 0x200 | 133, 0x200 | 134, 0x200 | 136, 0x200 | 146, 0x200 | 154, 0x200 | 156, 0x200 | 160, 0x200 | 163, 0x200 | 164, 0x200 | 169, 0x200 | 170, 0x200 | 173, 0x200 | 178, 0x200 | 181, 0x200 | 185, 0x200 | 186, 0x200 | 187, 0x200 | 189, 0x200 | 190, 0x200 | 196, 0x200 | 198, 0x200 | 228, 0x200 | 232, 0x200 | 233};
    private static final short[] FF_23 = {0x100 | 1, 0x100 | 135, 0x100 | 137, 0x100 | 138, 0x100 | 139, 0x100 | 140, 0x100 | 141, 0x100 | 143, 0x100 | 147, 0x100 | 149, 0x100 | 150, 0x100 | 151, 0x100 | 152, 0x100 | 155, 0x100 | 157, 0x100 | 158, 0x100 | 165, 0x100 | 166, 0x100 | 168, 0x100 | 174, 0x100 | 175, 0x100 | 180, 0x100 | 182, 0x100 | 183, 0x100 | 188, 0x100 | 191, 0x100 | 197, 0x100 | 231, 0x100 | 239};
    private static final short[] FF_24 = {9, 142, 144, 145, 148, 159, 171, 206, 215, 225, 236, 237};
    private static final short[] FF_F8_26 = {0x600 | 192, 0x600 | 193, 0x600 | 200, 0x600 | 201};
    private static final short[] FF_F9_26 = {0x600 | 202, 0x600 | 205, 0x600 | 210, 0x600 | 213};
    private static final short[] FF_FA_26 = {0x600 | 218, 0x600 | 219, 0x600 | 238, 0x600 | 240};
    private static final short[] FF_FB_26 = {0x600 | 242, 0x600 | 243, 0x600 | 255};
    private static final short[] FF_FC_27 = {0x500 | 211, 0x500 | 212, 0x500 | 214, 0x500 | 221, 0x500 | 222, 0x500 | 223, 0x500 | 241, 0x500 | 244};
    private static final short[] FF_FD_27 = {0x500 | 245, 0x500 | 246, 0x500 | 247, 0x500 | 248, 0x500 | 250, 0x500 | 251, 0x500 | 252, 0x500 | 253};
    private static final short[] FF_FE_28 = {0x400 | 2, 0x400 | 3, 0x400 | 4, 0x400 | 5, 0x400 | 6, 0x400 | 7, 0x400 | 8, 0x400 | 11, 0x400 | 12, 0x400 | 14, 0x400 | 15, 0x400 | 16, 0x400 | 17, 0x400 | 18};
    private static final short[] FF_FF_28 = {0x400 | 19, 0x400 | 20, 0x400 | 21, 0x400 | 23, 0x400 | 24, 0x400 | 25, 0x400 | 26, 0x400 | 27, 0x400 | 28, 0x400 | 29, 0x400 | 30, 0x400 | 31, 0x400 | 127, 0x400 | 220, 0x400 | 249};
    private static final short[] FF_FF_30 = {0x200 | 10, 0x200 | 13, 0x200 | 22, 0x200 | 256};

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
        if(encodeData(buf, offset, len, output, 0) != output.length)
            throw new IllegalStateException("internal error (coding bug): huffmanLength()=" + output.length + " but encodeData() returned count not match");
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
        if (written != hufLen) throw new IllegalStateException("internal error (coding bug): huffmanLength()=" + hufLen + " but encodeData() returned " + written);
        return outOff + written - begin;
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
        try {
            final int begin = outOff, endOffset = offset + len;
            byte b = buf[offset++];
            int nextRemBits = 0, nextMantissa = 0;
            for (; ; ) {
                int idx = b & 0xFF, bits = DECODE_BITS[idx], remBits, mantissa;
                if (bits <= 8) {
                    output[outOff++] = DECODE_VALUES[idx];
                    remBits = 8 - bits + nextRemBits;
                    mantissa = DECODE_MANTISSAS[idx] << nextRemBits | nextMantissa;
                } else { // Multi-byte path (idx >= 254)
                    int value = idx;
                    if (offset < endOffset) {
                        int level = 0;
                        do {
                            int octet = buf[offset++] & 0xFF, follow = (nextMantissa << 8 | octet) >> nextRemBits;
                            value = value << 8 | follow;
                            nextMantissa = octet & MASKS[nextRemBits];
                            int result = (idx == 254) ? FE_10[follow >> 6] : decodeFF(value, level++);
                            if (result != value) {
                                output[outOff++] = (byte) result;
                                remBits = (result >> 8) + nextRemBits;
                                mantissa = (value & MASKS[remBits]) << nextRemBits | nextMantissa;
                                break;
                            }
                        } while (true);
                    } else {
                        int lastFillBits = 8 - nextRemBits;  // padding bits to fill 8 bits
                        int follow = nextMantissa << lastFillBits | MASKS[lastFillBits];
                        value = value << 8 | follow;
                        int result = (idx == 254) ? FE_10[follow >> 6] : decodeFF(value, 0);
                        if (result != value) {
                            output[outOff++] = (byte) result;
                            remBits = (result >> 8);
                            int mask = MASKS[remBits];
                            if ((value & mask) == mask) return outOff - begin; // EOS
                        }
                        throw new IllegalArgumentException("decode error at the last byte");
                    }
                }
                if (remBits < 8) { // combine the next byte
                    if (offset < endOffset) {
                        int mask = MASKS[remBits];
                        int next = mantissa << 8 | (buf[offset++] & 0xFF);
                        b = (byte) (next >> remBits);
                        nextMantissa = next & mask;
                        nextRemBits = remBits;
                    } else {
                        if (mantissa == MASKS[remBits]) return outOff - begin; // check if remainder is EOS (all ones)
                        int lastIdx = mantissa << (8 - remBits); // fill remainder to 8 bits if last byte
                        if (DECODE_BITS[lastIdx] <= 8) {
                            output[outOff++] = DECODE_VALUES[lastIdx];
                            return outOff - begin;
                        } else throw new IllegalArgumentException("decode error");
                    }
                } else {
                    int n = remBits - 8;
                    b = (byte) (mantissa >> n);
                    nextRemBits = n;
                    nextMantissa = mantissa & MASKS[n];
                }
            }
        } catch (RuntimeException re) {
            log.error("decode error at offset: {}", offset);
            throw re;
        }
    }

    /** Decode for byte 255 (11-30 bit codes) */
    private static int decodeFF(int val, int level) {
        int b = val & 0xFF;
        if (level == 0) return b < 0xFE ? FF_LEVEL0[b] : val;
        if (level == 1) { // value is 24bits
            int b1 = val >> 8 & 0xFF;
            if (b1 == 0xFF) { // 0xFF 21bits ~ 24bits
                int flag = b >> 3;
                if (/*flag >= 0 && */flag < FF_21.length) return FF_21[flag]; // 21bits
                if ((flag = (b >> 2) - 0x12) >= 0 && flag < FF_22.length) return FF_22[flag]; // 22bits - 010010
                if ((flag = (b >> 1) - 0x58) >= 0 && flag < FF_23.length) return FF_23[flag]; // 23bits - 1011000
                if ((flag = b - 0xEA) >= 0 && flag < FF_24.length) return FF_24[flag]; // 24bits
                if (b >= 0xF6) return val; // 0xF6 ~ 0xFF
            } else { // 0xFE 19bits ~ 21bits
                int flag = b >> 5;
                if (/*flag >= 0 && */flag < FE_19.length) return FE_19[flag]; // 19bits
                if ((flag = (b >> 4) - 0x6) >= 0 && flag < FE_20.length) return FE_20[flag]; // 20bits
                if ((flag = (b >> 3) - 0x1C) >= 0/*&& flag < FE_21.length*/) return FE_21[flag]; // 21bits
            }
        } else { // value is 32bits
            int b1 = val >> 8 & 0xFF; // 0xF6 ~ 0xFF
            switch (b1) {
                case 0xF6: return 0x700 | (b < 128 ? 199 : 207); // 25 bits
                case 0xF7: return 0x700 | (b < 128 ? 234 : 235); // 25 bits
                case 0xF8: return FF_F8_26[b >> 6]; // 26bits 00 01 10 11
                case 0xF9: return FF_F9_26[b >> 6]; // 26bits 00 01 10 11
                case 0xFA: return FF_FA_26[b >> 6]; // 26bits 00 01 10 11
                case 0xFB: { // 26bits 00 01 10 11
                    int flag = b >> 6;
                    if (flag < 3) return FF_FB_26[flag];
                    return 0x500 | ((b >> 5) == 6 ? 203 : 204); // 27bits
                }
                case 0xFC: return FF_FC_27[b >> 5]; // 27bits
                case 0xFD: return FF_FD_27[b >> 5]; // 27bits
                case 0xFE: {
                    int flag = b >> 5;
                    if (flag == 0) return 0x500 | 254; // 27bits
                    if ((flag = (b >> 4) - 0x2) >= 0) return FF_FE_28[flag]; // 28bits
                    break;
                }
                case 0xFF: {// 28bits
                    int flag = b >> 4;
                    if (flag < FF_FF_28.length) return FF_FF_28[flag]; // 0000 - 1110
                    return FF_FF_30[(b >> 2) & 0x3]; // 30bits flag = 15   1111
                }
            }
        }
        throw new IllegalArgumentException("huffman decodeFF error: value = " + val + ", level = " + level);
    }
}
