package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpDefaultResponse}.
 * <p>
 * Uses a minimal subclass to test concrete methods without needing
 * full ChannelContext integration.
 */
public class HttpDefaultResponseTest {

    private HttpRequest mockReq;
    private ChannelContext mockCtx;
    private TestDefaultResp resp;

    @BeforeEach
    public void setUp() {
        mockReq = mock(HttpRequest.class);
        when(mockReq.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);
        mockCtx = mock(ChannelContext.class);
        resp = new TestDefaultResp(mockReq, mockCtx);
    }

    @Test
    public void testIsChunkedDefaultFalse() {
        TestDefaultResp r = createResponse();
        Assertions.assertFalse(r.isChunked());
    }

    @Test
    public void testSetChunkedTrue() {
        TestDefaultResp r = createResponse();
        r.setChunked(true);
        Assertions.assertTrue(r.isChunked());
    }

    @Test
    public void testSetChunkedFalse() {
        TestDefaultResp r = createResponse();
        r.setChunked(true);
        r.setChunked(false);
        Assertions.assertFalse(r.isChunked());
    }

    @Test
    public void testSetChunkedEncoding() {
        TestDefaultResp r = createResponse();
        r.setChunkedEncoding();
        Assertions.assertTrue(r.isChunked());
    }

    @Test
    public void testRemoveChunkedEncoding() {
        TestDefaultResp r = createResponse();
        r.setChunkedEncoding();
        r.removeChunkedEncoding();
        Assertions.assertFalse(r.isChunked());
    }

    @Test
    public void testSetChunkedAfterExplicitContentLengthThrows() {
        TestDefaultResp r = createResponse();
        r.addHeader(HttpHeaderNames.CONTENT_LENGTH, 100);
        Assertions.assertThrows(IllegalStateException.class, () -> r.setChunked(true));
    }

    @Test
    public void testIsCorruptedInitiallyFalse() {
        TestDefaultResp r = createResponse();
        Assertions.assertFalse(r.isCorrupted());
    }

    @Test
    public void testSetKeepAliveDoesNotThrow() {
        TestDefaultResp r = createResponse();
        Assertions.assertDoesNotThrow(() -> r.setKeepAlive(true));
        Assertions.assertDoesNotThrow(() -> r.setKeepAlive(false));
    }

    @Test
    public void testSetLastModifiedDoesNotThrow() {
        TestDefaultResp r = createResponse();
        Assertions.assertDoesNotThrow(() -> r.setLastModified(System.currentTimeMillis()));
    }

    @Test
    public void testWriteChunkedWithoutChunkedThrows() {
        TestDefaultResp r = createResponse();
        Assertions.assertThrows(IllegalStateException.class, () -> r.writeChunked("data".getBytes()));
    }

    @Test
    public void testWriteChunkedWithChunkedDoesNotThrow() throws IOException {
        TestDefaultResp r = createResponse();
        r.setChunked(true);
        r.writeChunked("data".getBytes());
    }

    @Test
    public void testAddCacheHeadersDoesNotThrow() {
        TestDefaultResp r = createResponse();
        Assertions.assertDoesNotThrow(() -> r.callAddCacheHeaders(100, 123456789L));
    }

    @Test
    public void testResetDoesNotThrow() {
        TestDefaultResp r = createResponse();
        Assertions.assertDoesNotThrow(() -> r.reset());
    }

    @Test
    public void testResetClearsHeaderContentLengthAndChunked() {
        TestDefaultResp r = createResponse();
        r.setContentLength(100);
        r.addHeader("X-Test", "value");
        r.setChunked(true);
        r.reset();
        Assertions.assertEquals(-1, r.getContentLength());
        Assertions.assertNull(r.getHeader("X-Test"));
        Assertions.assertFalse(r.isChunked());
    }

    @Test
    public void testResetClearsBodyBuffer() throws IOException {
        TestDefaultResp r = createResponse();
        r.write("hello".getBytes());
        Assertions.assertTrue(r.bodyBuf.size() > 0);
        r.reset();
        Assertions.assertEquals(0, r.bodyBuf.size());
    }

    @Test
    public void testResetAfterHeadersSentThrows() throws IOException {
        TestDefaultResp r = createResponse();
        r.flush();
        Assertions.assertThrows(IllegalStateException.class, () -> r.reset());
    }

    @Test
    public void testIsCommittedFalseInitially() {
        TestDefaultResp r = createResponse();
        Assertions.assertFalse(r.isCommitted());
    }

    @Test
    public void testIsCommittedTrueAfterFlush() throws IOException {
        TestDefaultResp r = createResponse();
        r.flush();
        Assertions.assertTrue(r.isCommitted());
    }

    //

    @Test
    public void testToContentLengthWithStringSerializable() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, "200");
        Assertions.assertEquals(200, resp.getContentLength());
    }

    @Test
    public void testToContentLengthWithShortValue() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, (short) 300);
        Assertions.assertEquals(300, resp.getContentLength());
    }

    @Test
    public void testToContentLengthWithInvalidString() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, "not-a-number"));
    }

    @Test
    public void testToContentLengthNegativeValue() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, -5L));
    }

    @Test
    public void testToContentLengthWithInteger() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 200);
        Assertions.assertEquals(200, resp.getContentLength());
    }

    @Test
    public void testToContentLengthWithLong() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 300L);
        Assertions.assertEquals(300, resp.getContentLength());
    }

    @Test
    public void testToContentLengthWhenChunked() {
        resp.setChunked(true);
        Assertions.assertThrows(IllegalStateException.class,
                () -> resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 100));
    }

    @Test
    public void testToContentLengthWithIntegerAndLong() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 128);
        Assertions.assertEquals(128, resp.getContentLength());
        resp.removeHeader(HttpHeaderNames.CONTENT_LENGTH);
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 1024L);
        Assertions.assertEquals(1024, resp.getContentLength());
    }

    @Test
    public void testToContentLengthDirectIntegerLongCoversInstanceofBranch() throws Exception {
        java.lang.reflect.Method m = HttpDefaultResponse.class.getDeclaredMethod("toContentLength", Serializable.class);
        m.setAccessible(true);
        long fromInt = (long) m.invoke(resp, Integer.valueOf(200));
        Assertions.assertEquals(200, fromInt);
        long fromLong = (long) m.invoke(resp, Long.valueOf(300));
        Assertions.assertEquals(300, fromLong);
    }

    @Test
    public void testUpdateContentFlagsWithHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 500);
        Assertions.assertEquals(-1, resp.getContentLength());
        Assertions.assertFalse(resp.hasExplicitContentLength());
    }

    @Test
    public void testUpdateContentFlagsWithContentType() {
        resp.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html");
        Assertions.assertTrue(resp.hasExplicitContentType());
    }

    @Test
    public void testUpdateContentFlagsRemoveContentLength() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 100);
        Assertions.assertEquals(100, resp.getContentLength());
        resp.removeHeader(HttpHeaderNames.CONTENT_LENGTH);
        Assertions.assertEquals(0, resp.getContentLength());
        Assertions.assertFalse(resp.hasExplicitContentLength());
    }

    @Test
    public void testUpdateContentFlagsRemoveContentType() {
        resp.addHeader(HttpHeaderNames.CONTENT_TYPE, "application/json");
        Assertions.assertTrue(resp.hasExplicitContentType());
        resp.removeHeader(HttpHeaderNames.CONTENT_TYPE);
        Assertions.assertFalse(resp.hasExplicitContentType());
    }

    @Test
    public void testGetHeaderSingleValue() {
        resp.addHeader("X-Custom", "singleValue");
        String result = resp.getHeader("X-Custom");
        Assertions.assertEquals("singleValue", result);
    }

    @Test
    public void testGetHeaderNonExistent() {
        String result = resp.getHeader("X-Nonexistent");
        Assertions.assertNull(result);
    }

    @Test
    public void testRemoveHeaderKeyValueNonExistent() {
        Assertions.assertDoesNotThrow(() -> resp.removeHeader("X-Nonexistent", "someValue"));
    }

    @Test
    public void testRemoveHeaderKeyValueStringMatch() {
        resp.addHeader("X-Custom", "valueToRemove");
        resp.removeHeader("X-Custom", "valueToRemove");
        Assertions.assertNull(resp.getHeader("X-Custom"));
    }

    @Test
    public void testRemoveHeaderKeyValueStringNoMatch() {
        resp.addHeader("X-Custom", "actualValue");
        resp.removeHeader("X-Custom", "differentValue");
        Assertions.assertEquals("actualValue", resp.getHeader("X-Custom"));
    }

    @Test
    public void testSetContentLengthNegative() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> resp.setContentLength(-1));
    }

    @Test
    public void testSetContentLengthWhenChunked() {
        resp.setChunked(true);
        resp.setContentLength(100);
        Assertions.assertEquals(-1, resp.getContentLength());
    }

    @Test
    public void testSetContentLengthWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        resp.setContentLength(200);
        Assertions.assertEquals(-1, resp.getContentLength());
    }

    @Test
    public void testSetChunkedWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        Assertions.assertDoesNotThrow(() -> resp.setChunked(true));
        Assertions.assertFalse(resp.isChunked());
    }

    @Test
    public void testSetChunkedEncodingWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        Assertions.assertDoesNotThrow(() -> resp.setChunkedEncoding());
        Assertions.assertFalse(resp.isChunked());
    }

    @Test
    public void testRemoveChunkedEncodingWhenHeadersSent() throws Exception {
        resp.setChunked(true);
        Assertions.assertTrue(resp.isChunked());
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        resp.removeChunkedEncoding();
        Assertions.assertTrue(resp.isChunked());
    }

    @Test
    public void testIsCorruptedTrue() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 2);
        Assertions.assertTrue(resp.isCorrupted());
    }

    @Test
    public void testGetHeaderMultiValue() {
        resp.addHeader("X-Multi", "first");
        resp.addHeader("X-Multi", "second");
        String result = resp.getHeader("X-Multi");
        Assertions.assertEquals("first", result);
    }

    @Test
    public void testFlushWhenCompleted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 1);
        Assertions.assertDoesNotThrow(() -> resp.flush());
    }

    @Test
    public void testFlushWhenCorrupted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 2);
        Assertions.assertDoesNotThrow(() -> resp.flush());
    }

    @Test
    public void testWriteChunkedWhenCompleted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 1);
        Assertions.assertDoesNotThrow(() -> resp.writeChunked("test".getBytes()));
    }

    @Test
    public void testWriteNullBytes() {
        Assertions.assertDoesNotThrow(() -> resp.write(null, 0, 10));
    }

    @Test
    public void testWriteZeroCount() {
        Assertions.assertDoesNotThrow(() -> resp.write(new byte[10], 0, 0));
    }

    @Test
    public void testHandover() throws Exception {
        resp.handover();
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        Assertions.assertEquals(1, stateField.get(resp));
    }

    @Test
    public void testWriteLargeData() throws Exception {
        int threshold = getBodyMemoryThreshold();
        byte[] largeData = new byte[threshold + 1];
        Assertions.assertDoesNotThrow(() -> resp.write(largeData, 0, largeData.length));
    }

    @Test
    public void testEarlyHintsWhenCompleted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 1);
        Assertions.assertDoesNotThrow(() -> resp.earlyHints("</style.css>; rel=preload"));
    }

    @Test
    public void testEarlyHintsWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        Assertions.assertDoesNotThrow(() -> resp.earlyHints("</style.css>; rel=preload"));
    }

    @Test
    public void testWriteChunkedNullData() throws Exception {
        resp.setChunked(true);
        Assertions.assertDoesNotThrow(() -> resp.writeChunked(null));
    }

    @Test
    public void testWriteChunkedEmptyData() throws Exception {
        resp.setChunked(true);
        Assertions.assertDoesNotThrow(() -> resp.writeChunked(new byte[0]));
    }

    @Test
    public void testSendFileNullReturnsNotFound() throws Exception {
        resp.sendFile((java.io.File) null);
        Assertions.assertEquals(HttpStatus.NOT_FOUND, resp.getStatus());
    }

    // ---- helpers ----

    private TestDefaultResp createResponse() {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHttpVersion()).thenReturn(HttpVersion.HTTP_1_1);
        when(req.getHeader(anyString(), anyBoolean())).thenReturn(null);
        ChannelContext ctx = mock(ChannelContext.class);
        return new TestDefaultResp(req, ctx);
    }

    private int getBodyMemoryThreshold() throws Exception {
        java.lang.reflect.Field thresholdField = HttpConf.class.getDeclaredField("BODY_MEMORY_THRESHOLD");
        thresholdField.setAccessible(true);
        return thresholdField.getInt(null);
    }

    static class TestDefaultResp extends HttpDefaultResponse {

        TestDefaultResp(HttpRequest request, ChannelContext ctx) {
            super(request, ctx);
        }

        public void callAddCacheHeaders(long fileSize, long lastModified) throws IOException {
            addCacheHeaders(fileSize, lastModified);
        }

        @Override
        public boolean isCorrupted() {
            return super.isCorrupted();
        }

        @Override
        public void reset() {
            super.reset();
        }

        boolean hasExplicitContentLength() {
            return hasExplicitContentLength;
        }

        boolean hasExplicitContentType() {
            return hasExplicitContentType;
        }
    }
}
