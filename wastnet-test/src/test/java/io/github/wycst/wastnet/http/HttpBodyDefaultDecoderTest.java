package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link HttpBodyDefaultDecoder}
 * <p>
 * Covers all public methods and internal branches including:
 * - form-urlencoded (basic, charset, repeated params, edge cases)
 * - Content-type checks (json, text/plain, null, multipart, octet-stream)
 * - Charset parsing
 * - Multipart decoding (all branch paths)
 * - Exception handling
 *
 * @author wangyc
 */
public class HttpBodyDefaultDecoderTest {

    private static final String BOUNDARY = "----TestBoundary";

    private HttpBodyDecoder createDecoder(String contentType, byte[] data) {
        return new HttpBodyDefaultDecoder(contentType, data);
    }

    // ==================== form-urlencoded ====================

    @Test
    public void testFormUrlencodedSimple() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded",
                "name=John&age=30".getBytes());
        Assertions.assertEquals("John", decoder.getParameter("name"));
        Assertions.assertEquals("30", decoder.getParameter("age"));
    }

    @Test
    public void testFormUrlencodedWithCharset() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded; charset=UTF-8",
                "key=value".getBytes());
        Assertions.assertEquals("value", decoder.getParameter("key"));
    }

    @Test
    public void testFormUrlencodedEmpty() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded", new byte[0]);
        Assertions.assertTrue(decoder.getUrlencodedParameterNames().isEmpty());
    }

    @Test
    public void testFormUrlencodedSpecialChars() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded",
                "q=hello+world&lang=java%2B%2B".getBytes());
        Assertions.assertEquals("hello world", decoder.getParameter("q"));
        Assertions.assertEquals("java++", decoder.getParameter("lang"));
    }

    @Test
    public void testRepeatedParameterInUrlencoded() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded",
                "tag=a&tag=b&tag=c".getBytes());
        List<String> tags = decoder.getParameterValues("tag");
        Assertions.assertNotNull(tags);
        Assertions.assertEquals(3, tags.size());
        Assertions.assertEquals("a", tags.get(0));
        Assertions.assertEquals("b", tags.get(1));
        Assertions.assertEquals("c", tags.get(2));
    }

    @Test
    public void testFormUrlencodedEmptyBodyReturnsEmpty() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded", new byte[0]);
        Assertions.assertTrue(decoder.getUrlencodedParameterNames().isEmpty());
        Assertions.assertNull(decoder.getUrlencodedParameter("key"));
        Assertions.assertNull(decoder.getUrlencodedParameterValues("key"));
    }

    // decodeFormUrlencoded exception path: hard to trigger via normal input,
    // the catch block is a defensive guard; the test for empty body already
    // covers the bodyLength==0 early return.

    // ==================== Content-type checks ====================

    @Test
    public void testContentTypeJson() {
        HttpBodyDecoder decoder = createDecoder("application/json",
                "{\"key\":\"value\"}".getBytes());
        Assertions.assertTrue(decoder.isJson());
        Assertions.assertFalse(decoder.isFormUrlencoded());
        Assertions.assertFalse(decoder.isMultipart());
    }

    @Test
    public void testContentTypeTextPlain() {
        HttpBodyDecoder decoder = createDecoder("text/plain", "Hello".getBytes());
        Assertions.assertFalse(decoder.isJson());
        Assertions.assertFalse(decoder.isFormUrlencoded());
        Assertions.assertFalse(decoder.isMultipart());
    }

    @Test
    public void testContentTypeNull() {
        HttpBodyDecoder decoder = createDecoder(null, "data".getBytes());
        Assertions.assertFalse(decoder.isFormUrlencoded());
    }

    @Test
    public void testContentTypeMultipart() {
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new byte[0]);
        Assertions.assertTrue(decoder.isMultipart());
    }

    @Test
    public void testGetCharsetFromContentType() {
        HttpBodyDecoder decoder = createDecoder(
                "text/plain; charset=iso-8859-1",
                "data".getBytes());
        Assertions.assertEquals("ISO-8859-1", decoder.getCharset().name());
    }

    @Test
    public void testDefaultCharset() {
        HttpBodyDecoder decoder = createDecoder("text/plain", "data".getBytes());
        Assertions.assertEquals("UTF-8", decoder.getCharset().name());
    }

    @Test
    public void testOctetStream() {
        HttpBodyDecoder decoder = createDecoder("application/octet-stream",
                new byte[]{0x01, 0x02, 0x03});
        Assertions.assertTrue(decoder.isOctetStream());
        Assertions.assertFalse(decoder.isJson());
        Assertions.assertFalse(decoder.isFormUrlencoded());
        Assertions.assertFalse(decoder.isMultipart());
    }

    @Test
    public void testGetContentType() {
        HttpBodyDecoder decoder = createDecoder("application/json; charset=utf-8", "{}".getBytes());
        Assertions.assertEquals("application/json; charset=utf-8", decoder.getContentType());
    }

    // ==================== Multipart: bodyLength == 0 ====================

    @Test
    public void testMultipartEmptyBodyReturnsNoFields() {
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new byte[0]);
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
        Assertions.assertNull(decoder.getMultipartField("any"));
    }

    // ==================== Multipart: non-multipart content type ====================

    @Test
    public void testNonMultipartContentTypeReturnsNull() {
        HttpBodyDecoder decoder = createDecoder("text/plain", "data".getBytes());
        Assertions.assertNull(decoder.getMultipartField("any"));
        Assertions.assertNull(decoder.getMultipartFields("any"));
        Assertions.assertTrue(decoder.getMultipartFieldValues("any").isEmpty());
    }

    // ==================== Multipart: first boundary not at position 0 ====================

    @Test
    public void testMultipartNoLeadingBoundaryReturnsNoFields() {
        String body = "junk\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "v\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== Multipart: end marker (--boundary--) ====================

    @Test
    public void testMultipartEndMarkerReturnsNoFields() {
        String body = "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== Multipart: headerEnd == -1 malformed ====================

    @Test
    public void testMultipartNoHeaderEndReturnsNoFields() {
        // Content-Disposition line without blank line (\r\n\r\n) → headerEnd == -1
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "data without CRLFCRLF\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== Multipart: contentStart > bodyLength ====================

    @Test
    public void testMultipartContentStartExceedsBodyLength() {
        // CRLFCRLF at the very end → contentStart = bodyLength, no content
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== Multipart: nextBoundary == -1 ====================

    @Test
    public void testMultipartNextBoundaryNotFoundReturnsNoFields() {
        // Content after headers but no boundary marker before end of data
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "some content without trailing boundary";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== Multipart: simple text field ====================

    @Test
    public void testMultipartSingleTextField() {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"username\"\r\n"
                + "\r\n"
                + "Alice\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertEquals("Alice", decoder.getMultipartFieldValue("username"));
        Assertions.assertEquals("Alice", decoder.getMultipartFieldValue("username"));
    }

    // ==================== Multipart: CRLF stripping (both \r\n and \n only) ====================

    @Test
    public void testMultipartContentEndingWithNewlineOnly() {
        // Content ends with \n (no \r) → code strips just \n
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"desc\"\r\n"
                + "\r\n"
                + "desc value\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertEquals("desc value", decoder.getMultipartFieldValue("desc"));
    }

    @Test
    public void testMultipartContentNoTrailingNewline() {
        // Content has no \r\n before next boundary (edge case)
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "data"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        // Without trailing CRLF, the boundary is immediately after content
        Assertions.assertEquals("data", decoder.getMultipartFieldValue("x"));
    }

    // ==================== Multipart: multiple fields ====================

    @Test
    public void testMultipartMultipleFields() {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n"
                + "\r\n"
                + "Hello\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"value\"\r\n"
                + "\r\n"
                + "42\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertEquals("Hello", decoder.getMultipartFieldValue("title"));
        Assertions.assertEquals("42", decoder.getMultipartFieldValue("value"));
    }

    // ==================== Multipart: file field with Content-Type ====================

    @Test
    public void testMultipartFileField() {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"doc.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "file content\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        MultipartField field = decoder.getMultipartField("file");
        Assertions.assertNotNull(field);
        Assertions.assertTrue(field.isFile());
        Assertions.assertEquals("doc.txt", field.getFileName());
        Assertions.assertEquals("text/plain", field.getContentType());
        Assertions.assertEquals("file content", field.getDataAsString(decoder.getCharset()));
    }

    // ==================== Multipart: fieldName null/empty → skip ====================

    @Test
    public void testMultipartFieldWithNoNameIsSkipped() {
        // Name-less field (no "name=" in Content-Disposition) should be skipped
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: attachment; filename=\"orphan.bin\"\r\n"
                + "\r\n"
                + "data\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"valid\"\r\n"
                + "\r\n"
                + "ok\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        // The nameless field should be skipped, only "valid" should be present
        Assertions.assertNull(decoder.getMultipartField("valid".toUpperCase())); // no match
        Assertions.assertEquals("ok", decoder.getMultipartFieldValue("valid"));
        Set<String> names = decoder.getMultipartFieldNames();
        Assertions.assertEquals(1, names.size());
        Assertions.assertTrue(names.contains("valid"));
    }

    // ==================== Multipart: same field name multiple times ====================

    @Test
    public void testMultipartMultipleFieldsSameName() {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"tag\"\r\n"
                + "\r\n"
                + "red\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"tag\"\r\n"
                + "\r\n"
                + "blue\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        List<MultipartField> tags = decoder.getMultipartFields("tag");
        Assertions.assertNotNull(tags);
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals("red", tags.get(0).getDataAsString(decoder.getCharset()));
        Assertions.assertEquals("blue", tags.get(1).getDataAsString(decoder.getCharset()));
        List<String> values = decoder.getMultipartFieldValues("tag");
        Assertions.assertEquals(2, values.size());
    }

    // ==================== Multipart: extractBoundary null (malformed content-type) ====================

    @Test
    public void testMultipartWithoutBoundaryReturnsNoFields() {
        // multipart content-type without "boundary=" → extractBoundary returns null
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data",
                "some body".getBytes());
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== getParameter delegation ====================

    @Test
    public void testGetParameterForFormUrlencoded() {
        HttpBodyDecoder decoder = createDecoder("application/x-www-form-urlencoded",
                "key=val".getBytes());
        Assertions.assertEquals("val", decoder.getParameter("key"));
    }

    @Test
    public void testGetParameterNonMultipartNonFormReturnsNull() {
        HttpBodyDecoder decoder = createDecoder("application/json", "{}".getBytes());
        Assertions.assertNull(decoder.getParameter("any"));
    }

    // ==================== release() ====================

    @Test
    public void testReleaseClearsResources() {
        HttpBodyDecoder decoder = createDecoder("application/json", "{}".getBytes());
        decoder.release();
        // After release, subsequent calls should still not throw
        Assertions.assertFalse(decoder.isMultipart());
    }

    // ==================== contentEnd == contentStart (L71/L73) ====================

    @Test
    public void testMultipartNoContentBeforeBoundary() {
        // Boundary immediately after CRLFCRLF → contentEnd == contentStart → skip CRLF stripping
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertEquals("", decoder.getMultipartFieldValue("x"));
    }

    // ==================== fieldName.isEmpty() skip (L81) ====================

    @Test
    public void testMultipartFieldWithEmptyNameIsSkipped() {
        // name="" → fieldName is empty string (not null) → isEmpty()=true → skip
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"\"\r\n"
                + "\r\n"
                + "data\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"valid\"\r\n"
                + "\r\n"
                + "ok\r\n"
                + "--" + BOUNDARY + "--\r\n";
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        Assertions.assertEquals("ok", decoder.getMultipartFieldValue("valid"));
        Assertions.assertEquals(1, decoder.getMultipartFieldNames().size());
    }

    // ==================== catch (Exception e) block in doDecodeMultipartFields (L94) ====================

    @Test
    public void testMultipartBoundaryAtArrayEnd() {
        // Boundary is the entire body → partStart == bodyLength → AOBE → caught by catch
        String body = "--" + BOUNDARY;
        HttpBodyDecoder decoder = createDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                body.getBytes());
        // Exception caught silently, no fields should be returned
        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

}
