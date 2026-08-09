package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpBodyDecoder} abstract class logic.
 *
 * @author wangyc
 */
public class HttpBodyDecoderUnitTest {

    // ==================== Content type detection ====================

    @Test
    public void testIsMultipart() {
        TestDecoder decoder = createDecoder("multipart/form-data; boundary=----123");
        Assertions.assertTrue(decoder.isMultipart());
    }

    @Test
    public void testIsFormUrlencoded() {
        TestDecoder decoder = createDecoder("application/x-www-form-urlencoded");
        Assertions.assertTrue(decoder.isFormUrlencoded());
    }

    @Test
    public void testIsJson() {
        Assertions.assertTrue(createDecoder("application/json").isJson());
        Assertions.assertTrue(createDecoder("text/json").isJson());
        Assertions.assertFalse(createDecoder("text/plain").isJson());
    }

    @Test
    public void testIsOctetStream() {
        Assertions.assertTrue(createDecoder("application/octet-stream").isOctetStream());
        Assertions.assertFalse(createDecoder("text/plain").isOctetStream());
    }

    @Test
    public void testNullContentType() {
        TestDecoder decoder = createDecoder(null);
        Assertions.assertFalse(decoder.isMultipart());
        Assertions.assertFalse(decoder.isFormUrlencoded());
        Assertions.assertFalse(decoder.isJson());
    }

    @Test
    public void testDefaultCharsetUtf8() {
        Assertions.assertEquals("UTF-8", createDecoder("text/plain").getCharset().name());
    }

    @Test
    public void testCharsetFromContentType() {
        Assertions.assertEquals("ISO-8859-1", createDecoder("text/plain; charset=iso-8859-1").getCharset().name());
    }

    // ==================== Boundary extraction ====================

    @Test
    public void testExtractBoundary() {
        TestDecoder decoder = new TestDecoder("multipart/form-data; boundary=----WebKitFormBoundary");
        byte[] boundary = decoder.testExtractBoundary();
        Assertions.assertNotNull(boundary);
        Assertions.assertEquals("------WebKitFormBoundary", new String(boundary));
    }

    @Test
    public void testExtractBoundaryReturnsNullWhenNotFound() {
        Assertions.assertNull(new TestDecoder("multipart/form-data").testExtractBoundary());
    }

    // ==================== Boyer-Moore ====================

    @Test
    public void testFindBytesFound() {
        byte[] data = "hello world".getBytes();
        byte[] pattern = "world".getBytes();
        int[] badChar = HttpBodyDecoder.buildBadCharTable(pattern);
        Assertions.assertEquals(6, HttpBodyDecoder.findBytes(data, 0, data.length, pattern, badChar));
    }

    @Test
    public void testFindBytesNotFound() {
        byte[] data = "hello world".getBytes();
        byte[] pattern = "xyz".getBytes();
        int[] badChar = HttpBodyDecoder.buildBadCharTable(pattern);
        Assertions.assertEquals(-1, HttpBodyDecoder.findBytes(data, 0, data.length, pattern, badChar));
    }

    // ==================== parseMultipartHeaders ====================

    @Test
    public void testParseMultipartHeadersWithName() {
        byte[] data = "Content-Disposition: form-data; name=\"username\"\r\n".getBytes();
        HttpBuf[] result = HttpBodyDecoder.parseMultipartHeaders(data, 0, data.length - 2);
        Assertions.assertNotNull(result[0]);
        Assertions.assertEquals("username", result[0].toString());
    }

    @Test
    public void testParseMultipartHeadersWithNameAndFilename() {
        byte[] data = "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\nContent-Type: text/plain\r\n".getBytes();
        HttpBuf[] result = HttpBodyDecoder.parseMultipartHeaders(data, 0, data.length - 2);
        Assertions.assertNotNull(result[0]);
        Assertions.assertEquals("file", result[0].toString());
        Assertions.assertNotNull(result[1]);
        Assertions.assertEquals("test.txt", result[1].toString());
        Assertions.assertNotNull(result[2]);
        Assertions.assertEquals("text/plain", result[2].toString());
    }

    // ==================== Helper ====================

    private static TestDecoder createDecoder(String contentType) {
        return new TestDecoder(contentType);
    }

    static class TestDecoder extends HttpBodyDecoder {
        TestDecoder(String contentType) { super(contentType); }
        @Override protected void doDecodeMultipartFields(byte[] boundaryBytes) {}
        @Override protected void decodeFormUrlencoded() {}
        byte[] testExtractBoundary() { return extractBoundary(); }
    }
}
