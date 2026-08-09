package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * Coverage tests for HttpBodyDefaultDecoder and HttpBodyStreamDecoder.
 */
public class HttpBodyDecoderCoverageTest {

    // ==================== HttpBodyDefaultDecoder ====================

    @Test
    public void testBodyDefaultDecoderMultipartSimple() {
        // Actual multipart data
        String boundary = "--boundary123";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"field1\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "value1\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=boundary123", body);
        assertEquals("value1", decoder.getParameter("field1"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartMultiple() {
        String boundary = "--testbound";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"a\"\r\n" +
                "\r\n" +
                "val_a\r\n" +
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"b\"\r\n" +
                "\r\n" +
                "val_b\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=testbound", body);
        assertEquals("val_a", decoder.getParameter("a"));
        assertEquals("val_b", decoder.getParameter("b"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartNoBoundaryPrefix() {
        // Body that doesn't start with boundary - should return empty
        byte[] body = "not a multipart body".getBytes();
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=boundary123", body);
        assertNull(decoder.getParameter("field1"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartNoFieldName() {
        String boundary = "--boundary";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "content\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        // No Content-Disposition means no field name
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=boundary", body);
        assertNull(decoder.getParameter("field1"));
    }

    @Test
    public void testBodyDefaultDecoderMultipartWithFilename() {
        String boundary = "--bound";
        byte[] body = (
                boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "file content here\r\n" +
                boundary + "--\r\n"
        ).getBytes();

        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound", body);
        // With filename, getMultipartFields returns non-null list
        java.util.List<MultipartField> fields = decoder.getMultipartFields("file");
        assertNotNull(fields, "Should have file field");
        assertFalse(fields.isEmpty(), "File field list should not be empty");
    }

    @Test
    public void testBodyDefaultDecoderFormUrlencodedComplex() {
        String body = "name=hello+world&tags=a%2Cb&empty=&num=42";
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "application/x-www-form-urlencoded", body.getBytes());
        assertEquals("hello world", decoder.getParameter("name"));
        assertNotNull(decoder.getParameter("empty"));
        assertEquals("42", decoder.getParameter("num"));
    }

    // ==================== HttpBodyStreamDecoder ====================

    @Test
    public void testBodyStreamDecoderBoundaryCheckFail() throws Exception {
        // Data too short (limit < boundary.length)
        InputStream in = new ByteArrayInputStream("short".getBytes());
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=----BoundaryLong", in);
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderEndMarker() throws Exception {
        // Properly formatted multipart with end marker --boundary--
        String boundary = "----BOUND";
        byte[] body = (
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"test\"\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--" + boundary + "--\r\n"
        ).getBytes();

        InputStream in = new ByteArrayInputStream(body);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        assertEquals("hello", decoder.getParameter("test"));
    }

    @Test
    public void testBodyStreamDecoderFormUrlencodedNonChunked() throws Exception {
        // Non-chunked stream (content-length > MAX_BODY_IN_MEMORY) throws
        InputStream in = new ByteArrayInputStream("key=val".getBytes());
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", in);
        assertThrows(IllegalStateException.class, decoder::decodeFormUrlencoded);
    }

    @Test
    public void testBodyStreamDecoderNullStream() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=test", (InputStream) null);
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderNotMultipart() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/json", new ByteArrayInputStream("{}".getBytes()));
        decoder.doDecodeMultipartFields("--boundary".getBytes());
    }

    // ==================== HttpBodyStreamDecoder: form-urlencoded with chunked stream ====================

    @Test
    public void testBodyStreamDecoderFormUrlencodedChunked() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.isChannelClosed()).thenReturn(false);
        when(mockCtx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1);
        byte[] wire = "7\r\nkey=val\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream chunkedStream = new HttpChunkedStream(wire, mockCtx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);
        assertEquals("val", decoder.getParameter("key"));
    }

    // ==================== HttpBodyStreamDecoder: readFieldToFile skipContent paths ====================

    @Test
    public void testBodyStreamDecoderReadFieldToFileSkipContentNullField() throws Exception {
        // Create data where boundary is found in buffer (first boundary triggers readFieldToFile)
        // But with a field that has no Content-Disposition name → fieldName is null → skipContent=true
        // We use a data pattern where the boundary delimiter is NOT found within the buffer range
        // To trigger readFieldToFile, need boundary not in buffer: use very short initial data and boundary at programmatic pos
        String boundary = "----BOUND";
        // Make data where the first boundary + headers consume most of the stream
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"\"\r\n"); // empty name
        sb.append("Content-Type: text/plain\r\n\r\n");
        sb.append("datadata\r\n");
        sb.append("--").append(boundary).append("--\r\n");
        byte[] body = sb.toString().getBytes();
        InputStream in = new ByteArrayInputStream(body);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        // Without a field name, the field won't show up in params
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderNoFieldName() throws Exception {
        // Multipart without Content-Disposition name
        String boundary = "----BOUND";
        byte[] body = (
                "--" + boundary + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "data\r\n" +
                "--" + boundary + "--\r\n"
        ).getBytes();

        InputStream in = new ByteArrayInputStream(body);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        assertNull(decoder.getParameter("x"));
    }

    @Test
    public void testBodyStreamDecoderHeaderNotFound() throws Exception {
        // headers without CRLFCRLF within buffer
        String boundary = "----BOUNDARY";
        // Make header data that fills buffer without CRLFCRLF
        byte[] headerPart = new byte[4096];
        Arrays.fill(headerPart, (byte) 'A');
        byte[] bodyBytes = ("--" + boundary + "\r\n" + new String(headerPart)).getBytes();

        InputStream in = new ByteArrayInputStream(bodyBytes);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=" + boundary, in);
        assertNull(decoder.getParameter("x"));
    }

    // ==================== HttpBodyDecoder base class ====================

    @Test
    public void testBodyDecoderContentTypeParsing() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound123", "x".getBytes());
        assertTrue(decoder.isMultipart());
        assertFalse(decoder.isFormUrlencoded());

        HttpBodyDefaultDecoder formDecoder = new HttpBodyDefaultDecoder(
                "application/x-www-form-urlencoded", "a=1".getBytes());
        assertTrue(formDecoder.isFormUrlencoded());
        assertFalse(formDecoder.isMultipart());
    }

    // ==================== HttpBodyDecoder.of() ====================

    @Test
    public void testOfWithStreamRequest() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getContentType()).thenReturn("application/json");
        when(mockReq.isStream()).thenReturn(true);
        when(mockReq.bodyStream()).thenReturn(new ByteArrayInputStream("{}".getBytes()));
        HttpBodyDecoder decoder = HttpBodyDecoder.of(mockReq);
        assertNotNull(decoder);
        assertTrue(decoder instanceof HttpBodyStreamDecoder);
    }

    // ==================== extractContentTypeParam: quoted/unclosed ====================

    @Test
    public void testQuotedCharset() {
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "text/plain; charset=\"UTF-8\"", "data".getBytes());
        assertEquals("UTF-8", dec.getCharset().name());
    }

    @Test
    public void testUnclosedQuote() {
        // charset="UTF-8 (no closing quote) → end = content type length
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "text/plain; charset=\"UTF-8", "data".getBytes());
        assertEquals("UTF-8", dec.getCharset().name());
    }

    @Test
    public void testSpaceDelimitedBoundary() {
        // Unquoted param, space delimiter
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=abc def", "x".getBytes());
        assertNull(dec.getParameter("f"));
    }

    // ==================== decodeMultipartFields: non-multipart (L177) ====================

    @Test
    public void testDecodeMultipartFieldsNotMultipart() {
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "application/json", "{}".getBytes());
        dec.decodeMultipartFields(); // should set empty map, no exception
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== catch exception in decodeMultipartFields (L183-186) ====================

    @Test
    public void testDecodeMultipartFieldsExceptionCaught() {
        // Stream that throws IOException → caught by catch block
        InputStream faulty = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("simulated");
            }
            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                throw new java.io.IOException("simulated");
            }
        };
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=bound", faulty);
        dec.decodeMultipartFields();
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== getMultipartFieldValues: file field (L274) ====================

    @Test
    public void testGetMultipartFieldValuesWithFileField() {
        String boundary = "--bound";
        byte[] body = (boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"f\"; filename=\"x.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "data\r\n"
                + boundary + "--\r\n").getBytes();
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound", body);
        // File field is ignored in getMultipartFieldValues → returns empty list
        assertTrue(dec.getMultipartFieldValues("f").isEmpty());
    }

    // ==================== getParameterValues: multipath path (L307) ====================

    @Test
    public void testGetParameterValuesMultipart() {
        String boundary = "--bound";
        byte[] body = (boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n"
                + "\r\n"
                + "y\r\n"
                + boundary + "--\r\n").getBytes();
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=bound", body);
        assertEquals("y", dec.getParameterValues("x").get(0));
    }

    // ==================== extractBoundary: empty (L403) ====================

    @Test
    public void testExtractBoundaryEmpty() {
        HttpBodyDefaultDecoder dec = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=", "data".getBytes());
        assertNull(dec.getParameter("f"));
    }

    // ==================== startsWithBoundary: data too short (L467) ====================

    @Test
    public void testStartsWithBoundaryDataTooShort() {
        byte[] boundary = "--test".getBytes();
        byte[] data = "x".getBytes();
        assertFalse(HttpBodyDecoder.startsWithBoundary(data, boundary));
    }

    // ==================== parseMultipartHeaders: \n-only line ending (L536/537) ====================

    @Test
    public void testParseMultipartHeadersLfOnly() {
        // Headers with \n only (no \r) before Content-Type
        String data = "Content-Type: text/plain\n\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(data.getBytes(), 0, data.length());
        assertNotNull(headers);
        assertEquals("text/plain", headers[2].toString());
    }

    // ==================== extractHeaderParamValue: empty after trim (L552/553) ====================

    @Test
    public void testExtractHeaderParamValueEmpty() {
        // name=  ; → value is spaces, trimmed to empty trigger L552/553
        String fullData = "Content-Disposition: form-data; name=  ;\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
    }

    // ==================== extractHeaderParamValue: escaped quote in quoted value ====================

    @Test
    public void testExtractHeaderParamValueEscapedQuote() {
        // name="file\"name" → value contains escaped quote, covers escape handling
        String fullData = "Content-Disposition: form-data; name=\"file\\\"name\"\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
        assertEquals("file\"name", headers[0].toString());
    }

    // ==================== extractHeaderParamValue: unclosed quoted value ====================

    @Test
    public void testExtractHeaderParamValueUnclosedQuote() {
        // name="unclosed → no closing quote, covers i >= end branch
        String fullData = "Content-Disposition: form-data; name=\"unclosed\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
        assertEquals("unclosed", headers[0].toString());
    }

    // ==================== extractHeaderParamValue: unquoted with trailing spaces ====================

    @Test
    public void testExtractHeaderParamValueUnquotedTrim() {
        // name=value   ; → trailing spaces trimmed, covers line 597
        String fullData = "Content-Disposition: form-data; name=value   ;\r\n\r\n";
        HttpBuf[] headers = HttpBodyDecoder.parseMultipartHeaders(fullData.getBytes(), 0, fullData.length());
        assertNotNull(headers);
        assertEquals("value", headers[0].toString());
    }

    // ==================== findNewlineGuaranteed: no \r before \n (L616) ====================

    @Test
    public void testFindNewlineGuaranteedLfOnly() {
        byte[] data = "hello\nworld".getBytes();
        int pos = HttpBodyDecoder.findNewlineGuaranteed(data, 0);
        assertEquals(5, pos); // position of \n (since no \r)
    }
}
