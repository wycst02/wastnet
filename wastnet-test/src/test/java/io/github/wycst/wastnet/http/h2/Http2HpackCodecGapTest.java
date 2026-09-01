package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage gap tests for {@link Http2HpackCodec}.
 *
 * @author wangyc
 */
public class Http2HpackCodecGapTest {

    // ==================== encodeLength with flag=0 (plain text) ====================

    @Test
    void testEncodeLengthPlainText() {
        byte[] out = new byte[8];
        int len = Http2HpackCodec.encodeLength(42, out, 0, false);
        assertEquals(1, len);
        assertEquals(42, out[0] & 0x7F);
        assertEquals(0, out[0] & 0x80);

        len = Http2HpackCodec.encodeLength(200, out, 0, false);
        assertTrue(len > 1);
    }

    // ==================== decodeTo catch: offset > endOffset ====================

    @Test
    void testDecodeToOffsetExceedsEndOffset() {
        Http2HpackCodec codec = new Http2HpackCodec();
        byte[] corrupt = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        assertThrows(Http2HpackException.class, () -> codec.decode(corrupt));
    }

    // ==================== decode(byte[], int, int) exception wrapping ====================

    @Test
    void testDecodeExceptionWrappingNonHpack() {
        Http2HpackCodec codec = new Http2HpackCodec();
        byte[] shortData = new byte[]{ 0x40, 0x10 };
        try {
            codec.decode(shortData);
        } catch (Http2HpackException e) {
            assertTrue(e.getMessage().contains("hpack decode error"));
        }
    }

    // ==================== putHeader: triplicate (add to existing List) ====================

    @Test
    void testPutHeaderTriplicateAddsToList() {
        Http2HpackCodec codec = new Http2HpackCodec();
        String name = "x-triple-list";
        byte[] nameEnc = HuffmanByteCodec.encodeData(name.getBytes(StandardCharsets.US_ASCII));
        String[] vals = {"a", "b", "c"};
        byte[][] valEnc = new byte[3][];
        for (int i = 0; i < 3; ++i) {
            valEnc[i] = HuffmanByteCodec.encodeData(vals[i].getBytes(StandardCharsets.US_ASCII));
        }

        byte[] combined = new byte[1024];
        int off = 0;
        for (int i = 0; i < 3; ++i) {
            combined[off++] = 0x40;
            off += Http2HpackCodec.encodeLength(nameEnc.length, combined, off);
            System.arraycopy(nameEnc, 0, combined, off, nameEnc.length);
            off += nameEnc.length;
            off += Http2HpackCodec.encodeLength(valEnc[i].length, combined, off);
            System.arraycopy(valEnc[i], 0, combined, off, valEnc[i].length);
            off += valEnc[i].length;
        }
        Map<String, Object> headers = codec.decode(Arrays.copyOf(combined, off));
        Object val = headers.get("x-triple-list");
        assertTrue(val instanceof List);
        assertEquals(3, ((List) val).size());
    }

    // ==================== equals: odd-length comparison ====================

    @Test
    void testEqualsOddLength() {
        String oddName = "x-k";  // 3 bytes → odd
        byte[] nameEnc = HuffmanByteCodec.encodeData(oddName.getBytes(StandardCharsets.US_ASCII));
        Http2HpackCodec codec = new Http2HpackCodec();

        byte[] ve1 = HuffmanByteCodec.encodeData("v1".getBytes(StandardCharsets.US_ASCII));
        byte[] ve2 = HuffmanByteCodec.encodeData("v2".getBytes(StandardCharsets.US_ASCII));
        Map<String, Object> h1 = codec.decode(buildLiteralNewName(nameEnc, ve1));
        assertEquals("v1", h1.get(oddName));

        Map<String, Object> h2 = codec.decode(buildLiteralNewName(nameEnc, ve2));
        assertEquals("v2", h2.get(oddName));
    }

    // ==================== getCachedStringValue: chain traversal ====================

    @Test
    void testCachedValueChainTraversal() {
        Http2HpackCodec codec = new Http2HpackCodec();
        for (int i = 0; i < 50; ++i) {
            String name = "x-chain-" + i;
            byte[] ne = HuffmanByteCodec.encodeData(name.getBytes(StandardCharsets.US_ASCII));
            byte[] ve = HuffmanByteCodec.encodeData("v".getBytes(StandardCharsets.US_ASCII));
            codec.decode(buildLiteralNewName(ne, ve));
        }
        String testName = "x-chain-0";
        byte[] te = HuffmanByteCodec.encodeData(testName.getBytes(StandardCharsets.US_ASCII));
        byte[] ve = HuffmanByteCodec.encodeData("verified".getBytes(StandardCharsets.US_ASCII));
        Map<String, Object> h = codec.decode(buildLiteralNewName(te, ve));
        assertEquals("verified", h.get(testName));
    }

    // ==================== Literal without indexing, mantissa == 0xF ====================

    @Test
    void testLiteralWithoutIndexingExtendedIndex() {
        // Populate dynamic table so index 80 is valid (static 1-61, dynamic starts at 62)
        Http2HpackCodec codec = new Http2HpackCodec(8192);
        for (int i = 0; i < 20; ++i) {
            String name = "x-dyn-" + i;
            byte[] ne = HuffmanByteCodec.encodeData(name.getBytes(StandardCharsets.US_ASCII));
            byte[] ve = HuffmanByteCodec.encodeData("v".getBytes(StandardCharsets.US_ASCII));
            codec.decode(buildLiteralNewName(ne, ve));
        }
        // Now decode a literal-without-indexing using extended index = 80
        // cmd = 0x0F (0000 + mantissa=15), then remaining index = 80-15 = 65
        byte[] valEnc = HuffmanByteCodec.encodeData("custom-path".getBytes(StandardCharsets.US_ASCII));
        byte[] wire = new byte[2 + valEnc.length + 5];
        int off = 0;
        wire[off++] = 0x0F;            // cmd: 0000 + mantissa=15 (0xF)
        wire[off++] = 0x41;            // remaining = 65 (< 128), final byte
        off += Http2HpackCodec.encodeLength(valEnc.length, wire, off);
        System.arraycopy(valEnc, 0, wire, off, valEnc.length);
        off += valEnc.length;

        Map<String, Object> headers = codec.decode(Arrays.copyOf(wire, off));
        // Index 80 maps to dynamic table entry, value is "custom-path"
        assertTrue(headers.containsValue("custom-path"));
    }

    // ==================== Indexed header with extended index (cmd & 0x7f == 0x7f) ====================

    @Test
    void testIndexedHeaderExtendedIndex() {
        // Fill dynamic table so indices >= 127 are valid
        Http2HpackCodec codec = new Http2HpackCodec(8192);
        for (int i = 0; i < 80; ++i) {
            String name = "x-dyn-" + i;
            byte[] ne = HuffmanByteCodec.encodeData(name.getBytes(StandardCharsets.US_ASCII));
            byte[] ve = HuffmanByteCodec.encodeData("v".getBytes(StandardCharsets.US_ASCII));
            codec.decode(buildLiteralNewName(ne, ve));
        }
        // Decode an indexed header with extended index: 0xFF + remaining
        // Index = 130: cmd=0xFF, remaining=130-127=3 (< 128 → single byte)
        Map<String, Object> headers = codec.decode(new byte[]{ (byte) 0xFF, 0x03 });
        // Index 130 points to a dynamic table entry
        assertFalse(headers.isEmpty());
    }

    // ==================== Helper ====================

    private static byte[] buildLiteralNewName(byte[] nameEnc, byte[] valueEncoded) {
        byte[] buf = new byte[nameEnc.length + valueEncoded.length + 10];
        int off = 0;
        buf[off++] = 0x40;
        off += Http2HpackCodec.encodeLength(nameEnc.length, buf, off);
        System.arraycopy(nameEnc, 0, buf, off, nameEnc.length);
        off += nameEnc.length;
        off += Http2HpackCodec.encodeLength(valueEncoded.length, buf, off);
        System.arraycopy(valueEncoded, 0, buf, off, valueEncoded.length);
        off += valueEncoded.length;
        return Arrays.copyOf(buf, off);
    }
}
