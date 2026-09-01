package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.*;

import java.nio.channels.SocketChannel;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HTTP core utility classes: HttpHeaderUtils, HttpUnsafe,
 * HttpHeaderNormalized, HttpDate, HttpConf, HttpDecodedRequest, HttpBodyDefaultDecoder.
 */
public class HttpCoreUtilTest {

    // ==================== HttpHeaderUtils ====================

    @Test
    public void testHeaderUtilsConfigDefaults() {
        // Default config is LOWERCASE + COMMA_SEPARATED
        assertFalse(HttpHeaderUtils.isTitleCase());
        assertTrue(HttpHeaderUtils.isCommaSeparated());
        assertFalse(HttpHeaderUtils.isAllowDuplicates());
    }

    @Test
    public void testHeaderUtilsSetTitleCaseConfig() {
        HttpHeaderUtils.HeaderConfig titleConfig = HttpHeaderUtils.HeaderConfig.standardConfig();
        assertTrue(titleConfig.isCommaSeparated());
        assertEquals(HttpHeaderUtils.HeaderFormatStrategy.TITLE_CASE, titleConfig.formatStrategy);

        HttpHeaderUtils.setHeaderConfig(titleConfig);
        assertTrue(HttpHeaderUtils.isTitleCase());

        // Test toStandardFormat with title case
        String result = HttpHeaderUtils.toStandardFormat("content-type");
        assertEquals("Content-Type", result);

        // Non-standard key gets title-cased
        String custom = HttpHeaderUtils.toStandardFormat("x-custom-header");
        assertEquals("X-Custom-Header", custom);

        // normalizeHeaderKey returns title case
        assertEquals("Content-Type", HttpHeaderUtils.normalizeHeaderKey("content-type"));

        // Test normalizedKeyBuffer (byte version)
        byte[] buf = "content-type".getBytes();
        HttpHeaderUtils.normalizedKeyBuffer(buf, 0, buf.length);
        assertEquals("Content-Type", new String(buf));

        // Reset back to default
        HttpHeaderUtils.resetHeaderConfig();
        assertFalse(HttpHeaderUtils.isTitleCase());
        assertTrue(HttpHeaderUtils.isCommaSeparated());
    }

    @Test
    public void testHeaderUtilsAllowDuplicatesConfig() {
        HttpHeaderUtils.HeaderConfig dupConfig = HttpHeaderUtils.HeaderConfig.allowDuplicatesConfig();
        assertTrue(dupConfig.isAllowDuplicates());

        HttpHeaderUtils.setHeaderConfig(dupConfig);
        assertTrue(HttpHeaderUtils.isAllowDuplicates());

        HttpHeaderUtils.resetHeaderConfig();
    }

    @Test
    public void testHeaderUtilsSetNullConfigThrows() {
        assertThrows(IllegalArgumentException.class, () -> HttpHeaderUtils.setHeaderConfig(null));
    }

    @Test
    public void testToStandardFormatNullOrEmpty() {
        assertNull(HttpHeaderUtils.toStandardFormat(null));
        assertEquals("", HttpHeaderUtils.toStandardFormat(""));
    }

    @Test
    public void testToTitleCase() {
        assertNull(HttpHeaderUtils.toTitleCase(null));
        assertEquals("", HttpHeaderUtils.toTitleCase(""));
        assertEquals("Content-Type", HttpHeaderUtils.toTitleCase("content-type"));
        assertEquals("Content-Type", HttpHeaderUtils.toTitleCase("content_type"));
        assertEquals("X-Custom", HttpHeaderUtils.toTitleCase("X-CUSTOM"));
        assertEquals("Foo-Bar", HttpHeaderUtils.toTitleCase("FOO-BAR"));
    }

    @Test
    public void testToTitleCaseBytes() {
        byte[] buf = "content-type".getBytes();
        HttpHeaderUtils.toTitleCase(buf, 0, buf.length);
        assertEquals("Content-Type", new String(buf));

        // Already uppercase
        byte[] buf2 = "Content-Type".getBytes();
        HttpHeaderUtils.toTitleCase(buf2, 0, buf2.length);
        assertEquals("Content-Type", new String(buf2));
    }

    @Test
    public void testToLowerCase() {
        byte[] buf = "Content-Type".getBytes();
        HttpHeaderUtils.toLowerCase(buf, 0, buf.length);
        assertEquals("content-type", new String(buf));

        // Already lowercase
        byte[] buf2 = "content-type".getBytes();
        HttpHeaderUtils.toLowerCase(buf2, 0, buf2.length);
        assertEquals("content-type", new String(buf2));
    }

    @Test
    public void testNormalizeHeaderKeyLowerCase() {
        // With default LOWERCASE config
        assertEquals("content-type", HttpHeaderUtils.normalizeHeaderKey("Content-Type"));
    }

    @Test
    public void testGetMimeType() {
        assertNull(HttpHeaderUtils.getMimeType(null));
        assertNull(HttpHeaderUtils.getMimeType(""));
        assertEquals("text/html", HttpHeaderUtils.getMimeType("html"));
        assertEquals("application/json", HttpHeaderUtils.getMimeType("JSON"));
        assertEquals("image/png", HttpHeaderUtils.getMimeType("png"));
        assertNull(HttpHeaderUtils.getMimeType("unknown_extension"));
    }

    @Test
    public void testGetMimeTypeByFilename() {
        assertNull(HttpHeaderUtils.getMimeTypeByFilename(null));
        assertNull(HttpHeaderUtils.getMimeTypeByFilename(""));
        assertNull(HttpHeaderUtils.getMimeTypeByFilename("noextension"));
        assertEquals("text/html", HttpHeaderUtils.getMimeTypeByFilename("index.html"));
        assertEquals("image/png", HttpHeaderUtils.getMimeTypeByFilename("dir/file.PNG"));
    }

    @Test
    public void testGetDateHeaderBytes() {
        byte[] dateBytes = HttpHeaderUtils.getDateHeaderBytes(System.currentTimeMillis());
        assertEquals(29, dateBytes.length);
    }

    @Test
    public void testGetDateHeaderValue() {
        String dateStr = HttpHeaderUtils.getDateHeaderValue(System.currentTimeMillis());
        assertNotNull(dateStr);
        assertEquals(29, dateStr.length());
    }

    // ==================== HttpUnsafe ====================

    @Test
    public void testCreateAsciiString() {
        byte[] data = "hello".getBytes();
        String s = HttpUnsafe.createAsciiString(data, 0, data.length);
        assertEquals("hello", s);
    }

    @Test
    public void testCreateAsciiStringFull() {
        byte[] data = "world".getBytes();
        String s = HttpUnsafe.createAsciiString(data);
        assertEquals("world", s);
    }

    @Test
    public void testCreateAsciiStringEmpty() {
        assertEquals("", HttpUnsafe.createAsciiString(new byte[0]));
    }

    @Test
    public void testCreateAsciiStringBounds() {
        assertThrows(IndexOutOfBoundsException.class,
                () -> HttpUnsafe.createAsciiString(new byte[5], 0, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> HttpUnsafe.createAsciiString(new byte[5], -1, 3));
    }

    @Test
    public void testCopyOfRangeSmallSizes() {
        // Test all copyOfRange branches for len < 32
        byte[] src = "ABCDEFGHIJKLMNOPQRSTUVWXYZ01234".getBytes();

        // len > 15 (16+)
        byte[] tgt1 = new byte[20];
        HttpUnsafe.copyOfRange(src, 0, tgt1, 0, 20);
        assertEquals("ABCDEFGHIJKLMNOPQRST", new String(tgt1, 0, 20));

        // len 10 (>8, <=15, <=8 after L110)
        byte[] tgt2 = new byte[10];
        HttpUnsafe.copyOfRange(src, 0, tgt2, 0, 10);
        assertEquals("ABCDEFGHIJ", new String(tgt2, 0, 10));

        // len 6 (>4, >2, remaining)
        byte[] tgt3 = new byte[6];
        HttpUnsafe.copyOfRange(src, 0, tgt3, 0, 6);
        assertEquals("ABCDEF", new String(tgt3, 0, 6));

        // len 3 (>2, remaining byte)
        byte[] tgt4 = new byte[3];
        HttpUnsafe.copyOfRange(src, 0, tgt4, 0, 3);
        assertEquals("ABC", new String(tgt4, 0, 3));

        // len 1 (just remaining byte)
        byte[] tgt5 = new byte[1];
        HttpUnsafe.copyOfRange(src, 0, tgt5, 0, 1);
        assertEquals("A", new String(tgt5, 0, 1));

        // len 2 (exactly short path)
        byte[] tgt6 = new byte[2];
        HttpUnsafe.copyOfRange(src, 5, tgt6, 0, 2);
        assertEquals("FG", new String(tgt6, 0, 2));

        // len >= 32 should use System.arraycopy
        byte[] longSrc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".getBytes();
        byte[] tgt7 = new byte[32];
        HttpUnsafe.copyOfRange(longSrc, 0, tgt7, 0, 32);
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", new String(tgt7, 0, 32));
    }

    @Test
    public void testGetLongShortInt() {
        byte[] data = "0123456789".getBytes();
        assertNotEquals(0, HttpUnsafe.getLong(data, 0));
        assertNotEquals(0, HttpUnsafe.getShort(data, 0));
        assertNotEquals(0, HttpUnsafe.getInt(data, 0));
    }


    @Test
    public void testWriteTwoDigitChar() {
        byte[] buf = new byte[2];
        HttpUnsafe.writeTwoDigitChar(buf, 0, 42);
        assertEquals("42", new String(buf));
    }

    @Test
    public void testMaskOfWhitespace() {
        byte[] buf = "hello   ".getBytes();
        long mask = HttpUnsafe.maskOfWhitespace(buf, 0);
        assertNotEquals(0, mask);
    }

    @Test
    public void testMaskOfColon() {
        byte[] buf = "header:".getBytes();
        long value = HttpUnsafe.getLong(buf, 0);
        assertNotEquals(0, HttpUnsafe.maskOfColon(value));
        // No colon
        byte[] noColon = "abcdefgh".getBytes();
        assertEquals(0, HttpUnsafe.maskOfColon(HttpUnsafe.getLong(noColon, 0)));
    }

    @Test
    public void testMaskOfNewline() {
        byte[] buf = "abc\ndefg".getBytes();
        long value = HttpUnsafe.getLong(buf, 0);
        assertNotEquals(0, HttpUnsafe.maskOfNewline(value));
    }

    @Test
    public void testMaskOfCR() {
        byte[] buf = "abc\rdefg".getBytes();
        long value = HttpUnsafe.getLong(buf, 0);
        assertNotEquals(0, HttpUnsafe.maskOfCR(value));
    }

    @Test
    public void testOffsetTokenBytes() {
        long mask = 0x8080808000000000L;
        int offset = HttpUnsafe.offsetTokenBytes(mask);
        assertTrue(offset >= 0);
    }

    @Test
    public void testFindCRLFCRLF() {
        byte[] data = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        int pos = HttpUnsafe.findCRLFCRLF(data, 0, data.length);
        assertTrue(pos > 0);

        // Not found
        byte[] noCrlf = "no crlf here at all".getBytes();
        assertEquals(-1, HttpUnsafe.findCRLFCRLF(noCrlf, 0, noCrlf.length));

        // Too short (< 4 bytes)
        byte[] shortData = new byte[3];
        assertEquals(-1, HttpUnsafe.findCRLFCRLF(shortData, 0, 3));

        // Has \r but not \r\n\r\n
        byte[] partial = "foo\rbar\r\n\r\nbaz".getBytes();
        int found = HttpUnsafe.findCRLFCRLF(partial, 4, partial.length);
        assertTrue(found >= 0);
    }

    // ==================== HttpHeaderNormalized ====================

    @Test
    public void testHeaderNormalizedDefaults() {
        assertEquals("content-type", HttpHeaderNormalized.getContentType());
        assertEquals("content-length", HttpHeaderNormalized.getContentLength());
        assertEquals("content-", HttpHeaderNormalized.getContentPrefix());
    }

    @Test
    public void testHeaderNormalizedTitleCase() {
        // Switch to title case temporarily
        HttpHeaderUtils.setHeaderConfig(HttpHeaderUtils.HeaderConfig.standardConfig());
        assertTrue(HttpHeaderUtils.isTitleCase());

        assertEquals("Content-Type", HttpHeaderNormalized.getContentType());
        assertEquals("Content-Length", HttpHeaderNormalized.getContentLength());
        assertEquals("Content-", HttpHeaderNormalized.getContentPrefix());
        assertEquals("Content-Encoding", HttpHeaderNormalized.getContentEncoding());
        assertEquals("Content-Disposition", HttpHeaderNormalized.getContentDisposition());
        assertEquals("Accept-Encoding", HttpHeaderNormalized.getAcceptEncoding());
        assertEquals("Transfer-Encoding", HttpHeaderNormalized.getTransferEncoding());
        assertEquals("Allow", HttpHeaderNormalized.getAllow());
        assertEquals("Upgrade", HttpHeaderNormalized.getUpgrade());
        assertEquals("Connection", HttpHeaderNormalized.getConnection());
        assertEquals("Host", HttpHeaderNormalized.getHost());
        assertEquals("Date", HttpHeaderNormalized.getDate());
        assertEquals("Server", HttpHeaderNormalized.getServer());
        assertEquals("Expect", HttpHeaderNormalized.getExpect());
        assertEquals("Last-Modified", HttpHeaderNormalized.getLastModified());
        assertEquals("ETag", HttpHeaderNormalized.getETag());
        assertEquals("If-None-Match", HttpHeaderNormalized.getIfNoneMatch());
        assertEquals("If-Modified-Since", HttpHeaderNormalized.getIfModifiedSince());
        assertEquals("Sec-WebSocket-Key", HttpHeaderNormalized.getSecWebSocketKey());
        assertEquals("Sec-WebSocket-Protocol", HttpHeaderNormalized.getSecWebSocketProtocol());
        assertEquals("Cache-Control", HttpHeaderNormalized.getCacheControl());

        // Reset back
        HttpHeaderUtils.resetHeaderConfig();
        assertFalse(HttpHeaderUtils.isTitleCase());
    }

    @Test
    public void testHeaderNormalizedLowercase() {
        assertFalse(HttpHeaderUtils.isTitleCase());

        assertEquals("content-encoding", HttpHeaderNormalized.getContentEncoding());
        assertEquals("content-disposition", HttpHeaderNormalized.getContentDisposition());
        assertEquals("accept-encoding", HttpHeaderNormalized.getAcceptEncoding());
        assertEquals("transfer-encoding", HttpHeaderNormalized.getTransferEncoding());
        assertEquals("allow", HttpHeaderNormalized.getAllow());
        assertEquals("upgrade", HttpHeaderNormalized.getUpgrade());
        assertEquals("connection", HttpHeaderNormalized.getConnection());
        assertEquals("host", HttpHeaderNormalized.getHost());
        assertEquals("date", HttpHeaderNormalized.getDate());
        assertEquals("server", HttpHeaderNormalized.getServer());
        assertEquals("expect", HttpHeaderNormalized.getExpect());
        assertEquals("last-modified", HttpHeaderNormalized.getLastModified());
        assertEquals("etag", HttpHeaderNormalized.getETag());
        assertEquals("if-none-match", HttpHeaderNormalized.getIfNoneMatch());
        assertEquals("if-modified-since", HttpHeaderNormalized.getIfModifiedSince());
        assertEquals("sec-websocket-key", HttpHeaderNormalized.getSecWebSocketKey());
        assertEquals("sec-websocket-protocol", HttpHeaderNormalized.getSecWebSocketProtocol());
        assertEquals("cache-control", HttpHeaderNormalized.getCacheControl());
    }

    // ==================== HttpDate ====================

    @Test
    public void testHttpDateConstructor() {
        HttpDate date = new HttpDate(0L, java.util.TimeZone.getTimeZone("GMT"));
        assertTrue(date.getYear() >= 1970);
        assertTrue(date.getMonth() >= 1 && date.getMonth() <= 12);
        assertTrue(date.getDay() >= 1 && date.getDay() <= 31);
        assertTrue(date.getDayOfWeek() >= 1 && date.getDayOfWeek() <= 7);
    }

    @Test
    public void testHttpDateLeapYear() {
        // Year 2000 is a leap year (divisible by 400)
        HttpDate leapDate = new HttpDate(951825600000L, java.util.TimeZone.getTimeZone("GMT")); // 2000-02-29
        assertEquals(2000, leapDate.getYear());
        assertEquals(2, leapDate.getMonth());
        assertEquals(29, leapDate.getDay());

        // Year 1900 is NOT a leap year (divisible by 100 but not 400)
        HttpDate nonLeapDate = new HttpDate(-2203977600000L, java.util.TimeZone.getTimeZone("GMT")); // 1900-03-01
        assertEquals(1900, nonLeapDate.getYear());
    }

    @Test
    public void testHttpDateGetDateHeaderBytes() {
        byte[] bytes = HttpDate.getDateHeaderBytes(System.currentTimeMillis());
        assertEquals(29, bytes.length);
        assertEquals(',', bytes[3]); // "EEE," format
    }

    @Test
    public void testIsSameDay() {
        long now = System.currentTimeMillis();
        assertTrue(HttpDate.isSameDay(now, now));
        assertTrue(HttpDate.isSameDay(now, now + 60000)); // 1 minute later, always same GMT day
    }

    @Test
    public void testGetCurrentDateHeaderLineBytes() {
        byte[] line = HttpDate.getCurrentDateHeaderLineBytes();
        assertEquals(37, line.length);
    }

    @Test
    public void testUpdateDateHeaderCase() {
        HttpDate.updateDateHeaderCase();
        byte[] line = HttpDate.getCurrentDateHeaderLineBytes();
        // Default is lowercase 'd'
        assertEquals('d', line[0]);
    }

    @Test
    public void testGetDayOfWeekAtEpoch() {
        HttpDate epoch = new HttpDate(0L, java.util.TimeZone.getTimeZone("GMT"));
        assertEquals(5, epoch.getDayOfWeek()); // 1970-01-01 was Thursday (5)
    }

    // ==================== HttpConf ====================

    @Test
    public void testGetProperty() {
        // Should not throw
        String val = HttpConf.getProperty("wastnet.http.max-single-header-size");
        // May be null in test env, but shouldn't throw
    }

    @Test
    public void testDumpAsProperties() {
        String props = HttpConf.dumpAsProperties();
        assertNotNull(props);
        assertTrue(props.contains("wastnet.http.gzip="));
    }

    // ==================== HttpDecodedRequest ====================

    @Test
    public void testHttpDecodedRequestNoArgConstructor() {
        // Anonymous subclass using no-arg constructor
        HttpDecodedRequest req = new HttpDecodedRequest() {
        };
        assertNull(req.getUri());
        assertNull(req.getMethod());
        assertNull(req.getHttpVersion());
        assertNull(req.getQueryString());
        assertNull(req.getRequestURL());
    }

    @Test
    public void testHttpDecodedRequestSimpleConstructor() throws Exception {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("host", "localhost");

        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 4096);
        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, ctx) {
        };

        assertEquals(HttpVersion.HTTP_1_1, req.getHttpVersion());
        assertTrue(req.containsHeader("host"));
        assertNull(req.getMethod());
        assertEquals("http", req.getScheme());
        assertFalse(req.isSSL());
    }

    @Test
    public void testHttpDecodedRequestGetHeader() {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("content-type", "text/html");

        // Case-insensitive getHeader via getRawHeader path
        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, null) {
        };
        assertEquals("text/html", req.getHeader("Content-Type"));
    }

    @Test
    public void testHttpDecodedRequestMultiValueGetHeader() {
        Map<String, Object> headers = new HashMap<String, Object>();
        List<String> values = new ArrayList<String>();
        values.add("val1");
        values.add("val2");
        headers.put("x-multi", values);

        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, null) {
        };
        assertEquals("val1", req.getHeader("x-multi"));
        assertEquals("val1", req.getHeader("X-Multi"));
    }

    @Test
    public void testHttpDecodedRequestSetHeader() {
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, null) {
        };
        req.setHeader("X-Custom", "value");
        assertTrue(req.containsHeader("x-custom"));
    }

    @Test
    public void testHttpDecodedRequestRemoveHeader() {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("x-temp", "temp");
        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, null) {
        };
        req.removeHeader("X-Temp");
        assertFalse(req.containsHeader("x-temp"));
    }

    @Test
    public void testHttpDecodedRequestGetRawHeaderNull() {
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, null) {
        };
        assertNull(req.getRawHeader(null));
        req.setHeader("X-Custom", "val");
        assertEquals("val", req.getRawHeader("x-custom"));
    }

    @Test
    public void testHttpDecodedRequestSetRewriteUriWithQuery() {
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("q", Arrays.asList("test"));

        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/search?q=test".getBytes(), "/search", params,
                HttpVersion.HTTP_1_1, headers, null, 0, null, null) {
        };
        req.setRewriteUri("/new-search");
        String uri = req.getUri();
        assertTrue(uri.contains("?"));
        assertTrue(uri.contains("q=test"));
    }

    @Test
    public void testHttpDecodedRequestSetRewriteUriNoQuery() {
        Map<String, Object> headers = new HashMap<String, Object>();
        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/index".getBytes(), "/index", null,
                HttpVersion.HTTP_1_1, headers, null, 0, null, null) {
        };
        req.setRewriteUri("/home");
        assertEquals("/home", req.getUri());
    }

    @Test
    public void testHttpDecodedRequestSetRewriteWithQueryEnding() {
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("q", Arrays.asList("test"));

        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/search?q=test".getBytes(), "/search", params,
                HttpVersion.HTTP_1_1, headers, null, 0, null, null) {
        };
        req.setRewriteUri("/search?");
        String uri = req.getUri();
        assertTrue(uri.endsWith("q=test"));
    }

    @Test
    public void testHttpDecodedRequestGetQueryString() {
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("key", Arrays.asList("val"));

        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/path?key=val".getBytes(), "/path", params,
                HttpVersion.HTTP_1_1, headers, null, 0, null, null) {
        };
        assertEquals("key=val", req.getQueryString());
        // Cached
        assertEquals("key=val", req.getQueryString());
    }

    @Test
    public void testHttpDecodedRequestGetUriParameter() {
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("name", Arrays.asList("john"));

        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/path?name=john".getBytes(), "/path", params,
                HttpVersion.HTTP_1_1, headers, null, 0, null, null) {
        };
        assertEquals("john", req.getUriParameter("name"));
        assertNull(req.getUriParameter("nonexistent"));
    }

    @Test
    public void testHttpDecodedRequestGetUriParameterNames() {
        Map<String, Object> headers = new HashMap<String, Object>();
        Map<String, List<String>> params = new HashMap<String, List<String>>();

        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/path".getBytes(), "/path", params,
                HttpVersion.HTTP_1_1, headers, null, 0, null, null) {
        };
        assertTrue(req.getUriParameterNames().isEmpty());
    }

    @Test
    public void testHttpDecodedRequestGetHeaderNames() {
        Map<String, Object> headers = new HashMap<String, Object>();
        headers.put("host", "localhost");
        headers.put("accept", "*/*");

        HttpDecodedRequest req = new HttpDecodedRequest(HttpVersion.HTTP_1_1, headers, null) {
        };
        Set<String> names = req.getHeaderNames();
        assertEquals(2, names.size());
    }

    @Test
    public void testHttpDecodedRequestGetBodyData() {
        byte[] body = "body".getBytes();
        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.POST,
                "/path".getBytes(), "/path", null,
                HttpVersion.HTTP_1_1, new HashMap<String, Object>(), body, 4,
                "text/plain", null) {
        };
        assertArrayEquals(body, req.getBodyData());
        assertNull(req.bodyStream());
    }

    @Test
    public void testHttpDecodedRequestGetContentLength() {
        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/path".getBytes(), "/path", null,
                HttpVersion.HTTP_1_1, new HashMap<String, Object>(), null, 100,
                null, null) {
        };
        assertEquals(100, req.getContentLength());
    }

    @Test
    public void testHttpDecodedRequestGetContentType() {
        HttpDecodedRequest req = new HttpDecodedRequest(HttpMethod.GET,
                "/path".getBytes(), "/path", null,
                HttpVersion.HTTP_1_1, new HashMap<String, Object>(), null, 0,
                "application/json", null) {
        };
        assertEquals("application/json", req.getContentType());
    }

    // ==================== HttpBodyDefaultDecoder ====================

    @Test
    public void testHttpBodyDefaultDecoderFormUrlencoded() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "application/x-www-form-urlencoded", "name=test&value=123".getBytes());
        assertEquals("test", decoder.getParameter("name"));
        assertEquals("123", decoder.getParameter("value"));
    }

    @Test
    public void testHttpBodyDefaultDecoderEmptyBody() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder(
                "multipart/form-data; boundary=--boundary", new byte[0]);
        assertNull(decoder.getMultipartFields("field"));
        decoder.release();
    }

    @Test
    public void testHttpBodyDefaultDecoderNullBody() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("text/plain", null);
        assertNull(decoder.getParameter("key"));
        decoder.release();
    }

    @Test
    public void testHttpBodyDefaultDecoderFormUrlencodedEmpty() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("application/x-www-form-urlencoded", new byte[0]);
        assertNull(decoder.getParameter("key"));
    }

    @Test
    public void testHttpBodyDefaultDecoderNotFormUrlencoded() {
        HttpBodyDefaultDecoder decoder = new HttpBodyDefaultDecoder("application/json", "{}".getBytes());
        decoder.decodeFormUrlencoded();
    }

    // ==================== HttpMethod and HttpVersion enums ====================

    @Test
    public void testHttpMethod() {
        assertEquals(HttpMethod.GET, HttpMethod.valueOf("GET"));
        assertEquals(HttpMethod.POST, HttpMethod.valueOf("POST"));
    }

    @Test
    public void testHttpVersion() {
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.valueOf("HTTP_1_1"));
    }
}
