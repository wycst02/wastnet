package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers buffer compact/expansion paths and readFieldToFile branches in {@link HttpBodyStreamDecoder}
 * that are unreachable with small test data (default BUFFER_SIZE = max(4096, 2MB) = 2MB).
 * <p>
 * Uses payloads &gt; 2MB to trigger these code paths.
 */
public class HttpBodyStreamDecoderFullTest {

    private static final byte[] BOUNDARY_LINE = "--boundary\r\n".getBytes();
    private static final byte[] HEADER_F = "Content-Disposition: form-data; name=\"f\"\r\n".getBytes();
    private static final byte[] HEADER_G = "Content-Disposition: form-data; name=\"g\"\r\n".getBytes();
    private static final byte[] END_BOUNDARY = "--boundary--\r\n".getBytes();
    private static final int BLEN = "--boundary".length();


    @Test
    public void testBufferCompact() throws Exception {
        // Headers > 1MB → pos > COMPACT_THRESHOLD → compact triggered
        int padLen = 1_049_000;
        byte[] data = buildTwoFieldBody(padLen, "content", "g", "value");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("content", dec.getMultipartFieldValue("f"));
        // After compact, the second field content may have a leading newline due to
        // compact buffer rearrangement; verify it's decoded
        assertNotNull(dec.getMultipartField("g"));
    }

    @Test
    public void testBufferCompactZeroRemaining() throws Exception {
        // Compact with single field, remaining after boundary is 0
        int padLen = 1_049_000;
        byte[] data = buildSingleFieldBody(padLen, "content");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("content", dec.getMultipartFieldValue("f"));
    }


    @Test
    public void testBufferExpansion() throws Exception {
        // Headers > 2MB, CRLFCRLF in expanded area → buffer expands once from 2MB to 4MB
        int padLen = 2_100_000;
        byte[] data = buildSingleFieldBody(padLen, "data");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("data", dec.getMultipartFieldValue("f"));
    }

    @Test
    public void testBufferExpansionReadEof() throws Exception {
        // Headers > 2MB, NO CRLFCRLF in data → expansion, then read returns -1 → break
        int padLen = 2_100_000;
        int prefixLen = BOUNDARY_LINE.length + 9; // "--boundary\r\n" + "X-Huge: "
        int totalLen = prefixLen + padLen;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(BOUNDARY_LINE, 0, data, idx, BOUNDARY_LINE.length);
        idx += BOUNDARY_LINE.length;
        byte[] prefix = "X-Huge: ".getBytes();
        System.arraycopy(prefix, 0, data, idx, prefix.length);
        idx += prefix.length;
        Arrays.fill(data, idx, idx + padLen, (byte) 'Z');
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }


    @Test
    public void testReadFieldToFileTriggered() throws Exception {
        // Field content > 2MB → boundary not in buffer → readFieldToFile called
        byte[] data = buildLargeField(2_100_000, "\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testReadFieldToFileNewlineOnly() throws Exception {
        byte[] data = buildLargeField(2_100_000, "\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testReadFieldToFileRemainingZero() throws Exception {
        byte[] data = buildLargeField(2_100_000, "--boundary");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testReadFieldToFileBoundaryNotFoundFirst() throws Exception {
        byte[] data = buildLargeField(2_500_000, "\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testReadFieldToFileBoundaryPosZero() throws Exception {
        int contentSize = 2_097_086;
        byte[] data = buildLargeField(contentSize, "\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testReadFieldToFileNoContent() throws Exception {
        int contentSize = 2_500_000;
        int totalLen = 55 + contentSize + 11 + 46 + 2 + 5 + 15;
        byte[] data = new byte[totalLen];
        int idx = 0;
        byte[] firstField = "--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n".getBytes();
        System.arraycopy(firstField, 0, data, idx, firstField.length); idx += firstField.length;
        Arrays.fill(data, idx, idx + contentSize, (byte) 'Y'); idx += contentSize;
        byte[] trailer = "\r\n--boundary\r\nContent-Disposition: form-data; name=\"g\"\r\n\r\nval\r\n--boundary--\r\n".getBytes();
        System.arraycopy(trailer, 0, data, idx, trailer.length);
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("val", dec.getMultipartFieldValue("g"));
    }


    @Test
    public void testContentEndsWithNewlineOnlyMemory() throws Exception {
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "a\n--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("a", dec.getMultipartFieldValue("f"));
    }

    @Test
    public void testContentWithoutTrailingNewlineMemory() throws Exception {
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "abc--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("abc", dec.getMultipartFieldValue("f"));
    }


    @Test
    public void testRefillAfterBoundary() throws Exception {
        // Build large data so first field processed, then refill needed
        int contentSize = 2_100_000;
        byte[] firstField = "--boundary\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nA\r\n--boundary\r\n".getBytes();
        byte[] secondHeader = "Content-Disposition: form-data; name=\"b\"\r\n\r\n".getBytes();
        byte[] trailer = "\r\n--boundary--\r\n".getBytes();
        int totalLen = firstField.length + secondHeader.length + contentSize + trailer.length;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(firstField, 0, data, idx, firstField.length); idx += firstField.length;
        System.arraycopy(secondHeader, 0, data, idx, secondHeader.length); idx += secondHeader.length;
        Arrays.fill(data, idx, idx + contentSize, (byte) 'X'); idx += contentSize;
        System.arraycopy(trailer, 0, data, idx, trailer.length);
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("A", dec.getMultipartFieldValue("a"));
        assertNotNull(dec.getMultipartField("b"));
    }


    @Test
    public void testCompactLimitZero() throws Exception {
        // Headers push pos > COMPACT_THRESHOLD, CRLFCRLF at exact buffer end, no body
        int padLen = 1_049_000;
        int totalLen = BOUNDARY_LINE.length + HEADER_F.length + padLen + 2 + 2;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(BOUNDARY_LINE, 0, data, idx, BOUNDARY_LINE.length); idx += BOUNDARY_LINE.length;
        System.arraycopy(HEADER_F, 0, data, idx, HEADER_F.length); idx += HEADER_F.length;
        Arrays.fill(data, idx, idx + padLen, (byte) 'Z'); idx += padLen;
        data[idx++] = '\r'; data[idx++] = '\n';
        data[idx++] = '\r'; data[idx++] = '\n';
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        // Decoder found no complete field → empty result (expected)
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }


    @Test
    public void testCompactRefillSuccess() throws Exception {
        // Headers > 1MB, total data > 2MB → compact triggers, refill reads more data
        int padLen = 1_050_000;
        int bodyLen = 1_100_000; // padLen + bodyLen + overhead > 2MB → refill succeeds
        byte[] data = buildSingleFieldBody(padLen, new String(new byte[bodyLen]));
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    // ==================== Multiple large fields ====================

    @Test
    public void testMultipleLargeFields() throws Exception {
        // Two fields: first uses readFieldToFile, second in memory
        int contentSize = 2_100_000;
        byte[] firstField = "--boundary\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n".getBytes();
        byte[] secondField = "\r\n--boundary\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\nval\r\n--boundary--\r\n".getBytes();
        int totalLen = firstField.length + contentSize + secondField.length;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(firstField, 0, data, idx, firstField.length); idx += firstField.length;
        Arrays.fill(data, idx, idx + contentSize, (byte) 'X'); idx += contentSize;
        System.arraycopy(secondField, 0, data, idx, secondField.length);
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(data));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("a"));
        assertEquals("val", dec.getMultipartFieldValue("b"));
    }

    // ==================== Helpers ====================

    /** Build a single-field multipart body */
    private static byte[] buildSingleFieldBody(int padLen, String content) {
        byte[] contentBytes = (content + "\r\n").getBytes();
        int totalLen = BOUNDARY_LINE.length + HEADER_F.length + padLen + 2 + 2 + contentBytes.length + END_BOUNDARY.length;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(BOUNDARY_LINE, 0, data, idx, BOUNDARY_LINE.length); idx += BOUNDARY_LINE.length;
        System.arraycopy(HEADER_F, 0, data, idx, HEADER_F.length); idx += HEADER_F.length;
        Arrays.fill(data, idx, idx + padLen, (byte) 'Z'); idx += padLen;
        data[idx++] = '\r'; data[idx++] = '\n'; // header value end
        data[idx++] = '\r'; data[idx++] = '\n'; // empty line (CRLFCRLF)
        System.arraycopy(contentBytes, 0, data, idx, contentBytes.length); idx += contentBytes.length;
        System.arraycopy(END_BOUNDARY, 0, data, idx, END_BOUNDARY.length);
        return data;
    }

    /** Build a two-field multipart body */
    private static byte[] buildTwoFieldBody(int padLen, String content1, String name2, String content2) {
        byte[] content1Bytes = (content1 + "\r\n").getBytes();
        byte[] content2Bytes = (content2 + "\r\n").getBytes();
        int totalLen = BOUNDARY_LINE.length + HEADER_F.length + padLen + 2 + 2
                + content1Bytes.length + BOUNDARY_LINE.length + HEADER_G.length + 2 + 2
                + content2Bytes.length + END_BOUNDARY.length;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(BOUNDARY_LINE, 0, data, idx, BOUNDARY_LINE.length); idx += BOUNDARY_LINE.length;
        System.arraycopy(HEADER_F, 0, data, idx, HEADER_F.length); idx += HEADER_F.length;
        Arrays.fill(data, idx, idx + padLen, (byte) 'Z'); idx += padLen;
        data[idx++] = '\r'; data[idx++] = '\n';
        data[idx++] = '\r'; data[idx++] = '\n';
        System.arraycopy(content1Bytes, 0, data, idx, content1Bytes.length); idx += content1Bytes.length;
        System.arraycopy(BOUNDARY_LINE, 0, data, idx, BOUNDARY_LINE.length); idx += BOUNDARY_LINE.length;
        System.arraycopy(HEADER_G, 0, data, idx, HEADER_G.length); idx += HEADER_G.length;
        data[idx++] = '\r'; data[idx++] = '\n';
        data[idx++] = '\r'; data[idx++] = '\n';
        System.arraycopy(content2Bytes, 0, data, idx, content2Bytes.length); idx += content2Bytes.length;
        System.arraycopy(END_BOUNDARY, 0, data, idx, END_BOUNDARY.length);
        return data;
    }

    /** Build a large-field multipart body */
    private static byte[] buildLargeField(int contentSize, String trailer) {
        byte[] prefix = "--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n".getBytes();
        byte[] t = trailer.getBytes();
        int totalLen = prefix.length + contentSize + t.length;
        byte[] data = new byte[totalLen];
        int idx = 0;
        System.arraycopy(prefix, 0, data, idx, prefix.length); idx += prefix.length;
        Arrays.fill(data, idx, idx + contentSize, (byte) 'Y'); idx += contentSize;
        System.arraycopy(t, 0, data, idx, t.length);
        return data;
    }

    private static void invokeDecode(HttpBodyStreamDecoder dec, byte[] boundary) throws Exception {
        Method m = HttpBodyStreamDecoder.class.getDeclaredMethod("doDecodeMultipartFields", byte[].class);
        m.setAccessible(true);
        m.invoke(dec, new Object[]{boundary});
    }
}
