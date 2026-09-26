package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>Must run in isolation</b> due to system property.
 */
public class HttpBodyStreamDecoderDirectTest {

    static {
        System.setProperty("wastnet.http.max-body-in-memory", "1");
    }

    private static byte[] buildMultipart(int fieldSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < fieldSize; ++i) sb.append('X');
        sb.append("\r\n--boundary--\r\n");
        return sb.toString().getBytes();
    }

    @Test
    public void testSmallField() throws Exception {
        byte[] body = buildMultipart(100);
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder("multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testLargeFieldToFile() throws Exception {
        byte[] body = buildMultipart(2_500_000);
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder("multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    @Test
    public void testEmptyBody() throws Exception {
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder("multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(new byte[0]));
        invokeDecode(dec, "--boundary".getBytes());
        assertNull(dec.getMultipartField("f"));
    }

    @Test
    public void testNoBoundary() throws Exception {
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder("multipart/form-data; boundary=boundary",
                new ByteArrayInputStream("no boundary".getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNull(dec.getMultipartField("f"));
    }

    @Test
    public void testNullStream() throws Exception {
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder("multipart/form-data; boundary=boundary", null);
        invokeDecode(dec, "--boundary".getBytes());
        assertNull(dec.getMultipartField("f"));
    }

    // ==================== Empty field name  ====================

    @Test
    public void testEmptyFieldName() throws Exception {
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"\"\r\n"
                + "\r\n"
                + "data\r\n"
                + "--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== Boundary at exact end (, ) ====================

    @Test
    public void testBoundaryAtExactEnd() throws Exception {
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "data"
                + "--boundary";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    // ==================== CRLF short-circuit  ====================

    @Test
    public void testCarriageReturnWithoutNewline() throws Exception {
        String body = "--boundary\rX";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    @Test
    public void testCarriageReturnAtEndOfBuffer() throws Exception {
        // \r as the last byte, no room for \n check
        String body = "--boundary\r";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== Content CRLF stripping (, ) ====================

    @Test
    public void testBoundaryImmediatelyAfterCRLF() throws Exception {
        // Content is just \r\n before boundary → stripped to empty
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "\r\n--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("", dec.getMultipartField("f").getDataAsString(dec.getCharset()));
    }

    @Test
    public void testContentEndingWithoutNewline() throws Exception {
        // Content does NOT end with \n before boundary → skip CRLF strip
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "abc--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("abc", dec.getMultipartField("f").getDataAsString(dec.getCharset()));
    }

    @Test
    public void testContentEndingWithNewlineOnly() throws Exception {
        // Content ends with \n but not \r\n → only \n stripped
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "a\n--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("a", dec.getMultipartField("f").getDataAsString(dec.getCharset()));
    }

    // ==================== readFieldToFile: stream ends ====================

    @Test
    public void testReadFieldToFileStreamEnds() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 5000; ++i) sb.append('X');
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== Buffer compact : BUFFER_SIZE=4096 via system prop ====================

    @Test
    public void testBufferCompact() throws Exception {
        // Headers > 2048 bytes to trigger pos > COMPACT_THRESHOLD
        // Then compact copies remaining data, loop continues to second field
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 2000; ++i) pad.append('Z');
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "X-Pad: " + pad.toString() + "\r\n"
                + "\r\n"
                + "content\r\n"
                + "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"g\"\r\n"
                + "\r\n"
                + "value\r\n"
                + "--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("content", dec.getMultipartFieldValue("f"));
        assertEquals("value", dec.getMultipartFieldValue("g"));
    }

    // ==================== Buffer expansion  ====================

    @Test
    public void testBufferExpansion() throws Exception {
        // Headers > 4096 bytes without CRLFCRLF → buffer expands once
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4100; ++i) huge.append('Z');
        String body = "--boundary\r\n"
                + "X-Huge: " + huge.toString() + "\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "data\r\n"
                + "--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("data", dec.getMultipartFieldValue("f"));
    }

    // ==================== readFieldToFile skipContent via fieldName=null (/) ====================

    @Test
    public void testReadFieldToFileSkipContentNoName() throws Exception {
        // Large field with no fieldName → skipContent=true → readFieldToFile returns null, channel stays null
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data\r\n\r\n");
        for (int i = 0; i < 4500; ++i) sb.append('Z');
        sb.append("\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== CRLF stripping: contentEnd just > pos (/) ====================

    @Test
    public void testContentEndingJustAbovePos() throws Exception {
        // Content ends with \r\n where contentEnd is just greater than pos
        String body = "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n"
                + "\r\n"
                + "ab\r\n"
                + "--boundary--\r\n";
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(body.getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertEquals("ab", dec.getMultipartFieldValue("f"));
    }

    // ==================== readFieldToFile contentEnd == 0 ( false) ====================

    @Test
    public void testReadFieldToFileContentEndZero() throws Exception {
        // Boundary at position 0 after read → contentEnd=0 → no content write
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 5000; ++i) sb.append('Z');
        sb.append("\r\n--boundary\r\nContent-Disposition: form-data; name=\"g\"\r\n\r\nval\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("val", dec.getMultipartFieldValue("g"));
    }

    private static void invokeDecode(HttpBodyStreamDecoder dec, byte[] b) throws Exception {
        dec.doDecodeMultipartFields(b);
    }
}
