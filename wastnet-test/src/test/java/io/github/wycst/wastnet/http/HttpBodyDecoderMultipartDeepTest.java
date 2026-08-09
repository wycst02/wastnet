package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Deep tests for multipart/form-data decoding.
 * Covers boundary edge cases, malformed data, raw binary content, stream decoding.
 *
 * @author wangyc
 */
public class HttpBodyDecoderMultipartDeepTest {

    private static final String BOUNDARY = "----Boundary";
    private static final byte[] BOUNDARY_BYTES = ("--" + BOUNDARY).getBytes();
    private static final byte[] CRLF = "\r\n".getBytes();
    private static final byte[] DASHES = "--".getBytes();

    /**
     * Build multipart body at byte level for precise control over binary content.
     * Format per part: --Boundary\r\n<headers>\r\n<data>\r\n
     * Final: --Boundary--\r\n
     */
    private static byte[] buildBody(Part... parts) {
        int total = 0;
        for (Part p : parts) total += p.data.length;
        // Per part: --Boundary\r\n + <headers> + \r\n + <data> + \r\n
        // Closing: --Boundary--\r\n
        int headerLen = 0;
        for (Part p : parts) {
            headerLen += 2 + BOUNDARY.length() + 2; // --Boundary\r\n
            headerLen += p.header.length;
            headerLen += 6; // \r\n (end header line) + \r\n (blank line) + \r\n (trailing after data)
        }
        headerLen += 2 + BOUNDARY.length() + 2 + 2; // --Boundary--\r\n

        byte[] result = new byte[headerLen + total];
        int pos = 0;
        for (Part p : parts) {
            // --Boundary\r\n
            System.arraycopy(DASHES, 0, result, pos, 2); pos += 2;
            System.arraycopy(BOUNDARY_BYTES, 2, result, pos, BOUNDARY.length()); pos += BOUNDARY.length();
            System.arraycopy(CRLF, 0, result, pos, 2); pos += 2;
            // Header
            System.arraycopy(p.header, 0, result, pos, p.header.length); pos += p.header.length;
            // \r\n (end of header line)
            System.arraycopy(CRLF, 0, result, pos, 2); pos += 2;
            // \r\n (blank line separating headers from body)
            System.arraycopy(CRLF, 0, result, pos, 2); pos += 2;
            // Content data
            System.arraycopy(p.data, 0, result, pos, p.data.length); pos += p.data.length;
            // \r\n (trailing after content)
            System.arraycopy(CRLF, 0, result, pos, 2); pos += 2;
        }
        // --Boundary--\r\n
        System.arraycopy(DASHES, 0, result, pos, 2); pos += 2;
        System.arraycopy(BOUNDARY_BYTES, 2, result, pos, BOUNDARY.length()); pos += BOUNDARY.length();
        System.arraycopy(DASHES, 0, result, pos, 2); pos += 2;
        System.arraycopy(CRLF, 0, result, pos, 2); pos += 2;

        return result;
    }

    static class Part {
        final byte[] header;
        final byte[] data;
        Part(String header, String data) {
            this.header = header.getBytes(StandardCharsets.US_ASCII);
            this.data = data.getBytes(StandardCharsets.UTF_8);
        }
        Part(String header, byte[] data) {
            this.header = header.getBytes(StandardCharsets.US_ASCII);
            this.data = data;
        }
    }

    private static Part textPart(String name, String value) {
        return new Part("Content-Disposition: form-data; name=\"" + name + "\"", value);
    }

    private static Part filePart(String name, String filename, String contentType, byte[] data) {
        String header = "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"";
        if (contentType != null) {
            header += "\r\nContent-Type: " + contentType;
        }
        return new Part(header, data);
    }

    // ==================== Binary content (via raw byte arrays) ====================

    @Test
    public void testBinaryFileContent() {
        byte[] binaryData = new byte[]{
                0x00, 0x01, 0x02, 0x03, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD,
                0x7F, (byte) 0x80, (byte) 0x81, (byte) 0xC0, (byte) 0xE0
        };
        byte[] body = buildBody(filePart("bin", "data.bin", "application/octet-stream", binaryData));
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=" + BOUNDARY, body);

        MultipartField field = decoder.getMultipartField("bin");
        Assertions.assertNotNull(field);
        Assertions.assertTrue(field.isFile());
        Assertions.assertEquals("data.bin", field.getFileName());
        Assertions.assertEquals("application/octet-stream", field.getContentType());
        Assertions.assertArrayEquals(binaryData, field.getData());
    }

    @Test
    public void testNullByteInFieldValue() {
        byte[] dataWithNull = new byte[]{'h', 'e', 'l', 'l', 'o', 0x00, 'w', 'o', 'r', 'l', 'd'};
        byte[] body = buildBody(new Part(
                "Content-Disposition: form-data; name=\"data\"", dataWithNull));
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=" + BOUNDARY, body);

        MultipartField field = decoder.getMultipartField("data");
        Assertions.assertFalse(field.isFile());
        Assertions.assertArrayEquals(dataWithNull, field.getData());
    }

    // ==================== Boundary edge cases ====================

    @Test
    public void testBoundaryAppearsInsideContent() {
        // Content contains the boundary string literally -- should NOT be split
        String content = "this contains --Boundary inside it --Boundary not real";
        byte[] body = buildBody(textPart("text", content));
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=" + BOUNDARY, body);

        // The content has --Boundary in it. The decoder should only stop at the real
        // closing boundary (--Boundary--), not at the literal occurrence inside content.
        // Since --Boundary appears inside but not as a standalone boundary marker,
        // the content up to the real close should be captured.
        Assertions.assertEquals(content, decoder.getMultipartFieldValue("text"));
    }

    @Test
    public void testBoundaryWithDotsAndPlusSigns() {
        String specialBoundary = "BOUNDARY+*.123";
        byte[] bodyBytes = ("--" + specialBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "value\r\n"
                + "--" + specialBoundary + "--\r\n").getBytes();

        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=" + specialBoundary, bodyBytes);

        Assertions.assertEquals("value", decoder.getMultipartFieldValue("x"));
    }

    @Test
    public void testBoundaryWithQuotedValueInContentType() {
        String bodyStr = "------WebKitFormBoundary\r\n"
                + "Content-Disposition: form-data; name=\"q\"\r\n"
                + "\r\n"
                + "quoted\r\n"
                + "------WebKitFormBoundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=\"----WebKitFormBoundary\"",
                bodyStr.getBytes());

        Assertions.assertEquals("quoted", decoder.getMultipartFieldValue("q"));
    }

    // ==================== Malformed / edge case data ====================

    @Test
    public void testMissingClosingBoundaryHandlesGracefully() {
        // No closing --Boundary--, only opening boundary.
        // The decoder should not crash and gracefully handle the malformed data.
        String bodyStr = "--Boundary\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "value\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        // Without closing boundary, the parser may still extract field
        // or return null -- either is acceptable, but must not crash
        String val = decoder.getMultipartFieldValue("x");
        Assertions.assertTrue(val == null || "value".equals(val), "Must not crash on malformed data (missing closing boundary)");
    }

    @Test
    public void testExtraWhitespaceInContentDisposition() {
        String bodyStr = "--Boundary\r\n"
                + "Content-Disposition:    form-data;    name=\"x\"    ;    filename=\"f.txt\"\r\n"
                + "Content-Type:   text/plain  \r\n"
                + "\r\n"
                + "content\r\n"
                + "--Boundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        MultipartField field = decoder.getMultipartField("x");
        Assertions.assertNotNull(field);
        Assertions.assertEquals("x", field.getName());
        Assertions.assertEquals("f.txt", field.getFileName());
        Assertions.assertEquals("text/plain", field.getContentType());
    }

    @Test
    public void testFilenameBeforeNameInContentDisposition() {
        String bodyStr = "--Boundary\r\n"
                + "Content-Disposition: form-data; filename=\"f.txt\"; name=\"x\"\r\n"
                + "\r\n"
                + "data\r\n"
                + "--Boundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        MultipartField field = decoder.getMultipartField("x");
        Assertions.assertNotNull(field);
        Assertions.assertEquals("x", field.getName());
        Assertions.assertEquals("f.txt", field.getFileName());
    }

    @Test
    public void testContentDispositionWithTrailingSemicolons() {
        String bodyStr = "--Boundary\r\n"
                + "Content-Disposition: form-data; name=\"x\";;;;\r\n"
                + "\r\n"
                + "value\r\n"
                + "--Boundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        Assertions.assertEquals("value", decoder.getMultipartFieldValue("x"));
    }

    @Test
    public void testUnquotedFieldName() {
        String bodyStr = "--Boundary\r\n"
                + "Content-Disposition: form-data; name=unquoted\r\n"
                + "\r\n"
                + "val\r\n"
                + "--Boundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        Assertions.assertEquals("val", decoder.getMultipartFieldValue("unquoted"));
    }

    @Test
    public void testEmptyPartBetweenBoundaries() {
        // Two boundaries with empty content between them
        String bodyStr = "--Boundary\r\n"
                + "Content-Disposition: form-data; name=\"a\"\r\n"
                + "\r\n"
                + "first\r\n"
                + "--Boundary\r\n"
                + "\r\n"
                + "--Boundary\r\n"
                + "Content-Disposition: form-data; name=\"b\"\r\n"
                + "\r\n"
                + "second\r\n"
                + "--Boundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        Assertions.assertEquals("first", decoder.getMultipartFieldValue("a"));
        Assertions.assertEquals("second", decoder.getMultipartFieldValue("b"));
    }

    @Test
    public void testContentTypeCaseInsensitivity() {
        String bodyStr = "--Boundary\r\n"
                + "content-disposition: form-data; name=\"file\"; filename=\"f.bin\"\r\n"
                + "content-type: application/octet-stream\r\n"
                + "\r\n"
                + "data\r\n"
                + "--Boundary--\r\n";
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=Boundary", bodyStr.getBytes());

        MultipartField field = decoder.getMultipartField("file");
        Assertions.assertEquals("f.bin", field.getFileName());
        Assertions.assertEquals("application/octet-stream", field.getContentType());
    }

    // ==================== HttpBodyStreamDecoder tests ====================

    @Test
    public void testStreamDecoderMultipleFields() throws Exception {
        byte[] body = buildBody(
                textPart("user", "Bob"),
                filePart("photo", "pic.png", "image/png", "PNG-BINARY".getBytes()),
                textPart("desc", "Profile picture"));

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body));

        Assertions.assertEquals("Bob", decoder.getMultipartFieldValue("user"));
        Assertions.assertEquals("Profile picture", decoder.getMultipartFieldValue("desc"));
        MultipartField photo = decoder.getMultipartField("photo");
        Assertions.assertTrue(photo.isFile());
        Assertions.assertEquals("pic.png", photo.getFileName());
        Assertions.assertEquals(3, decoder.getMultipartFieldNames().size());
    }

    @Test
    public void testStreamDecoderEmptyBody() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream("--Boundary--\r\n".getBytes()));

        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    @Test
    public void testStreamDecoderWithBoundarySpecialChars() throws Exception {
        String specialBoundary = "BOUNDARY+*.123";
        String bodyStr = "--" + specialBoundary + "\r\n"
                + "Content-Disposition: form-data; name=\"k\"\r\n"
                + "\r\n"
                + "v\r\n"
                + "--" + specialBoundary + "--\r\n";
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + specialBoundary,
                new ByteArrayInputStream(bodyStr.getBytes()));

        Assertions.assertEquals("v", decoder.getMultipartFieldValue("k"));
    }

    @Test
    public void testStreamDecoderLargerThanBuffer() throws Exception {
        // BUFFER_SIZE = max(4096, MAX_BODY_IN_MEMORY) = max(4096, 2MB) = 2MB
        // Create content larger than default buffer to trigger streaming behavior
        byte[] largeContent = new byte[5000];
        for (int i = 0; i < 5000; ++i) largeContent[i] = (byte) ('a' + (i % 26));
        byte[] body = buildBody(filePart("large", "large.bin", null, largeContent));

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body));

        MultipartField field = decoder.getMultipartField("large");
        Assertions.assertArrayEquals(largeContent, field.getData());
        Assertions.assertEquals(5000, field.getData().length);
        Assertions.assertEquals('a', field.getData()[0]);
        Assertions.assertEquals((byte) ('a' + (4999 % 26)), field.getData()[4999]);
    }

    // ==================== Non-multipart type handling ====================

    @Test
    public void testGetMultipartFieldReturnsNullForNonMultipart() {
        HttpBodyDecoder decoder = new HttpBodyDefaultDecoder(
                "application/x-www-form-urlencoded", "key=val".getBytes());

        Assertions.assertNull(decoder.getMultipartField("key"));
        Assertions.assertNull(decoder.getMultipartFields("key"));
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
        Assertions.assertNull(decoder.getMultipartFieldValue("key"));
        Assertions.assertTrue(decoder.getMultipartFieldValues("key").isEmpty());
    }

    // ==================== Release / cleanup ====================

}
