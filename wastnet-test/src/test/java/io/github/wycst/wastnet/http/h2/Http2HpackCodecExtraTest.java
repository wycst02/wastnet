package io.github.wycst.wastnet.http.h2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage gap tests for {@link Http2HpackCodec}: header-block validation, client (response)
 * decoding mode, dynamic table size update errors, trailer mode and large-name handling.
 *
 * <p>All cases are pure byte-level encode/decode assertions with no JDK-version-specific behavior.</p>
 */
public class Http2HpackCodecExtraTest {

    // Build a literal-with-incremental-indexing header with plain (non-Huffman) name+value.
    private static byte[] buildLiteralPlain(String name, String value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        byte[] valueBytes = value.getBytes(StandardCharsets.US_ASCII);
        byte[] buf = new byte[1 + nameBytes.length + valueBytes.length + 12];
        int off = 0;
        buf[off++] = 0x40; // B-a: literal with incremental indexing, new name
        off += Http2HpackCodec.encodeLength(nameBytes.length, buf, off, false);
        System.arraycopy(nameBytes, 0, buf, off, nameBytes.length);
        off += nameBytes.length;
        off += Http2HpackCodec.encodeLength(valueBytes.length, buf, off, false);
        System.arraycopy(valueBytes, 0, buf, off, valueBytes.length);
        off += valueBytes.length;
        return Arrays.copyOf(buf, off);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    @Test
    void testEmptyHeaderNameRejected() {
        Http2HpackCodec codec = new Http2HpackCodec();
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decode(buildLiteralPlain("", "v")));
        assertTrue(ex.getMessage().contains("Empty header field name"));
    }

    @Test
    void testPseudoAfterRegularRejected() {
        Http2HpackCodec codec = new Http2HpackCodec();
        // a regular header first, then an indexed pseudo-header (:method = 0x82)
        byte[] block = concat(buildLiteralPlain("x-foo", "1"), new byte[]{(byte) 0x82});
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decode(block));
        assertTrue(ex.getMessage().contains("Pseudo-header field appears after"));
    }

    @Test
    void testUnknownPseudoRejected() {
        Http2HpackCodec codec = new Http2HpackCodec();
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decode(buildLiteralPlain(":custom", "v")));
        assertTrue(ex.getMessage().contains("Unknown or forbidden pseudo-header field"));
    }

    @Test
    void testStatusRejectedInServerMode() {
        // :status is not a valid pseudo-header in default (server/request) mode
        Http2HpackCodec codec = new Http2HpackCodec();
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decode(new byte[]{(byte) 0x88})); // indexed :status (static 8)
        assertTrue(ex.getMessage().contains("Unknown or forbidden pseudo-header field"));
    }

    @Test
    void testForbiddenConnectionHeadersRejected() {
        String[] forbidden = {"connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"};
        for (String name : forbidden) {
            Http2HpackCodec codec = new Http2HpackCodec();
            Http2HpackException ex = assertThrows(Http2HpackException.class,
                    () -> codec.decode(buildLiteralPlain(name, "v")));
            assertTrue(ex.getMessage().contains("Forbidden connection-specific header field"),
                    "expected forbidden for " + name);
        }
    }

    @Test
    void testSetValidateFalseSkipsForbidden() {
        Http2HpackCodec codec = new Http2HpackCodec();
        codec.setValidate(false);
        Map<String, Object> headers = codec.decode(buildLiteralPlain("connection", "keep"));
        assertEquals("keep", headers.get("connection"));
    }

    @Test
    void testClientModeDecodesStatus() {
        Http2HpackCodec codec = new Http2HpackCodec().client();
        Map<String, Object> headers = codec.decode(new byte[]{(byte) 0x88}); // :status 200
        assertEquals("200", headers.get(":status"));
    }

    @Test
    void testDynamicTableSizeUpdateAfterHeaderRejected() {
        Http2HpackCodec codec = new Http2HpackCodec(4096);
        // :method first, then a dynamic table size update (0x3F 0x00)
        byte[] block = concat(new byte[]{(byte) 0x82}, new byte[]{(byte) 0x3F, 0x00});
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decode(block));
        assertTrue(ex.getMessage().contains("MUST appear before any header field"));
    }

    @Test
    void testDynamicTableSizeUpdateExceedsLimitRejected() {
        Http2HpackCodec codec = new Http2HpackCodec(4096);
        // size update with an extended value far above the 2 MB hard cap
        byte[] block = new byte[]{(byte) 0x3F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decode(block));
        assertTrue(ex.getMessage().contains("exceeds hard limit"));
    }

    @Test
    void testTrailerModeRejectsPseudo() {
        Http2HpackCodec codec = new Http2HpackCodec();
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        Http2HpackException ex = assertThrows(Http2HpackException.class,
                () -> codec.decodeTo(new byte[]{(byte) 0x82}, 0, 1, headers, true));
        assertTrue(ex.getMessage().contains("Pseudo-header field appears after"));
    }

    @Test
    void testLargeNameSkipsCacheAndDecodes() {
        // name longer than MAX_CACHE_STRING_LEN (48) exercises the non-cache branch and the
        // extended 7-bit length encoding for names.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; ++i) sb.append('a');
        String longName = sb.toString();
        Http2HpackCodec codec = new Http2HpackCodec();
        Map<String, Object> headers = codec.decode(buildLiteralPlain(longName, "v"));
        assertEquals("v", headers.get(longName));
    }
}
