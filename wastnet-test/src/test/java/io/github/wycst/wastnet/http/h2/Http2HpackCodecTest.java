package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.h2.Http2HpackCodec;
import io.github.wycst.wastnet.http.h2.Http2HpackException;
import io.github.wycst.wastnet.http.h2.HuffmanByteCodec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Unit tests for Http2HpackCodec (RFC 7541 HPACK compression).
 */
public class Http2HpackCodecTest {

    @Test
    public void testDecodeEmptyInput() {
        Http2HpackCodec codec = new Http2HpackCodec();
        Map<String, Object> headers = codec.decode(new byte[0]);
        Assertions.assertNotNull(headers);
        Assertions.assertTrue(headers.isEmpty());
    }

    @Test
    public void testDecodeStaticTableIndexed() {
        // Index 2 = :method GET (static table, single-byte indexed header: 10000010 = 0x82)
        Http2HpackCodec codec = new Http2HpackCodec();
        Map<String, Object> headers = codec.decode(new byte[]{(byte) 0x82});
        Assertions.assertEquals(1, headers.size());
        Assertions.assertEquals("GET", headers.get(":method"));
    }

    @Test
    public void testDecodeMultipleStaticIndexes() {
        // Index 2 = :method GET (0x82), Index 4 = :path / (0x84), Index 6 = :scheme http (0x86)
        Http2HpackCodec codec = new Http2HpackCodec();
        Map<String, Object> headers = codec.decode(new byte[]{(byte) 0x82, (byte) 0x84, (byte) 0x86});
        Assertions.assertEquals(3, headers.size());
        Assertions.assertEquals("GET", headers.get(":method"));
        Assertions.assertEquals("/", headers.get(":path"));
        Assertions.assertEquals("http", headers.get(":scheme"));
    }

    @Test
    public void testDecodeLiteralWithIndexedName() {
        // RFC 7541 Appendix C.2.4: Literal Header Field Never Indexed
        // Index 28 = :path, Huffman-encoded value "/sample/path"
        Http2HpackCodec codec = new Http2HpackCodec();

        // Build: literal never indexed (0100) + 1111 (name index 28 via extended length)
        // Actually, let's use a simpler approach: encode a literal with Huffman
        String customHeader = "x-custom";
        String customValue = "test-value";

        // Encode using known pattern: literal with name+value (index 0x40 = 64)
        byte[] nameEncoded = HuffmanByteCodec.encodeData(customHeader.getBytes(StandardCharsets.US_ASCII));
        byte[] valueEncoded = HuffmanByteCodec.encodeData(customValue.getBytes(StandardCharsets.US_ASCII));

        // Build wire format: 0x40 | length(name-encoded) | name-encoded | length(value-encoded) | value-encoded
        byte[] wire = new byte[1 + nameEncoded.length + 1 + valueEncoded.length + 5];
        int off = 0;
        wire[off++] = 0x40; // literal with incremental indexing, new name
        off += Http2HpackCodec.encodeLength(nameEncoded.length, wire, off);
        System.arraycopy(nameEncoded, 0, wire, off, nameEncoded.length);
        off += nameEncoded.length;
        off += Http2HpackCodec.encodeLength(valueEncoded.length, wire, off);
        System.arraycopy(valueEncoded, 0, wire, off, valueEncoded.length);
        off += valueEncoded.length;

        byte[] finalWire = java.util.Arrays.copyOf(wire, off);
        Map<String, Object> headers = codec.decode(finalWire);
        // The header was added to dynamic table, so after reset we can still decode
        Assertions.assertTrue(headers.containsKey("x-custom"));
        Assertions.assertEquals("test-value", headers.get("x-custom"));
    }

    @Test
    public void testDynamicTableSizeUpdate() {
        // Dynamic table size update: 001 + 3F (00111111 = 0x3F)
        // Sets max table size to 0x7F = 127
        Http2HpackCodec codec = new Http2HpackCodec(4096);
        Map<String, Object> headers = codec.decode(new byte[]{(byte) 0x3F, 0x00});
        Assertions.assertNotNull(headers);
        Assertions.assertTrue(headers.isEmpty());
    }

    @Test
    public void testCustomMaxHeaderSize() {
        Http2HpackCodec codec = new Http2HpackCodec(512, StandardCharsets.US_ASCII);
        Assertions.assertNotNull(codec);
        Map<String, Object> headers = codec.decode(new byte[]{(byte) 0x82});
        Assertions.assertEquals("GET", headers.get(":method"));
    }

    @Test
    public void testSetMaxHeaderSize() {
        Http2HpackCodec codec = new Http2HpackCodec(4096);
        codec.setMaxHeaderSize(2048); // reduce table size
        Map<String, Object> headers = codec.decode(new byte[]{(byte) 0x82});
        Assertions.assertEquals("GET", headers.get(":method"));
    }

    @Test
    public void testDecodeMalformedInputThrowsException() {
        Http2HpackCodec codec = new Http2HpackCodec();
        Assertions.assertThrows(Http2HpackException.class, () -> codec.decode(new byte[]{(byte) 0x7F, (byte) 0x7F, (byte) 0x7F, (byte) 0x7F}));
    }

    @Test
    public void testDecodeMultipleStreams() {
        // Two separate decode calls should work independently (fresh codec per stream)
        Http2HpackCodec codec1 = new Http2HpackCodec();
        Http2HpackCodec codec2 = new Http2HpackCodec();

        Map<String, Object> h1 = codec1.decode(new byte[]{(byte) 0x82, (byte) 0x84});
        Assertions.assertEquals("GET", h1.get(":method"));
        Assertions.assertEquals("/", h1.get(":path"));

        Map<String, Object> h2 = codec2.decode(new byte[]{(byte) 0x84, (byte) 0x86});
        Assertions.assertEquals("/", h2.get(":path"));
        Assertions.assertEquals("http", h2.get(":scheme"));
    }
}
