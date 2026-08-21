package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link HuffmanByteCodec} (RFC 7541 Huffman encoding/decoding).
 * <p>
 * Covers encode/decode roundtrip for all 256 byte values, public API variants,
 * decode error paths (multi-byte EOF, truncated input), and decodeFF error branch.
 */
public class HuffmanByteCodecTest {

    // ==================== Constructor ====================

    @Test
    void testConstructor() {
        assertNotNull(new HuffmanByteCodec());
    }

    // ==================== computeHuffmanLength ====================

    @Test
    void testComputeHuffmanLengthEmpty() {
        assertEquals(0, HuffmanByteCodec.computeHuffmanLength(new byte[0], 0, 0));
    }

    @Test
    void testComputeHuffmanLengthWithOffset() {
        byte[] data = "hello".getBytes(StandardCharsets.US_ASCII);
        int len = HuffmanByteCodec.computeHuffmanLength(data, 1, 4);
        assertTrue(len > 0);
    }

    @Test
    void testComputeHuffmanLengthPadding() {
        // Single byte produces non-byte-aligned output, tests padding length calculation
        byte[] data = new byte[]{'a'}; // 7-bit code
        int len = HuffmanByteCodec.computeHuffmanLength(data, 0, 1);
        assertEquals(1, len); // 7 bits → 1 byte
    }

    // ==================== encodeData variants ====================

    @Test
    void testEncodeDataEmpty() {
        assertArrayEquals(new byte[0], HuffmanByteCodec.encodeData(new byte[0]));
        assertArrayEquals(new byte[0], HuffmanByteCodec.encodeData(new byte[0], 0, 0));
        byte[] out = new byte[1];
        assertEquals(0, HuffmanByteCodec.encodeData(new byte[0], 0, 0, out, 0));
    }

    @Test
    void testEncodeDataWithOffset() {
        byte[] data = "abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = HuffmanByteCodec.encodeData(data, 2, 3);
        assertTrue(encoded.length > 0);
        byte[] decoded = new byte[16];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
        assertEquals("cde", new String(decoded, 0, count, StandardCharsets.US_ASCII));
    }

    @Test
    void testEncodeDataConvenienceSingleArg() {
        byte[] data = "GET".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = HuffmanByteCodec.encodeData(data);
        assertTrue(encoded.length > 0);
        byte[] decoded = new byte[16];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
        assertEquals("GET", new String(decoded, 0, count, StandardCharsets.US_ASCII));
    }

    // ==================== Roundtrip: single characters ====================

    @Test
    void testSingleCharacterEncodeDecode() {
        for (int c = 32; c <= 126; c++) {
            byte[] input = new byte[]{(byte) c};
            byte[] encoded = HuffmanByteCodec.encodeData(input, 0, 1);
            assertTrue(encoded.length > 0, "Encoding " + (char) c + " produced empty output");
            byte[] decoded = new byte[input.length * 2];
            int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
            assertEquals(1, count, "Roundtrip failed for char " + (char) c);
            assertEquals((byte) c, decoded[0], "Roundtrip value mismatch for char " + (char) c);
        }
    }

    // ==================== Roundtrip: known strings ====================

    @Test
    void testKnownHuffmanValues() {
        String[] testValues = {
                "www.example.com", "no-cache", "custom-key", "custom-value",
                "GET", "POST", "/index.html", "https", "200", "404", "gzip, deflate"
        };
        for (String value : testValues) {
            byte[] input = value.getBytes(StandardCharsets.US_ASCII);
            byte[] encoded = HuffmanByteCodec.encodeData(input);
            assertEquals(HuffmanByteCodec.computeHuffmanLength(input, 0, input.length),
                    encoded.length, "Huffman length mismatch for: " + value);
            byte[] decoded = new byte[input.length * 4];
            int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
            assertEquals(value, new String(decoded, 0, count, StandardCharsets.US_ASCII),
                    "Roundtrip failed for: " + value);
        }
    }

    // ==================== Roundtrip: all 256 bytes ====================

    @Test
    void testAll256ByteValues() {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; ++i) allBytes[i] = (byte) i;

        byte[] encoded = HuffmanByteCodec.encodeData(allBytes);
        byte[] decoded = new byte[2048];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
        assertEquals(256, count);
        assertArrayEquals(allBytes, Arrays.copyOf(decoded, count));
    }

    // ==================== Individual byte roundtrip: exercises ALL decodeFF branches ====================

    @Test
    void testEveryByteIndividuallyHitsAllDecodeFFPaths() {
        // Each byte value 0-255 has a unique Huffman code with specific bit-length.
        // Encoding then decoding each byte individually forces the decoder into
        // the exact decodeFF path for that code, covering every level-0/1/2 branch:
        //   level 0: 11-18 bits → FF_LEVEL0[b] for b < 0xFE, val for b >= 0xFE
        //   level 1: 19-24 bits → FE_19/20/21 (0xFE family) or FF_21/22/23/24 (0xFF family)
        //   level 2: 25-30 bits → 0xF6..0xFF switch + sub-branches (b<128, flag<3, flag==0, etc.)
        for (int i = 0; i < 256; ++i) {
            byte[] input = new byte[]{(byte) i};
            byte[] encoded = HuffmanByteCodec.encodeData(input, 0, 1);
            byte[] decoded = new byte[4];
            int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
            assertEquals(1, count, "Roundtrip failed for byte " + i);
            assertEquals((byte) i, decoded[0], "Value mismatch for byte " + i);
        }
    }

    // ==================== Roundtrip: single byte to trigger exact 8-bit boundary ====================

    @Test
    void testDecodeExact8BitBoundary() {
        // Characters with 8-bit Huffman codes cause remBits=0 boundary in decoder
        byte[] input = "%%".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = HuffmanByteCodec.encodeData(input);
        byte[] decoded = new byte[8];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
        assertEquals(2, count);
        assertEquals("%%", new String(decoded, 0, count, StandardCharsets.US_ASCII));
    }

    // ==================== Roundtrip: all bytes (including non-printable) ====================

    // all256 byte test above already covers bytes 0-255 comprehensively.

    // ==================== Roundtrip: high-value bytes triggering long codes ====================

    @Test
    void testLongCodeBytes() {
        // Bytes with multi-byte Huffman codes (19-30 bits)
        byte[] longCodeInput = new byte[]{10, 1, 127, 0, (byte) 255, (byte) 128, (byte) 200, (byte) 250};
        byte[] encoded = HuffmanByteCodec.encodeData(longCodeInput);
        byte[] decoded = new byte[256];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
        assertEquals(8, count);
        byte[] trimmed = Arrays.copyOf(decoded, count);
        assertArrayEquals(longCodeInput, trimmed);
    }

    // ==================== HPACK literal encoding ====================

    @Test
    void testEncodeHpackLiteral() {
        byte[] input = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] output = new byte[input.length * 4 + 5];
        int written = HuffmanByteCodec.encodeHpackLiteral(input, 0, input.length, output, 0);
        assertTrue(written > 0);
        assertTrue((output[0] & 0x80) != 0); // H flag = 1
    }

    @Test
    void testEncodeHpackLiteralLongerThan127() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; ++i) sb.append("hello world ");
        byte[] input = sb.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] output = new byte[input.length * 4 + 5];
        int written = HuffmanByteCodec.encodeHpackLiteral(input, 0, input.length, output, 0);
        assertTrue(written > 0);
        assertEquals((byte) 0xFF, output[0]); // extended length marker
    }

    // ==================== Decode error: truncated multi-byte code ====================

    @Test
    void testDecodeTruncatedMultiByteCode() {
        // Byte 0 has a 13-bit code → encode, then truncate last byte to trigger EOF in multi-byte path
        byte[] input = new byte[]{0};
        byte[] encoded = HuffmanByteCodec.encodeData(input);
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);

        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> HuffmanByteCodec.decodeData(truncated, 0, truncated.length, new byte[16], 0));
        } finally {
            System.setErr(oldErr);
        }
    }

    // ==================== Decode error: corrupted last-byte padding ====================

    @Test
    void testDecodeInvalidPaddingByte() {
        // Encode a single 'e' (7-bit code), then corrupt the padding byte
        byte[] input = "e".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = HuffmanByteCodec.encodeData(input);
        // Overwrite the last byte with 0xFF to create invalid remaining bits
        encoded[encoded.length - 1] = (byte) 0xFF;

        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> HuffmanByteCodec.decodeData(encoded, 0, encoded.length, new byte[16], 0));
        } finally {
            System.setErr(oldErr);
        }
    }

    // ==================== Decode error: completely invalid data ====================

    @Test
    void testDecodeAllOnesData() {
        // All 0xFF bytes - invalid Huffman data
        byte[] invalid = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            assertThrows(Exception.class,
                    () -> HuffmanByteCodec.decodeData(invalid, 0, invalid.length, new byte[16], 0));
        } finally {
            System.setErr(oldErr);
        }
    }

    // ==================== Decode with non-zero output offset ====================

    @Test
    void testDecodeWithOutputOffset() {
        byte[] encoded = HuffmanByteCodec.encodeData("abc".getBytes(StandardCharsets.US_ASCII));
        byte[] output = new byte[16];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, output, 5);
        assertEquals(3, count);
        assertEquals("abc", new String(output, 5, count, StandardCharsets.US_ASCII));
    }

    // ==================== Empty decode at various offsets ====================

    @Test
    void testDecodeEmptyInputWithOffset() {
        assertEquals(0, HuffmanByteCodec.decodeData(new byte[0], 0, 0, new byte[0], 0));
        assertEquals(0, HuffmanByteCodec.decodeData(new byte[10], 5, 0, new byte[10], 3));
    }

    // ==================== Multi-byte EOF: EOS detection and error ====================

    @Test
    void testMultiByteEofEosDetectionReturnsSuccess() {
        // 0xFE is a multi-byte code prefix (DECODE_BITS[254]=10) with only 1 byte of
        // input: the symbol is not completed, so decode must reject it.
        byte[] input = new byte[]{(byte) 0xFE};
        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> HuffmanByteCodec.decodeData(input, 0, input.length, new byte[4], 0));
        } finally {
            System.setErr(oldErr);
        }
    }

    @Test
    void testMultiByteEofDecodeErrorThrows() {
        // 0xFF triggers multi-byte path (DECODE_BITS[255]=32). With only 1 byte,
        // the else branch is entered. decodeFF(0xFFFF, 0) returns value unchanged
        // since b >= 0xFE and level==0. result==value, so it falls through to throw.
        byte[] input = new byte[]{(byte) 0xFF};
        java.io.PrintStream oldErr = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> HuffmanByteCodec.decodeData(input, 0, input.length, new byte[4], 0));
        } finally {
            System.setErr(oldErr);
        }
    }

    // ==================== Roundtrip: edge padding (EOS) ====================

    @Test
    void testDecodeEosPaddingAtEnd() {
        // Single character 'e' (7-bit code), padding bits should be 0xFF (EOS)
        byte[] input = "e".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = HuffmanByteCodec.encodeData(input);
        byte[] decoded = new byte[4];
        int count = HuffmanByteCodec.decodeData(encoded, 0, encoded.length, decoded, 0);
        assertEquals(1, count);
        assertEquals('e', decoded[0]);
    }

    // ==================== HPACK encodeLength prefix ====================

    @Test
    void testEncodeLength() {
        byte[] output = new byte[8];
        int len = Http2HpackCodec.encodeLength(42, output, 0);
        assertEquals(1, len);
        assertEquals((byte) 0xAA, output[0]); // 0x80 | 42

        len = Http2HpackCodec.encodeLength(127, output, 0);
        assertEquals(2, len);
        assertEquals((byte) 0xFF, output[0]);
        assertEquals((byte) 0x00, output[1]);

        len = Http2HpackCodec.encodeLength(128, output, 0);
        assertEquals(2, len);
        assertEquals((byte) 0xFF, output[0]);
        assertEquals((byte) 0x01, output[1]);

        len = Http2HpackCodec.encodeLength(1000, output, 0);
        assertTrue(len > 2);
    }

    // Convenience overload decodeData(buf, offset, len): returns a trimmed array whose length equals the actual decoded size.
    @Test
    void testDecodeDataConvenienceTrimsToExactLength() {
        byte[][] cases = {
                "hello".getBytes(StandardCharsets.US_ASCII),
                "a".getBytes(StandardCharsets.US_ASCII),
                new byte[]{'e'}, // 7-bit code -> non-8-multiple tail, exercises EOS padding path
                new byte[]{0, 1, 2, 127, (byte) 255},
                "The quick brown fox jumps over the lazy dog 0123456789".getBytes(StandardCharsets.US_ASCII)
        };
        for (byte[] input : cases) {
            byte[] encoded = HuffmanByteCodec.encodeData(input);
            byte[] decoded = HuffmanByteCodec.decodeData(encoded, 0, encoded.length);
            assertEquals(input.length, decoded.length, "decoded length must equal actual size");
            assertArrayEquals(input, decoded, "decoded content must match input");
        }
    }
}
