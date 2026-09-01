package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpInternalResponse} (abstract base class logic).
 *
 * @author wangyc
 */
public class HttpInternalResponseTest {

    // ==================== body() ====================

    @Test
    public void testBodyBytes() {
        TestResponse resp = createResponse();
        byte[] data = "hello".getBytes();
        resp.body(data);
        Assertions.assertEquals(5, resp.bodyBuf.size());
    }

    @Test
    public void testBodyStringWithUtf8() {
        TestResponse resp = createResponse();
        resp.body("你好");
        Assertions.assertEquals(6, resp.bodyBuf.size()); // UTF-8: 6 bytes for 2 Chinese chars
    }

    @Test
    public void testBodyStringNullClearsBuffer() {
        TestResponse resp = createResponse();
        resp.body("hello");
        resp.body((String) null);
        Assertions.assertEquals(0, resp.bodyBuf.size());
    }

    // ==================== Status ====================

    @Test
    public void testSetStatus() {
        TestResponse resp = createResponse();
        resp.setStatus(HttpStatus.NOT_FOUND);
        Assertions.assertEquals(HttpStatus.NOT_FOUND, resp.getStatus());
    }

    @Test
    public void testStatusInt() {
        TestResponse resp = createResponse();
        resp.status(201);
        Assertions.assertEquals(HttpStatus.CREATED, resp.getStatus());
    }

    @Test
    public void testSetStatusAndText() {
        TestResponse resp = createResponse();
        resp.setStatusAndText(HttpStatus.NOT_FOUND);
        Assertions.assertEquals(HttpStatus.NOT_FOUND, resp.getStatus());
        Assertions.assertEquals("Not Found", new String(resp.bodyBuf.toBytes()));
    }

    // ==================== Content-Type / Content-Length / Encoding ====================

    @Test
    public void testSetContentType() {
        TestResponse resp = createResponse();
        resp.setContentType("text/html");
        Assertions.assertEquals("text/html", resp.getContentType());
    }

    @Test
    public void testSetCharacterEncoding() {
        TestResponse resp = createResponse();
        resp.setCharacterEncoding("GBK");
        Assertions.assertEquals("GBK", resp.getCharacterEncoding());
    }

    @Test
    public void testSetContentTypeExtractsCharset() {
        TestResponse resp = createResponse();
        resp.setContentType("application/json; charset=gbk");
        Assertions.assertEquals("application/json; charset=gbk", resp.getContentType());
        // charset parameter is synced to the character encoding
        Assertions.assertEquals("gbk", resp.getCharacterEncoding());
    }

    @Test
    public void testApplyCharsetFromContentTypeNoCharset() {
        TestResponse resp = createResponse();
        resp.applyCharsetFromContentType("text/plain");
        // no charset in the value -> encoding untouched
        Assertions.assertNotEquals("utf-8", resp.getCharacterEncoding());
    }

    @Test
    public void testApplyCharsetFromContentTypeWithSemicolonTrailing() {
        TestResponse resp = createResponse();
        resp.applyCharsetFromContentType("text/html;charset=UTF-8;");
        Assertions.assertEquals("UTF-8", resp.getCharacterEncoding());
    }

    @Test
    public void testGetBufferSizeDelegatesToContext() {
        TestResponse resp = createResponse();
        // getBufferSize() returns ctx.getWriteBufferSize()
        Assertions.assertEquals(resp.ctx.getWriteBufferSize(), resp.getBufferSize());
    }

    @Test
    public void testResetBufferClearsBodyBeforeCommit() {
        TestResponse resp = createResponse();
        resp.body("hello");
        Assertions.assertEquals(5, resp.bodyBuf.size());
        resp.resetBuffer();
        Assertions.assertEquals(0, resp.bodyBuf.size());
    }

    @Test
    public void testResetBufferThrowsAfterCommit() {
        TestResponse resp = createResponse();
        resp.headersSent = true;
        Assertions.assertThrows(IllegalStateException.class, resp::resetBuffer);
    }

    @Test
    public void testAddHeaderRejectsCRLFInKey() {
        TestResponse resp = createResponse();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> resp.addHeader("X-Evil\r\nInjected", "v"));
    }

    @Test
    public void testAddHeaderRejectsCRLFInValue() {
        TestResponse resp = createResponse();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> resp.addHeader("X-Evil", "bad\r\nvalue"));
    }

    @Test
    public void testAddHeaderAcceptsNumberAndBoolean() {
        TestResponse resp = createResponse();
        // Number / Boolean values are inherently CR/LF-free and must be accepted
        resp.addHeader("X-Num", 123);
        resp.addHeader("X-Bool", true);
    }

    // ==================== GZIP compress (protected final, same-package can call) ====================

    @Test
    public void testGzipCompressData() throws Exception {
        TestResponse resp = createResponse();
        // Use longer repeated data to ensure compression is effective
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; ++i) {
            sb.append("Hello World, this is compressible data! ");
        }
        byte[] original = sb.toString().getBytes("UTF-8");
        byte[] compressed = resp.callGzipCompress(original);
        Assertions.assertNotNull(compressed);
        Assertions.assertTrue(compressed.length < original.length, "Compressed data (" + compressed.length + ") should be smaller than original (" + original.length + ")");
    }

    @Test
    public void testGzipCompressWithOffset() throws Exception {
        TestResponse resp = createResponse();
        byte[] original = "prefix_suffix_".getBytes("UTF-8");
        byte[] compressed = resp.callGzipCompress(original, 7, 6);
        Assertions.assertNotNull(compressed);
    }

    @Test
    public void testGzipCompressEmptyData() throws Exception {
        TestResponse resp = createResponse();
        byte[] result = resp.callGzipCompress(new byte[0]);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(0, result.length);
    }

    // ==================== shouldCompressMimeType (protected final) ====================

    @Test
    public void testShouldCompressTextHtml() {
        TestResponse resp = createResponse();
        Assertions.assertTrue(resp.callShouldCompressMimeType("text/html"));
    }

    @Test
    public void testShouldNotCompressImagePng() {
        TestResponse resp = createResponse();
        Assertions.assertFalse(resp.callShouldCompressMimeType("image/png"));
    }

    @Test
    public void testShouldNotCompressNull() {
        TestResponse resp = createResponse();
        Assertions.assertFalse(resp.callShouldCompressMimeType(null));
    }

    // ==================== ETag (protected final) ====================

    @Test
    public void testGenerateETag() {
        TestResponse resp = createResponse();
        String etag = resp.callGenerateETag(100, 123456789L);
        Assertions.assertTrue(etag.startsWith("\""), etag);
        Assertions.assertTrue(etag.endsWith("\""), etag);
        Assertions.assertTrue(etag.contains("-"), etag);
    }

    // ==================== resolveMimeType (protected final) ====================

    @Test
    public void testResolveMimeTypeHtml() {
        TestResponse resp = createResponse();
        String mime = resp.callResolveMimeType("index.html");
        Assertions.assertTrue(mime.contains("html"), mime);
    }

    @Test
    public void testResolveMimeTypeUnknown() {
        TestResponse resp = createResponse();
        Assertions.assertEquals("application/octet-stream", resp.callResolveMimeType("file.unknown"));
    }

    // ==================== Keep-Alive ====================

    @Test
    public void testIsKeepAliveHttp11() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertTrue(resp.isKeepAlive());
    }

    @Test
    public void testIsKeepAliveHttp10() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_0);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertFalse(resp.isKeepAlive());
    }

    @Test
    public void testIsKeepAliveH2() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertTrue(resp.isKeepAlive());
    }

    @Test
    public void testIsKeepAliveWithConnectionClose() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("close");
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertFalse(resp.isKeepAlive());
    }

    // ==================== Gzip Supported ====================

    @Test
    public void testIsGzipSupported() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(eq("accept-encoding"), anyBoolean())).thenReturn("gzip, deflate");
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertTrue(resp.isGzipSupported());
    }

    @Test
    public void testIsGzipNotSupported() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(eq("accept-encoding"), anyBoolean())).thenReturn("deflate");
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertFalse(resp.isGzipSupported());
    }

    // ==================== Auto-Commit ====================

    @Test
    public void testSetAutoCommit() {
        TestResponse resp = createResponse();
        Assertions.assertTrue(resp.isAutoCommit());
        resp.setAutoCommit(false);
        Assertions.assertFalse(resp.isAutoCommit());
    }

    // ==================== OutputStream ====================

    @Test
    public void testOutputStreamReturnsSameInstance() {
        TestResponse resp = createResponse();
        OutputStream os1 = resp.outputStream();
        OutputStream os2 = resp.outputStream();
        Assertions.assertSame(os1, os2);
    }

    @Test
    public void testOutputStreamWrite() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        OutputStream os = resp.outputStream();
        os.write("data".getBytes());
        Assertions.assertEquals(4, resp.bodyBuf.size());
    }

    // ==================== sendFile validation ====================

    @Test
    public void testSendFileNullCallsNotFound() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        final boolean[] notFoundCalled = {false};
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT) {
            @Override
            protected void notFound() {
                notFoundCalled[0] = true;
            }
        };
        resp.sendFile(null);
        Assertions.assertTrue(notFoundCalled[0], "notFound should be called for null file");
    }

    // ==================== checkNotModified (protected final) ====================

    @Test
    public void testCheckNotModifiedNoMatchHeader() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 12345L);
        Assertions.assertFalse(result, "Should return false without If-None-Match or If-Modified-Since");
    }

    // ==================== readFileContent (protected) ====================

    @Test
    public void testReadFileContent() throws Exception {
        TestResponse resp = createResponse();
        File tempFile = File.createTempFile("test-", ".txt");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "file content".getBytes());
            byte[] content = resp.callReadFileContent(tempFile, (int) tempFile.length());
            Assertions.assertEquals("file content", new String(content));
        } finally {
            tempFile.delete();
        }
    }

    // ==================== contentLength, chunked delegates ====================

    @Test
    public void testContentLengthDelegatesToSetContentLength() {
        TestResponse resp = createResponse();
        HttpResponse result = resp.contentLength(100);
        Assertions.assertSame(resp, result);
        Assertions.assertEquals(100, resp.contentLength);
    }

    @Test
    public void testChunkedDelegatesToSetChunked() {
        TestResponse resp = createResponse();
        HttpResponse result = resp.chunked();
        Assertions.assertSame(resp, result);
    }

    @Test
    public void testChunkedBooleanDelegatesToSetChunked() {
        TestResponse resp = createResponse();
        HttpResponse result = resp.chunked(false);
        Assertions.assertSame(resp, result);
    }

    @Test
    public void testBodyStringWithNullClearsBuffer() {
        TestResponse resp = createResponse();
        resp.body("hello");
        resp.body((String) null);
        Assertions.assertEquals(0, resp.bodyBuf.size());
    }

    @Test
    public void testBodyStringWithEncoding() {
        TestResponse resp = createResponse();
        resp.setCharacterEncoding("UTF-8");
        resp.body("你好");
        Assertions.assertEquals(6, resp.bodyBuf.size());
    }

    // ==================== complete() ====================

    @Test
    public void testComplete() throws Exception {
        TestResponse resp = createResponse();
        resp.body("content");
        Assertions.assertDoesNotThrow(() -> resp.complete());
        // After complete, bodyBuf should be reset
        Assertions.assertEquals(0, resp.bodyBuf.size());
    }

    // ==================== shouldApplyAutoGzip ====================

    @Test
    public void testShouldApplyAutoGzipFalseWhenGzipDisabled() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(eq("accept-encoding"), anyBoolean())).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        resp.body("data that is long enough for gzip to apply but gzip is config-disabled");
        // Should be false because HttpConf.GZIP is false by default
        Assertions.assertFalse(resp.callShouldApplyAutoGzip());
    }

    @Test
    public void testShouldApplyAutoGzipFalseWhenNoBody() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(eq("accept-encoding"), anyBoolean())).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertFalse(resp.callShouldApplyAutoGzip());
    }

    // ==================== Chunked methods ====================

    @Test
    public void testIsChunked() {
        TestResponse resp = createResponse();
        Assertions.assertFalse(resp.isChunked());
    }

    @Test
    public void testSetChunked() {
        TestResponse resp = createResponse();
        resp.setChunked(true);
    }

    @Test
    public void testSetChunkedEncoding() {
        TestResponse resp = createResponse();
        resp.setChunkedEncoding();
    }

    @Test
    public void testRemoveChunkedEncoding() {
        TestResponse resp = createResponse();
        resp.removeChunkedEncoding();
    }

    @Test
    public void testWriteChunked() throws Exception {
        TestResponse resp = createResponse();
        resp.writeChunked("chunk".getBytes());
        Assertions.assertEquals(5, resp.bodyBuf.size());
    }

    // ==================== body(String) catch ====================

    @Test
    public void testBodyStringWithUnsupportedEncoding() {
        TestResponse resp = createResponse();
        resp.setCharacterEncoding("INVALID-CHARSET");
        resp.body("hello");
        Assertions.assertEquals(5, resp.bodyBuf.size());
    }

    // ==================== Keep-Alive additional ====================

    @Test
    public void testIsKeepAliveWithNonCloseConnection() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("keep-alive");
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertTrue(resp.isKeepAlive());
    }

    // ==================== setKeepAlive / setLastModified ====================

    @Test
    public void testSetKeepAlive() {
        TestResponse resp = createResponse();
        resp.setKeepAlive(true);
    }

    @Test
    public void testSetLastModified() {
        TestResponse resp = createResponse();
        resp.setLastModified(System.currentTimeMillis());
    }

    // ==================== OutputStream remaining ====================

    @Test
    public void testOutputStreamWriteSingleByte() throws Exception {
        TestResponse resp = createResponse();
        OutputStream os = resp.outputStream();
        os.write('A');
        Assertions.assertEquals(1, resp.bodyBuf.size());
    }

    @Test
    public void testOutputStreamWriteWithOffset() throws Exception {
        TestResponse resp = createResponse();
        OutputStream os = resp.outputStream();
        os.write("hello".getBytes(), 1, 3);
        Assertions.assertEquals(3, resp.bodyBuf.size());
    }

    @Test
    public void testOutputStreamFlush() throws Exception {
        TestResponse resp = createResponse();
        OutputStream os = resp.outputStream();
        os.flush();
    }

    // ==================== removeHeader with value ====================

    @Test
    public void testRemoveHeaderWithValue() {
        TestResponse resp = createResponse();
        resp.removeHeader("key", "value");
    }

    // ==================== isGzipSupported null header ====================

    @Test
    public void testIsGzipNotSupportedWhenNoAcceptEncoding() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn(null);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        Assertions.assertFalse(resp.isGzipSupported());
    }

    // ==================== complete coverage ====================

    @Test
    public void testCompleteWithAutoCommitFalse() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        resp.body("content");
        resp.setAutoCommit(false);
        Assertions.assertDoesNotThrow(() -> resp.complete());
    }

    @Test
    public void testCompleteWithAutoGzipTrue() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        resp.body("content");
        resp.autoGzipResult = true;
        Assertions.assertDoesNotThrow(() -> resp.complete());
    }

    // ==================== reset() ====================

    @Test
    public void testReset() {
        TestResponse resp = createResponse();
        resp.body("data");
        Assertions.assertEquals(4, resp.bodyBuf.size());
        resp.reset();
        Assertions.assertEquals(0, resp.bodyBuf.size());
    }

    // ==================== SSE full coverage ====================

    @Test
    public void testSseWithFullParams() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        HttpResponse ret = resp.sse("chat", "hello", "msg-001", 3000);
        Assertions.assertSame(resp, ret);
    }

    @Test
    public void testSseDoubleCall() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        resp.sse("first");
        resp.sse("second");
    }

    @Test
    public void testSseEmitter() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        SseEmitter emitter = resp.sseEmitter();
        Assertions.assertNotNull(emitter);
    }

    // ==================== shouldApplyAutoGzip additional ====================

    @Test
    public void testShouldApplyAutoGzipFalseWhenHeadersSent() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        resp.body("data that is long enough for gzip to apply");
        resp.headersSent = true;
        Assertions.assertFalse(resp.callShouldApplyAutoGzip());
    }

    // ==================== checkNotModified ====================

    @Test
    public void testCheckNotModifiedWithEtagMatch() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        // etag: hex(2748)=abc, hex(100)=64 => "\"abc-64\""
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("\"abc-64\"");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertTrue(result, "Should return true when If-None-Match matches");
    }

    @Test
    public void testCheckNotModifiedWithEtagNoMatch() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("\"not-match\"");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when If-None-Match does not match");
    }

    @Test
    public void testCheckNotModifiedWithIfModifiedSince() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        long lastModified = 1000000L;
        String dateHeader = HttpHeaderUtils.getDateHeaderValue(lastModified);
        when(mockReq.getHeader(HttpHeaderNormalized.getIfModifiedSince(), true))
            .thenReturn(dateHeader);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, lastModified);
        Assertions.assertTrue(result, "Should return true when If-Modified-Since matches");
    }

    // ==================== sendFile coverage ====================

    @Test
    public void testSendFileReturnsEarlyWhenHeadersSent() throws Exception {
        TestResponse resp = createResponse();
        resp.headersSent = true;
        resp.sendFile(null);
    }

    @Test
    public void testSendFileReturnsEarlyWhenCommitted() throws Exception {
        TestResponse resp = createResponse();
        resp.committed = true;
        resp.sendFile(null);
    }

    @Test
    public void testSendFileEmptyFile() throws Exception {
        TestResponse resp = createResponse();
        File tempFile = File.createTempFile("test-", ".txt");
        try {
            resp.sendFile(tempFile);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testSendFileWithCacheDisabled() throws Exception {
        TestResponse resp = createResponse();
        File tempFile = File.createTempFile("test-", ".txt");
        java.nio.file.Files.write(tempFile.toPath(), "content".getBytes());
        try {
            resp.sendFile(tempFile, false, -1);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testSendFileWithCacheAndMaxAge() throws Exception {
        TestResponse resp = createResponse();
        File tempFile = File.createTempFile("test-", ".txt");
        java.nio.file.Files.write(tempFile.toPath(), "content".getBytes());
        try {
            resp.sendFile(tempFile, true, 3600);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testSendFileWithCacheAndEtagHit() throws Exception {
        File tempFile = File.createTempFile("test-", ".txt");
        java.nio.file.Files.write(tempFile.toPath(), new byte[100]);
        long lastModified = tempFile.lastModified();
        long fileSize = tempFile.length();
        String expectedEtag = "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(fileSize) + "\"";
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn(expectedEtag);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        try {
            resp.sendFile(tempFile, true, -1);
        } finally {
            tempFile.delete();
        }
    }

    // ── 4-arg sendFile cacheControl coverage ──

    @Test
    public void testSendFileWithNullCacheControl() throws Exception {
        // cacheControl=null → no Cache-Control header set
        final String[] capturedCC = {null};
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT) {
            @Override
            protected void doAddHeader(String key, String value) {
                if (HttpHeaderNames.CACHE_CONTROL.equals(key)) {
                    capturedCC[0] = value;
                }
            }
        };
        File tempFile = File.createTempFile("test-", ".txt");
        java.nio.file.Files.write(tempFile.toPath(), "content".getBytes());
        try {
            resp.sendFile(tempFile, true, null, "text/plain");
        } finally {
            tempFile.delete();
        }
        Assertions.assertNull(capturedCC[0]);
    }

    @Test
    public void testSendFileWithEmptyCacheControl() throws Exception {
        // empty cacheControl → same as null, no header set
        final boolean[] ccSet = {false};
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT) {
            @Override
            protected void doAddHeader(String key, String value) {
                if (HttpHeaderNames.CACHE_CONTROL.equals(key)) {
                    ccSet[0] = true;
                }
            }
        };
        File tempFile = File.createTempFile("test-", ".txt");
        java.nio.file.Files.write(tempFile.toPath(), "content".getBytes());
        try {
            resp.sendFile(tempFile, true, "", "text/plain");
        } finally {
            tempFile.delete();
        }
        Assertions.assertFalse(ccSet[0]);
    }

    @Test
    public void testSendFileWithCustomCacheControl() throws Exception {
        // non-null non-empty cacheControl → used directly
        final String[] capturedCC = {null};
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT) {
            @Override
            protected void doAddHeader(String key, String value) {
                if (HttpHeaderNames.CACHE_CONTROL.equals(key)) {
                    capturedCC[0] = value;
                }
            }
        };
        File tempFile = File.createTempFile("test-", ".txt");
        java.nio.file.Files.write(tempFile.toPath(), "content".getBytes());
        try {
            resp.sendFile(tempFile, true, "public, max-age=31536000, immutable", "text/plain");
        } finally {
            tempFile.delete();
        }
        Assertions.assertEquals("public, max-age=31536000, immutable", capturedCC[0]);
    }

    @Test
    public void testSendFileWithDirectory() throws Exception {
        TestResponse resp = createResponse();
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        resp.sendFile(tempDir);
        // should call notFound() because tempDir.isDirectory() is true
    }

    // ==================== sse with null event ====================

    @Test
    public void testSseWithNullEvent() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        HttpResponse ret = resp.sse(null, "data", null, -1);
        Assertions.assertSame(resp, ret);
    }

    // ==================== shouldCompressMimeType true branches ====================

    @Test
    public void testShouldCompressMimeTypeJson() {
        TestResponse resp = createResponse();
        Assertions.assertTrue(resp.callShouldCompressMimeType("application/json"));
    }

    @Test
    public void testShouldCompressMimeTypeJavascript() {
        TestResponse resp = createResponse();
        Assertions.assertTrue(resp.callShouldCompressMimeType("application/javascript"));
    }

    @Test
    public void testShouldCompressMimeTypeXml() {
        TestResponse resp = createResponse();
        Assertions.assertTrue(resp.callShouldCompressMimeType("application/xml"));
    }

    @Test
    public void testShouldCompressMimeTypeCss() {
        TestResponse resp = createResponse();
        // "text/css" short-circuits at startsWith("text/"), use non-text/ mime
        Assertions.assertTrue(resp.callShouldCompressMimeType("font/otf+css"));
    }

    // ==================== checkNotModified: etag short-circuit branches ====================

    @Test
    public void testCheckNotModifiedWithEtagWrongFirstChar() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        // hexLM="abc"(3), hexFS="64"(2), hexLen=8. ifNoneMatch must be 8 chars, charAt(0)!='"':
        // "*abc-64\"" → Java: "*abc-64\"" → 8 chars: * a b c - 6 4 "
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("*abc-64\"");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when first char is not quote");
    }

    @Test
    public void testCheckNotModifiedWithEtagWrongLastChar() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        // ifNoneMatch length=8, charAt(0)='"', charAt(7)!='"':
        // "\"abc-64*\"" → Java: "\"abc-64*" → "abc-64* → 8 chars
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("\"abc-64*");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when last char is not quote");
    }

    @Test
    public void testCheckNotModifiedWithEtagWrongSeparator() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        // ifNoneMatch length=8, charAt(0)='"', charAt(7)='"', charAt(4)!='-':
        // "\"abc:64\"" → "abc:64" → 8 chars, charAt(4)=':'
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("\"abc:64\"");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when separator is not dash");
    }

    @Test
    public void testCheckNotModifiedWithEtagWrongHexLastModified() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        // All format checks pass, but regionMatches for hexLastModified fails:
        // "\"abd-64\"" → "abd-64" → 8 chars, regionMatches(1,"abc",0,3) → "abd" vs "abc" → false
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("\"abd-64\"");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when hexLastModified does not match");
    }

    @Test
    public void testCheckNotModifiedWithEtagWrongHexFileSize() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        // All format checks pass, regionMatches hexLM passes, but hexFS fails:
        // "\"abc-65\"" → "abc-65" → 8 chars, regionMatches(5,"64",0,2) → "65" vs "64" → false
        when(mockReq.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true))
            .thenReturn("\"abc-65\"");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when hexFileSize does not match");
    }

    // ==================== checkNotModified: If-Modified-Since no match ====================

    @Test
    public void testCheckNotModifiedWithIfModifiedSinceNoMatch() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getIfModifiedSince(), true))
            .thenReturn("Thu, 01 Jan 1970 00:00:00 GMT");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        boolean result = resp.callCheckNotModified(100L, 2748L);
        Assertions.assertFalse(result, "Should return false when If-Modified-Since does not match");
    }

    // ==================== sendFile: !file.canRead() ====================

    @Test
    public void testSendFileWithUnreadableFile() throws Exception {
        TestResponse resp = createResponse();
        File tempFile = File.createTempFile("test-", ".txt");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "content".getBytes());
            if (tempFile.setReadable(false)) {
                resp.sendFile(tempFile);
                // should call notFound() because !file.canRead() is true
            }
        } finally {
            tempFile.setReadable(true);
            tempFile.delete();
        }
    }

    @Test
    public void testSendFileNonExistent() throws Exception {
        TestResponse resp = createResponse();
        File nonExistent = new File("__non_existent_file_xxx__");
        resp.sendFile(nonExistent);
        // should call notFound() because !file.exists() is true
    }

    // ==================== GZIP=true branches (set via static initializer) ====================


    @Test
    public void testGzipShouldApplyAutoGzipNoSupport() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("deflate");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 512; ++i) sb.append("ABCD");
        resp.body(sb.toString());
        Assertions.assertFalse(resp.callShouldApplyAutoGzip());
    }

    @Test
    public void testGzipShouldApplyAutoGzipHeadersSent() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        resp.body("body");
        resp.headersSent = true;
        Assertions.assertFalse(resp.callShouldApplyAutoGzip());
    }

    @Test
    public void testGzipShouldApplyAutoGzipBodyTooSmall() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        TestResponse resp = new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
        resp.body("tiny");
        Assertions.assertFalse(resp.callShouldApplyAutoGzip());
    }

    @Test
    public void testGzipSendFileAllCompressed() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        File tempFile = File.createTempFile("test-", ".txt");
        try {
            byte[] content = new byte[4096];
            java.nio.file.Files.write(tempFile.toPath(), content);
            resp.sendFile(tempFile, true, -1);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGzipSendFileNoSupport() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("deflate");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        File tempFile = File.createTempFile("test-", ".txt");
        try {
            byte[] content = new byte[4096];
            java.nio.file.Files.write(tempFile.toPath(), content);
            resp.sendFile(tempFile, true, -1);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGzipSendFileSmallFile() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        File tempFile = File.createTempFile("test-", ".txt");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "small".getBytes());
            resp.sendFile(tempFile, true, -1);
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGzipSendFileNonCompressible() throws Exception {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        ChannelContext mockCtx = mock(ChannelContext.class);
        TestResponse resp = new TestResponse(mockReq, mockCtx);
        File tempFile = File.createTempFile("test-", ".png");
        try {
            byte[] content = new byte[4096];
            java.nio.file.Files.write(tempFile.toPath(), content);
            resp.sendFile(tempFile, true, -1);
        } finally {
            tempFile.delete();
        }
    }

    // ==================== HttpDefaultResponse compressAndSendFile ====================

    @Test
    public void testHttp1SendFileCompressSmallFile() throws Exception {
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.GZIP, true);
        cfg.option(HttpOptions.GZIP_MIN_SIZE, 1);
        ChannelContext ctx = mockChannelContext(cfg);

        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);

        HttpDefaultResponse resp = new HttpDefaultResponse(mockReq, ctx);

        File tempFile = File.createTempFile("test-", ".html");
        try {
            byte[] content = "Hello World, compressible data! ".getBytes();
            java.nio.file.Files.write(tempFile.toPath(), content);
            resp.sendFile(tempFile, true, -1);
            // Small file compressed and sent via bodyBuf + commit → headers should have been sent
            Assertions.assertNotNull(resp.getContentType());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testHttp1SendFileCompressLargeFile() throws Exception {
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.GZIP, true);
        cfg.option(HttpOptions.GZIP_MIN_SIZE, 1);
        cfg.option(HttpOptions.BODY_MEMORY_THRESHOLD, 16);
        ChannelContext ctx = mockChannelContext(cfg);

        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true)).thenReturn("gzip");
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);

        HttpDefaultResponse resp = new HttpDefaultResponse(mockReq, ctx);

        File tempFile = File.createTempFile("test-", ".html");
        try {
            byte[] content = "This is a larger file for streaming GZIP compression in Http1 response!".getBytes();
            java.nio.file.Files.write(tempFile.toPath(), content);
            resp.sendFile(tempFile, true, -1);
            Assertions.assertNotNull(resp.getContentType());
        } finally {
            tempFile.delete();
        }
    }

    // ==================== Helpers ====================

    /**
     * Build a real {@link ChannelContext} (spy) bound to a {@link NioConfig} that mirrors the
     * live HttpConf defaults and applies the given overrides, so config-branch coverage does
     * not require reflective/Unsafe mutation of HttpConf static fields.
     */
    private static ChannelContext mockChannelContext(NioConfig overrides) {
        ChannelContext ctx;
        try {
            java.lang.reflect.Constructor<ChannelContext> ctor =
                    ChannelContext.class.getDeclaredConstructor(long.class, java.nio.channels.SocketChannel.class, int.class);
            ctor.setAccessible(true);
            // Mock channel + a non-zero bufferSize so sendFile takes the buffered (in-memory) path
            // instead of zero-copy transferTo; the mock channel's write() consumes all bytes so the
            // real ChannelContext#write loop cannot deadlock on a 0-byte return.
            java.nio.channels.SocketChannel mockChannel = mock(java.nio.channels.SocketChannel.class);
            when(mockChannel.write(any(java.nio.ByteBuffer.class))).thenAnswer(inv -> {
                java.nio.ByteBuffer b = inv.getArgument(0);
                int n = b.remaining();
                b.position(b.limit());
                return n;
            });
            ctx = ctor.newInstance(0L, mockChannel, 8192);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Per-test overrides are applied on top of each option's default value (option.value,
        // which mirrors the live HttpConf). Unset keys fall back to the default automatically.
        NioConfig nioConfig = new NioConfig();
        if (overrides != null) {
            nioConfig.option(HttpOptions.EXPOSE_SERVER_HEADER, overrides.option(HttpOptions.EXPOSE_SERVER_HEADER));
            nioConfig.option(HttpOptions.WRITE_DEFAULT_HEADERS, overrides.option(HttpOptions.WRITE_DEFAULT_HEADERS));
            nioConfig.option(HttpOptions.GZIP, overrides.option(HttpOptions.GZIP));
            nioConfig.option(HttpOptions.GZIP_MIN_SIZE, overrides.option(HttpOptions.GZIP_MIN_SIZE));
            nioConfig.option(HttpOptions.BODY_MEMORY_THRESHOLD, overrides.option(HttpOptions.BODY_MEMORY_THRESHOLD));
        }
        ctx.attachNioConfig(nioConfig);
        return ctx;
    }

    private TestResponse createResponse() {
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        return new TestResponse(mockReq, ChannelContext.EMPTY_CONTEXT);
    }


    /**
     * Concrete subclass that exposes all protected methods for testing.
     */
    static class TestResponse extends HttpInternalResponse {

        TestResponse(HttpRequest request, ChannelContext ctx) {
            super(request, ctx);
        }

        @Override
        public HttpVersion getHttpVersion() {
            return request != null ? request.getHttpVersion() : null;
        }

        boolean autoGzipResult = false;

        @Override
        protected boolean attemptAutoGzipAndSend() {
            return autoGzipResult;
        }

        @Override
        protected void sendFile0(File file, long fileSize, String mimeType, boolean shouldCompress) {
        }

        @Override
        protected void notFound() {
        }

        @Override
        public void handover() {
        }

        @Override
        public boolean isCorrupted() {
            return false;
        }

        // Public abstract methods no-op (only methods NOT implemented in HttpCompleteResponse)
        @Override protected void doAddHeader(String key, String value) {}
        @Override public void removeHeader(String key) {}
        @Override public String getHeader(String key) { return null; }

        @Override
        public List<String> getHeaders(String name) {
            return Collections.emptyList();
        }

        @Override
        public Set<String> getHeaderNames() {
            return Collections.emptySet();
        }

        @Override public void write(byte[] buf, int offset, int count) throws IOException { bodyBuf.write(buf, offset, count); }
        @Override public void flush() throws IOException {}
        @Override public void commit() throws IOException { bodyBuf.reset(); }
        @Override public void earlyHints(String linkHeader) throws IOException {}

        // Wrapper methods (use DIFFERENT names to avoid conflict with final methods)
        public byte[] callGzipCompress(byte[] data) throws IOException {
            return gzipCompress(data);
        }

        public byte[] callGzipCompress(byte[] data, int offset, int len) throws IOException {
            return gzipCompress(data, offset, len);
        }

        public boolean callShouldCompressMimeType(String mimeType) {
            return shouldCompressMimeType(mimeType);
        }

        public String callGenerateETag(long fileSize, long lastModified) {
            return generateETag(fileSize, lastModified);
        }

        public String callResolveMimeType(String filename) {
            return resolveMimeType(filename);
        }

        public boolean callCheckNotModified(long fileSize, long lastModified) throws IOException {
            return checkNotModified(fileSize, lastModified);
        }

        public byte[] callReadFileContent(File file, int fileSize) throws IOException {
            return readFileContent(file, fileSize);
        }

        public boolean callShouldApplyAutoGzip() {
            return shouldApplyAutoGzip();
        }
    }
}
