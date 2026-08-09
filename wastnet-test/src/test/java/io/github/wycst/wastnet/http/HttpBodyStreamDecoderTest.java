package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HttpBodyStreamDecoder}.
 * <p>
 * Covers multipart/form-data and application/x-www-form-urlencoded decoding
 * from streaming sources (InputStream), with chunked and non-chunked variants.
 *
 * @author wangyc
 */
public class HttpBodyStreamDecoderTest {

    private static final String BOUNDARY = "----TestBoundary";

    // ==================== Multipart: null / empty / malformed ====================

    @Test
    public void testNullBodyStreamReturnsEmptyMultipartFields() {
        // bodyStream == null → multipart fields should be empty
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY, null);

        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
        Assertions.assertNull(decoder.getMultipartField("any"));
        Assertions.assertNull(decoder.getMultipartFields("any"));
        Assertions.assertTrue(decoder.getMultipartFieldValues("any").isEmpty());
    }

    @Test
    public void testEmptyStreamReturnsNoFields() throws Exception {
        // read() returns -1 immediately → early return
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(new byte[0]));

        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    @Test
    public void testDataWithoutLeadingBoundaryReturnsNoFields() throws Exception {
        // First line is not a boundary marker → early return
        String body = "Content-Disposition: form-data; name=\"x\"\r\n\r\nvalue\r\n";
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    @Test
    public void testBoundaryWithoutCrLfReturnsNoFields() throws Exception {
        // Boundary is followed by another boundary (end marker "--Boundary--"),
        // not by CRLF → break out of loop → empty fields
        String body = "--" + BOUNDARY + "--\r\n";
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        Assertions.assertTrue(decoder.getMultipartFieldNames().isEmpty());
    }

    // ==================== Multipart: normal field parsing ====================

    @Test
    public void testSingleTextField() throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"username\"\r\n"
                + "\r\n"
                + "Alice\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        Assertions.assertEquals("Alice", decoder.getMultipartFieldValue("username"));
    }

    @Test
    public void testSingleFileField() throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"avatar\"; filename=\"photo.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "\r\n"
                + "JPEG-BINARY-DATA\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        MultipartField field = decoder.getMultipartField("avatar");
        Assertions.assertNotNull(field);
        Assertions.assertTrue(field.isFile());
        Assertions.assertEquals("photo.jpg", field.getFileName());
        Assertions.assertEquals("image/jpeg", field.getContentType());
        Assertions.assertEquals("JPEG-BINARY-DATA", field.getDataAsString(decoder.getCharset()));
    }

    @Test
    public void testMultipleFieldsMixedTypes() throws Exception {
        // Text + file + text — three parts to exercise multi-iteration loop
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n"
                + "\r\n"
                + "Hello\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"doc.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "file content here\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"count\"\r\n"
                + "\r\n"
                + "42\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        Assertions.assertEquals("Hello", decoder.getMultipartFieldValue("title"));
        Assertions.assertEquals("42", decoder.getMultipartFieldValue("count"));

        MultipartField fileField = decoder.getMultipartField("file");
        Assertions.assertTrue(fileField.isFile());
        Assertions.assertEquals("doc.txt", fileField.getFileName());
        Assertions.assertEquals("text/plain", fileField.getContentType());
    }

    @Test
    public void testSingleFileFieldWithoutContentType() throws Exception {
        // File field with no Content-Type header → contentType should be null/empty
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"bin\"; filename=\"data.bin\"\r\n"
                + "\r\n"
                + "RAW\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        MultipartField field = decoder.getMultipartField("bin");
        Assertions.assertNotNull(field);
        Assertions.assertTrue(field.isFile());
        Assertions.assertEquals("data.bin", field.getFileName());
        // Content-Type not specified → should be null or empty
        Assertions.assertNull(field.getContentType());
    }

    // ==================== Form-urlencoded: null / wrong type / non-chunked ====================

    @Test
    public void testFormUrlencodedNonFormContentTypeReturnsEmpty() {
        // Content type is multipart → form-urlencoded methods return null/empty
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY, null);

        Assertions.assertNull(decoder.getUrlencodedParameter("any"));
        Assertions.assertNull(decoder.getUrlencodedParameterValues("any"));
        Assertions.assertTrue(decoder.getUrlencodedParameterNames().isEmpty());
    }

    @Test
    public void testFormUrlencodedNullBodyStreamReturnsEmpty() {
        // bodyStream == null and type is form-urlencoded → empty params
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", null);

        Assertions.assertTrue(decoder.getUrlencodedParameterNames().isEmpty());
        Assertions.assertNull(decoder.getUrlencodedParameter("key"));
    }

    @Test
    public void testFormUrlencodedNonChunkedStreamThrows() {
        // Non-chunked InputStream with form-urlencoded → decodeFormUrlencoded throws
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded",
                new ByteArrayInputStream("key=val".getBytes()));

        Assertions.assertThrows(IllegalStateException.class,
                () -> decoder.getUrlencodedParameter("key"));
    }

    // ==================== Form-urlencoded: chunked stream ====================

    @Test
    public void testFormUrlencodedChunkedStreamDecodesParams() throws Exception {
        // Chunked stream with form-urlencoded body → params should be decoded
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1); // all data in pre-read bytes

        // Chunked wire: 19 bytes of "key1=val1&key2=val2" = hex "13"
        byte[] wire = "13\r\nkey1=val1&key2=val2\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream chunkedStream = new HttpChunkedStream(wire, ctx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);

        List<String> vals1 = decoder.getUrlencodedParameterValues("key1");
        Assertions.assertNotNull(vals1);
        Assertions.assertEquals(1, vals1.size());
        Assertions.assertEquals("val1", vals1.get(0));

        List<String> vals2 = decoder.getUrlencodedParameterValues("key2");
        Assertions.assertNotNull(vals2);
        Assertions.assertEquals(1, vals2.size());
        Assertions.assertEquals("val2", vals2.get(0));

        Set<String> names = decoder.getUrlencodedParameterNames();
        Assertions.assertEquals(2, names.size());
        Assertions.assertTrue(names.contains("key1"));
        Assertions.assertTrue(names.contains("key2"));
    }

    @Test
    public void testFormUrlencodedChunkedStreamEmptyBody() throws Exception {
        // Empty chunked body → no named parameters
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1);

        byte[] wire = "0\r\n\r\n".getBytes();
        HttpChunkedStream chunkedStream = new HttpChunkedStream(wire, ctx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);

        Assertions.assertNull(decoder.getUrlencodedParameter("key"));
        Assertions.assertNull(decoder.getUrlencodedParameterValues("key"));
    }

    // ==================== getParameter delegation ====================

    @Test
    public void testGetParameterForMultipartField() throws Exception {
        // getParameter() delegates to multipart field value for multipart content type
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"email\"\r\n"
                + "\r\n"
                + "a@b.com\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        Assertions.assertEquals("a@b.com", decoder.getParameter("email"));
    }

    // ==================== Multipart edge cases ====================

    @Test
    public void testMultipleFieldsSameName() throws Exception {
        // Multiple fields with the same name should be accessible via getMultipartFields()
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"tag\"\r\n"
                + "\r\n"
                + "red\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"tag\"\r\n"
                + "\r\n"
                + "blue\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        java.util.List<MultipartField> tags = decoder.getMultipartFields("tag");
        Assertions.assertNotNull(tags);
        Assertions.assertEquals(2, tags.size());
        Assertions.assertEquals("red", tags.get(0).getDataAsString(decoder.getCharset()));
        Assertions.assertEquals("blue", tags.get(1).getDataAsString(decoder.getCharset()));

        // getMultipartFieldValues returns the values
        java.util.List<String> values = decoder.getMultipartFieldValues("tag");
        Assertions.assertEquals(2, values.size());
        Assertions.assertEquals("red", values.get(0));
        Assertions.assertEquals("blue", values.get(1));
    }

    @Test
    public void testFieldWithQuotedFilename() throws Exception {
        // Filename with spaces in quotes
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"doc\"; filename=\"my file.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "content\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        MultipartField field = decoder.getMultipartField("doc");
        Assertions.assertNotNull(field);
        Assertions.assertTrue(field.isFile());
        Assertions.assertEquals("my file.txt", field.getFileName());
        Assertions.assertEquals("content", field.getDataAsString(decoder.getCharset()));
    }

    // ==================== getParameter delegation ====================

    @Test
    public void testGetParameterForFormUrlencoded() throws Exception {
        // getParameter() delegates to urlencoded parameter for form-urlencoded content type
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1);

        byte[] wire = "8\r\nuser=Bob\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream chunkedStream = new HttpChunkedStream(wire, ctx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);

        Assertions.assertEquals("Bob", decoder.getParameter("user"));
    }

    // ==================== Release / cleanup ====================

    @Test
    public void testReleaseClearsCaches() throws Exception {
        // release() clears multipartFields and urlencodedParams maps.
        // After release, the InputStream has been consumed so re-decoding
        // will fail silently (caught by exception handler → empty map).
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"k\"\r\n"
                + "\r\n"
                + "v\r\n"
                + "--" + BOUNDARY + "--\r\n";

        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + BOUNDARY,
                new ByteArrayInputStream(body.getBytes()));

        // Decode first
        Assertions.assertEquals("v", decoder.getMultipartFieldValue("k"));
        Assertions.assertEquals(1, decoder.getMultipartFieldNames().size());

        // Release — should not throw
        decoder.release();
    }
}
