package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpGenerativeResponse} base class.
 *
 * @author wangyc
 */
public class HttpGenerativeResponseTest {

    /**
     * Returns a real {@link ChannelContext} bound to a {@link NioConfig} that mirrors the
     * live {@link HttpConf} values. Configuration overrides are applied per-test via a
     * dedicated NioConfig, so no reflective/Unsafe mutation of HttpConf static fields is needed.
     */
    private static ChannelContext mockChannelContext() {
        return mockChannelContext(new NioConfig());
    }

    private static ChannelContext mockChannelContext(NioConfig overrideConfig) {
        ChannelContext ctx;
        try {
            java.lang.reflect.Constructor<ChannelContext> ctor =
                    ChannelContext.class.getDeclaredConstructor(long.class, java.nio.channels.SocketChannel.class, int.class);
            ctor.setAccessible(true);
            ctx = ctor.newInstance(0L, null, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Per-test overrides are applied on top of each option's default value (option.value,
        // which mirrors the live HttpConf). Unset keys fall back to the default automatically.
        NioConfig nioConfig = new NioConfig();
        if (overrideConfig != null) {
            nioConfig.option(HttpOptions.EXPOSE_SERVER_HEADER, overrideConfig.option(HttpOptions.EXPOSE_SERVER_HEADER));
            nioConfig.option(HttpOptions.WRITE_DEFAULT_HEADERS, overrideConfig.option(HttpOptions.WRITE_DEFAULT_HEADERS));
            nioConfig.option(HttpOptions.GZIP, overrideConfig.option(HttpOptions.GZIP));
            nioConfig.option(HttpOptions.GZIP_MIN_SIZE, overrideConfig.option(HttpOptions.GZIP_MIN_SIZE));
            nioConfig.option(HttpOptions.BODY_MEMORY_THRESHOLD, overrideConfig.option(HttpOptions.BODY_MEMORY_THRESHOLD));
        }
        ctx.attachNioConfig(nioConfig);
        return ctx;
    }

    // ==================== Header management ====================

    @Test
    public void testPutHeader() {
        TestResponse resp = createResponse();
        resp.testPutHeader("Content-Type", "text/html");
        Assertions.assertEquals("text/html", resp.headers.get("content-type"));
    }

    @Test
    public void testDirectlyPutHeader() {
        TestResponse resp = createResponse();
        resp.testDirectlyPutHeader("Content-Type", "application/json");
        Assertions.assertEquals("application/json", resp.headers.get("Content-Type"));
    }

    // ==================== Connection header ====================

    @Test
    public void testConnectionHeaderHttp11WithNoRequestConn() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, mockChannelContext());
        Assertions.assertEquals("keep-alive", resp.testGetConnectionHeaderValue());
    }

    @Test
    public void testConnectionHeaderHttp10WithNoRequestConn() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_0);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, mockChannelContext());
        Assertions.assertEquals("close", resp.testGetConnectionHeaderValue());
    }

    @Test
    public void testConnectionHeaderMirrorsRequest() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn("Upgrade");
        TestResponse resp = new TestResponse(mockReq, mockChannelContext());
        Assertions.assertEquals("Upgrade", resp.testGetConnectionHeaderValue());
    }

    // ==================== writeHeader methods ====================

    @Test
    public void testWriteHeaderLine() {
        TestResponse resp = createResponse();
        resp.testWriteHeaderLine("Content-Type", "text/plain");
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertEquals("Content-Type: text/plain\r\n", headerStr);
    }

    @Test
    public void testWriteCommaSeparatedHeader() {
        TestResponse resp = createResponse();
        List<String> values = new ArrayList<String>();
        values.add("gzip");
        values.add("deflate");
        resp.testWriteCommaSeparatedHeader("Accept-Encoding", values);
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertEquals("Accept-Encoding: gzip, deflate\r\n", headerStr);
    }

    // ==================== Default headers ====================

    @Test
    public void testWriteDefaultHeadersIncludesDate() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, mockChannelContext());
        resp.testWriteDefaultHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        // Date header (case varies by config, check length and GMT suffix)
        Assertions.assertTrue(headerStr.length() > 30, "Should contain date header");
        Assertions.assertTrue(headerStr.contains("GMT"), "Date should end with GMT");
        // Connection header (format: "connection: keep-alive" or "Connection: keep-alive")
        Assertions.assertTrue(headerStr.contains("keep-alive"), "Should contain connection header");
        // Server header should not be exposed by default (EXPOSE_SERVER_HEADER=false)
        Assertions.assertFalse(headerStr.toLowerCase().contains("server:"), "Server header should not be exposed by default");
    }

    // ==================== directlyGetHeader ====================

    @Test
    public void testDirectlyGetHeaderExists() {
        TestResponse resp = createResponse();
        resp.testDirectlyPutHeader("content-type", "text/html");
        Assertions.assertEquals("text/html", resp.testDirectlyGetHeader("content-type"));
    }

    @Test
    public void testDirectlyGetHeaderNotExists() {
        TestResponse resp = createResponse();
        Assertions.assertNull(resp.testDirectlyGetHeader("non-existent"));
    }

    // ==================== getHeaders / getHeaderNames ====================

    @Test
    public void testGetHeadersSingleValue() {
        TestResponse resp = createResponse();
        resp.headers.put("x-single", "v1");
        List<String> values = resp.getHeaders("X-Single");
        Assertions.assertNotNull(values);
        Assertions.assertEquals(1, values.size());
        Assertions.assertEquals("v1", values.get(0));
    }

    @Test
    public void testGetHeadersMultipleValues() {
        TestResponse resp = createResponse();
        List<String> multi = new ArrayList<String>();
        multi.add("v1");
        multi.add("v2");
        resp.headers.put("x-multi", multi);
        List<String> values = resp.getHeaders("x-multi");
        Assertions.assertNotNull(values);
        Assertions.assertEquals(2, values.size());
        Assertions.assertEquals("v1", values.get(0));
        Assertions.assertEquals("v2", values.get(1));
    }

    @Test
    public void testGetHeadersNotExists() {
        TestResponse resp = createResponse();
        Assertions.assertNull(resp.getHeaders("nonexistent"));
    }

    @Test
    public void testGetHeaderNames() {
        TestResponse resp = createResponse();
        resp.headers.put("x-a", "v1");
        resp.headers.put("x-b", "v2");
        java.util.Set<String> names = resp.getHeaderNames();
        Assertions.assertNotNull(names);
        Assertions.assertTrue(names.contains("x-a"));
        Assertions.assertTrue(names.contains("x-b"));
    }

    // ==================== getHttpVersion ====================

    @Test
    public void testGetHttpVersion() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        TestResponse resp = new TestResponse(mockReq, mockChannelContext());
        Assertions.assertSame(HttpVersion.HTTP_2, resp.testGetHttpVersion());
    }

    // ==================== writeDefaultHeaders uncovered branches ====================

    @Test
    public void testWriteDefaultHeadersWhenDateAlreadyPresent() {
        TestResponse resp = createResponse();
        // Pre-set Date header so writeDefaultHeaders skips writing it
        resp.testDirectlyPutHeader(HttpHeaderNormalized.getDate(), "some-date");
        resp.testWriteDefaultHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        // Date header always contains GMT; when skipped, output should not contain GMT
        Assertions.assertFalse(headerStr.contains("GMT"), "Date header should not be rewritten when already present");
    }

    @Test
    public void testWriteDefaultHeadersWithServerExposed() throws Exception {
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.EXPOSE_SERVER_HEADER, true);
        TestResponse resp = createResponse(mockChannelContext(cfg));
        resp.testWriteDefaultHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertTrue(headerStr.toLowerCase().contains("server:"),
                "Server header should be exposed when EXPOSE_SERVER_HEADER=true");
    }

    @Test
    public void testWriteDefaultHeadersWithServerExposedAndAlreadySet() throws Exception {
        // Cover EXPOSE_SERVER_HEADER=true + server header already present → !containsKey=false branch
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.EXPOSE_SERVER_HEADER, true);
        TestResponse resp = createResponse(mockChannelContext(cfg));
        // Pre-set Server header so the condition short-circuits at containsKey
        resp.testDirectlyPutHeader(HttpHeaderNormalized.getServer(), "MyServer");
        resp.testWriteDefaultHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        // Custom Server value "MyServer" should appear without being duplicated
        int firstIdx = headerStr.toLowerCase().indexOf("server:");
        int lastIdx = headerStr.toLowerCase().lastIndexOf("server:");
        Assertions.assertEquals(firstIdx, lastIdx,
                "Server header should appear only once when already present");
    }

    @Test
    public void testWriteHeadersWithContentTypeAndEmptyCharset() {
        TestResponse resp = createResponse();
        resp.contentType = "text/html";
        resp.characterEncoding = "";
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        // characterEncoding="" → !characterEncoding.isEmpty() is false → charset not appended
        Assertions.assertFalse(headerStr.toLowerCase().contains("charset"),
                "Charset should not be appended when characterEncoding is empty");
    }

    // ==================== writeHeaders (via writeHeaders()) ====================

    @Test
    public void testWriteHeadersWithChunkedAndContentLengthSkip() {
        // chunked=true and hasExplicitContentLength=true → skip Content-Length header
        TestResponse resp = createResponse();
        resp.chunked = true;
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertFalse(headerStr.toLowerCase().contains("content-length"),
                "Content-Length should be skipped when chunked");
    }

    @Test
    public void testWriteHeadersWithListHeaderAndAllowDuplicates() {
        HttpHeaderUtils.HeaderConfig originalConfig = HttpHeaderUtils.headerConfig;
        try {
            // Switch to ALLOW_DUPLICATES to trigger writeMultipleHeaderLines
            HttpHeaderUtils.HeaderConfig config =
                    new HttpHeaderUtils.HeaderConfig(HttpHeaderUtils.MERGE_ALLOW_DUPLICATES,
                            HttpHeaderUtils.FORMAT_LOWERCASE);
            HttpHeaderUtils.setHeaderConfig(config);

            TestResponse resp = createResponse();
            List<String> values = new ArrayList<String>();
            values.add("gzip");
            values.add("deflate");
            resp.headers.put("accept-encoding", values);

            resp.writeHeaders();
            String headerStr = new String(resp.headerBuf.toBytes());
            int count = 0;
            int idx = 0;
            while ((idx = headerStr.indexOf("accept-encoding", idx)) > -1) {
                count++;
                idx++;
            }
            Assertions.assertEquals(2, count, "Should write two separate header lines with ALLOW_DUPLICATES");
        } finally {
            HttpHeaderUtils.setHeaderConfig(originalConfig);
        }
    }

    @Test
    public void testWriteHeadersWithEmptyContentType() {
        TestResponse resp = createResponse();
        // contentType="" → !contentType.isEmpty() returns false → skip writing Content-Type
        resp.contentType = "";
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertFalse(headerStr.toLowerCase().contains("content-type"),
                "Content-Type header should not be written when empty");
    }

    @Test
    public void testWriteHeadersWithContentTypeAndCharset() {
        TestResponse resp = createResponse();
        resp.contentType = "text/plain";
        resp.characterEncoding = "UTF-8";
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertTrue(headerStr.toLowerCase().contains("charset"),
                "Charset should be appended when characterEncoding is set");
        Assertions.assertTrue(headerStr.contains("UTF-8"), "UTF-8 charset should appear in header");
    }

    @Test
    public void testWriteHeadersWithContentTypeAndCharsetInContentType() {
        // contentType already contains "charset" → charset should NOT be appended
        TestResponse resp = createResponse();
        resp.contentType = "text/html;charset=utf-8";
        resp.characterEncoding = "UTF-8";
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        // Should only contain "charset" once (from the contentType value itself, not appended)
        int charsetIdx = headerStr.toLowerCase().indexOf("charset");
        int lastCharsetIdx = headerStr.toLowerCase().lastIndexOf("charset");
        Assertions.assertEquals(charsetIdx, lastCharsetIdx,
                "charset should appear only once when already in contentType");
    }

    @Test
    public void testWriteHeadersWithContentTypeAndNullCharset() {
        TestResponse resp = createResponse();
        resp.contentType = "text/html";
        // characterEncoding is null → charset should NOT be appended
        resp.characterEncoding = null;
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertFalse(headerStr.toLowerCase().contains("charset"),
                "Charset should not be appended when characterEncoding is null");
    }

    // ==================== ensureHeadersSent ====================

    @Test
    public void testEnsureHeadersSentFirstCallWritesHeaders() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        resp.testEnsureHeadersSent();
        // First call: headersSent=false → writeHeaders + ctx.write + clear
        Assertions.assertTrue(resp.headersSent);
    }

    @Test
    public void testEnsureHeadersSentSecondCallIsNoOp() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        resp.testEnsureHeadersSent();
        Assertions.assertTrue(resp.headersSent);
        // Second call should be no-op (headersSent already true)
        resp.testEnsureHeadersSent();
    }

    // ==================== writeMultipleHeaderLines ====================

    @Test
    public void testWriteMultipleHeaderLines() {
        TestResponse resp = createResponse();
        List<String> values = new ArrayList<String>();
        values.add("v1");
        values.add("v2");
        resp.testWriteMultipleHeaderLines("X-Custom", values);
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertTrue(headerStr.contains("X-Custom: v1"));
        Assertions.assertTrue(headerStr.contains("X-Custom: v2"));
    }

    // ==================== writeHeaders with contentLength < bodySize ====================

    @Test
    public void testWriteHeadersContentLengthDefaultsToBodySize() {
        TestResponse resp = createResponse();
        // bodyBuf has some data, contentLength is 0 initially
        resp.addBodyData("hello".getBytes());
        // contentLength < bodySize → contentLength should be set to bodySize
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertTrue(headerStr.contains("content-length: 5") || headerStr.contains("Content-Length: 5"),
                "Content-Length should reflect actual body size: " + headerStr);
    }

    // ==================== flushBodyAsChunk / writeFlushChunk ====================

    @Test
    public void testFlushBodyAsChunkWritesChunk() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        resp.addBodyData("hello".getBytes());
        resp.testFlushBodyAsChunk();
        // flushBodyAsChunk calls writeFlushChunk(bodyBuf, size) → ctx.write (flush is deferred)
        verify(ctx, atLeast(3)).write(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testFlushBodyAsChunkWithEmptyBody() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        resp.testFlushBodyAsChunk();
        // Empty bodyBuf → writeFlushChunk writes zero-length chunk without flush
    }

    @Test
    public void testWriteFlushChunkWithLargeData() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        byte[] large = new byte[500];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(large);
        resp.testWriteFlushChunk(buf, 500);
        verify(ctx, atLeast(4)).write(any(java.nio.ByteBuffer.class));
    }

    // ==================== streamingGzipCompress (exercises ChunkedOutputStream) ====================

    @Test
    public void testStreamingGzipCompressSmallData() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        byte[] smallData = "small test data".getBytes();
        resp.testStreamingGzipCompress(new java.io.ByteArrayInputStream(smallData));
        // Should complete without error, ctx.write is called by ChunkedOutputStream
        verify(ctx, atLeastOnce()).write(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testStreamingGzipCompressLargeData() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        byte[] largeData = new byte[10000];
        java.util.Arrays.fill(largeData, (byte) 'X');
        resp.testStreamingGzipCompress(new java.io.ByteArrayInputStream(largeData));
        verify(ctx, atLeastOnce()).write(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testStreamingGzipCompressEmptyStream() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(createMockRequest(), ctx);
        resp.testStreamingGzipCompress(new java.io.ByteArrayInputStream(new byte[0]));
        verify(ctx, atLeastOnce()).write(any(java.nio.ByteBuffer.class));
    }

    // ==================== setKeepAlive ====================

    @Test
    public void testSetKeepAliveTrue() {
        TestResponse resp = createResponse();
        resp.setKeepAlive(true);
        Assertions.assertEquals("keep-alive",
                resp.headers.get(HttpHeaderNormalized.getConnection()).toString());
    }

    @Test
    public void testSetKeepAliveFalse() {
        TestResponse resp = createResponse();
        resp.setKeepAlive(false);
        Assertions.assertEquals("close",
                resp.headers.get(HttpHeaderNormalized.getConnection()).toString());
    }

    // ==================== setLastModified ====================

    @Test
    public void testSetLastModified() {
        TestResponse resp = createResponse();
        resp.setLastModified(1700000000000L);
        String lastMod = (String) resp.headers.get(HttpHeaderNormalized.getLastModified());
        Assertions.assertNotNull(lastMod);
        Assertions.assertTrue(lastMod.contains("GMT"));
    }

    // ==================== WriteHeaders List header with CommaSeparated ====================

    @Test
    public void testWriteHeadersWithExplicitContentType() {
        TestResponse resp = createResponse();
        resp.contentType = "text/html";
        resp.hasExplicitContentType = true;
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        Assertions.assertFalse(headerStr.toLowerCase().contains("content-type"),
                "Content-Type header should not be auto-written when hasExplicitContentType=true");
    }

    @Test
    public void testWriteHeadersWithoutDefaultHeaders() throws Exception {
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.WRITE_DEFAULT_HEADERS, false);
        TestResponse resp = createResponse(mockChannelContext(cfg));
        resp.writeHeaders();
        String headerStr = new String(resp.headerBuf.toBytes());
        // Log output for debugging
        System.out.println("Headers without defaults: " + headerStr);
        // Should NOT contain connection header (the default header that's normally written)
        Assertions.assertFalse(headerStr.toLowerCase().contains("connection:"),
                "Connection header should not be written when WRITE_DEFAULT_HEADERS=false");
        // Normal Status line and final CRLF should still be present
        Assertions.assertTrue(headerStr.endsWith("\r\n\r\n"),
                "Headers should end with double CRLF");
    }

    // ==================== Helpers ====================

    private TestResponse createResponse() {
        return new TestResponse(createMockRequest(), mockChannelContext());
    }

    private TestResponse createResponse(ChannelContext ctx) {
        return new TestResponse(createMockRequest(), ctx);
    }

    private static HttpRequest createMockRequest() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        return mockReq;
    }

    /**
     * Concrete subclass that extends HttpGenerativeResponse to expose protected methods.
     */
    static class TestResponse extends HttpGenerativeResponse {

        TestResponse(HttpRequest request, ChannelContext ctx) {
            super(request, ctx);
        }

        @Override
        protected boolean attemptAutoGzipAndSend() { return false; }

        @Override
        protected void sendFile0(java.io.File file, long fileSize, String mimeType, boolean shouldCompress) {}

        @Override
        protected void notFound() {}

        @Override
        public void handover() {}

        // Public abstract methods no-op
        @Override protected void doAddHeader(String key, String value) {}
        @Override public void removeHeader(String key) {}
        @Override public void removeHeader(String key, Serializable value) {}
        @Override public String getHeader(String key) { return null; }
        @Override public void write(byte[] buf, int offset, int count) throws IOException {}
        @Override public void setContentLength(long len) {}
        @Override public void setChunked(boolean chunked) {}
        @Override public void setChunkedEncoding() {}
        @Override public void removeChunkedEncoding() {}
        @Override public boolean isChunked() { return false; }
        @Override public void writeChunked(byte[] data) throws IOException {}
        @Override public void flush() throws IOException {}
        @Override public void commit() throws IOException {}
        @Override public boolean isCorrupted() { return false; }
        @Override public void earlyHints(String linkHeader) throws IOException {}

        // Wrapper methods for protected methods
        void testPutHeader(String key, Serializable value) { putHeader(key, value); }
        void testDirectlyPutHeader(String key, Serializable value) { directlyPutHeader(key, value); }
        String testDirectlyGetHeader(String key) { return directlyGetHeader(key); }
        HttpVersion testGetHttpVersion() { return getHttpVersion(); }
        String testGetConnectionHeaderValue() { return getConnectionHeaderValue(); }
        void testWriteHeaderLine(String key, String value) { writeHeaderLine(key, value); }
        void testWriteCommaSeparatedHeader(String key, List<String> values) { writeCommaSeparatedHeader(key, values); }
        void testWriteDefaultHeaders() { writeDefaultHeaders(); }
        void testEnsureHeadersSent() throws IOException { ensureHeadersSent(); }
        void testWriteMultipleHeaderLines(String key, List<String> values) { writeMultipleHeaderLines(key, values); }
        void testWriteFlushChunk(java.nio.ByteBuffer buf, int length) throws IOException { writeFlushChunk(buf, length); }
        void testFlushBodyAsChunk() throws IOException { flushBodyAsChunk(); }
        void addBodyData(byte[] data) { bodyBuf.write(data); }
        void testStreamingGzipCompress(java.io.InputStream is) throws IOException { streamingGzipCompress(is); }
    }
}
