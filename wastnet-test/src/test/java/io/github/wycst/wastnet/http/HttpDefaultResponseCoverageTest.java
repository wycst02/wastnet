package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;

import java.io.Serializable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Coverage tests for uncovered branches in {@link HttpDefaultResponse} and {@link HttpInternalResponse}.
 * <p>
 * These tests cover branches that were missed in the initial test suite.
 * All tests use mocks and do NOT require a real SocketChannel connection.
 */
public class HttpDefaultResponseCoverageTest {

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

    // ==================== toContentLength uncovered branches ====================

    /**
     * toContentLength with non-Integer non-Long Serializable (e.g. String "200")
     * Branch L62: value instanceof Integer || value instanceof Long → false
     * The String "200" goes through Long.parseLong path
     */
    @Test
    public void testToContentLengthWithStringSerializable() {
        // addHeader triggers updateContentFlags → toContentLength
        // Passing String "200" triggers the "else" branch (Long.parseLong)
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, "200");
        Assertions.assertEquals(200, resp.getContentLength());
    }

    /**
     * toContentLength with non-Integer non-Long Serializable that is a Number
     * e.g. Short value - goes through Long.parseLong path
     */
    @Test
    public void testToContentLengthWithShortValue() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, (short) 300);
        Assertions.assertEquals(300, resp.getContentLength());
    }

    /**
     * toContentLength: invalid String value -> IllegalArgumentException
     * Branch L62-65: Long.parseLong throws NumberFormatException
     */
    @Test
    public void testToContentLengthWithInvalidString() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, "not-a-number"));
    }

    /**
     * toContentLength: negative numeric value -> IllegalArgumentException
     * Branch L69-70: length < 0
     */
    @Test
    public void testToContentLengthNegativeValue() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, -5L));
    }

    /**
     * toContentLength with Integer value -> true branch (value instanceof Integer)
     * Branch L59-60: length = ((Number) value).longValue()
     */
    @Test
    public void testToContentLengthWithInteger() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 200);
        Assertions.assertEquals(200, resp.getContentLength());
    }

    /**
     * toContentLength with Long value -> true branch (value instanceof Long)
     * Branch L59-60: length = ((Number) value).longValue()
     */
    @Test
    public void testToContentLengthWithLong() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 300L);
        Assertions.assertEquals(300, resp.getContentLength());
    }

    /**
     * toContentLength: chunked encoding enabled -> IllegalStateException
     * Branch L55-56: chunked -> throw
     */
    @Test
    public void testToContentLengthWhenChunked() {
        resp.setChunked(true);
        Assertions.assertThrows(IllegalStateException.class,
                () -> resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 100));
    }

    /**
     * toContentLength: Integer/Long direct path (no String parsing)
     * Branch L59-60: value instanceof Integer || Long -> longValue()
     */
    @Test
    public void testToContentLengthWithIntegerAndLong() {
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 128);
        Assertions.assertEquals(128, resp.getContentLength());
        // reset by removing, then add a Long
        resp.removeHeader(HttpHeaderNames.CONTENT_LENGTH);
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 1024L);
        Assertions.assertEquals(1024, resp.getContentLength());
    }

    /**
     * toContentLength true branch (L59-60): value instanceof Integer || Long -> ((Number) value).longValue()
     * <p>
     * NOTE: addHeader(key, value) converts value to String via String.valueOf() BEFORE
     * updateContentFlags/toContentLength sees it (see HttpInternalResponse.addHeader), so the
     * Integer/Long type info is lost on the addHeader path and only the Long.parseLong branch
     * (else) is exercised. To cover the instanceof Integer/Long branch we must invoke the
     * private toContentLength(Serializable) directly with a real Integer/Long instance.
     */
    @Test
    public void testToContentLengthDirectIntegerLongCoversInstanceofBranch() throws Exception {
        java.lang.reflect.Method m = HttpDefaultResponse.class.getDeclaredMethod("toContentLength", Serializable.class);
        m.setAccessible(true);

        // Integer -> L59 first operand true, L60 executed
        long fromInt = (long) m.invoke(resp, Integer.valueOf(200));
        Assertions.assertEquals(200, fromInt);

        // Long -> L59 second operand true, L60 executed
        long fromLong = (long) m.invoke(resp, Long.valueOf(300));
        Assertions.assertEquals(300, fromLong);
    }

    // ==================== updateContentFlags uncovered branches ====================

    /**
     * updateContentFlags with headersSent=true
     * Branch L88: headersSent → true → return false
     * To trigger this, call addHeader with Content-Encoding header first,
     * then force headersSent, then call addHeader again with a content header.
     * Since updateContentFlags is protected, we use the public addHeader to trigger it.
     * Actually, the simplest way: call setChunkedEncoding + ensureHeadersSent, then addHeader
     * But ensureHeadersSent requires real ctx.
     * <p>
     * Alternative: headersSent is not directly settable from outside.
     * But addHeader calls updateContentFlags, and when headersSent=true the method
     * returns false without setting content flags. The addHeader method ignores the
     * return value of updateContentFlags when headersSent=true because it just checks
     * overwriteMode which will be false. The string value is still put in the headers map.
     * <p>
     * We can't easily set headersSent=true from outside without calling ensureHeadersSent
     * which requires ctx.write. So we'll use reflection to set headersSent.
     */
    @Test
    public void testUpdateContentFlagsWithHeadersSent() throws Exception {
        // Set headersSent to true via reflection
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);

        // Now call addHeader with a Content-Length header - updateContentFlags should
        // return false early (branch L88) but addHeader still puts the value in headers map
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 500);

        // Content-Length should NOT have been updated because headersSent was true
        // The contentLength field remains -1 (default) because updateContentFlags returned false
        Assertions.assertEquals(-1, resp.getContentLength());
        Assertions.assertFalse(resp.hasExplicitContentLength());
    }

    /**
     * updateContentFlags: Content-Type header path
     * Branch L92: equals Content-Length → false, enters L101 (Content-Type)
     */
    @Test
    public void testUpdateContentFlagsWithContentType() {
        // Setting Content-Type via addHeader should trigger the Content-Type branch
        resp.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html");
        // Content-Type is set via directlyPutHeader within writeContentTypeHeader
        // But updateContentFlags for Content-Type just sets hasExplicitContentType = true
        // The actual contentType field is set separately
        Assertions.assertTrue(resp.hasExplicitContentType());
    }

    /**
     * updateContentFlags: removeHeader path for Content-Length (isAdding=false)
     * Branch L93: isAdding → false → L97-98
     */
    @Test
    public void testUpdateContentFlagsRemoveContentLength() {
        // First add Content-Length to set it up
        resp.addHeader(HttpHeaderNames.CONTENT_LENGTH, 100);
        Assertions.assertEquals(100, resp.getContentLength());

        // Now remove the header - this calls updateContentFlags with isAdding=false
        resp.removeHeader(HttpHeaderNames.CONTENT_LENGTH);

        // After removal, contentLength should be 0 and hasExplicitContentLength should be false
        Assertions.assertEquals(0, resp.getContentLength());
        Assertions.assertFalse(resp.hasExplicitContentLength());
    }

    /**
     * updateContentFlags: removeHeader path for Content-Type (isAdding=false)
     * Branch L101-103: Content-Type with isAdding=false
     */
    @Test
    public void testUpdateContentFlagsRemoveContentType() {
        // First add Content-Type
        resp.addHeader(HttpHeaderNames.CONTENT_TYPE, "application/json");
        Assertions.assertTrue(resp.hasExplicitContentType());

        // Now remove the header
        resp.removeHeader(HttpHeaderNames.CONTENT_TYPE);

        // hasExplicitContentType should be false after removal
        Assertions.assertFalse(resp.hasExplicitContentType());
    }

    // ==================== getHeader uncovered branches ====================

    /**
     * getHeader: single-value header (String class)
     * Branch L131: value.getClass() == String.class → enters (String) cast
     */
    @Test
    public void testGetHeaderSingleValue() {
        resp.addHeader("X-Custom", "singleValue");
        String result = resp.getHeader("X-Custom");
        Assertions.assertEquals("singleValue", result);
    }

    /**
     * getHeader: non-existent header returns null
     */
    @Test
    public void testGetHeaderNonExistent() {
        String result = resp.getHeader("X-Nonexistent");
        Assertions.assertNull(result);
    }

    // ==================== removeHeader(String, Serializable) uncovered branches ====================

    /**
     * removeHeader(key, value) with non-existent key
     * Branch L164: existingValue == null → return early
     */
    @Test
    public void testRemoveHeaderKeyValueNonExistent() {
        // Should not throw any exception
        Assertions.assertDoesNotThrow(() -> resp.removeHeader("X-Nonexistent", "someValue"));
    }

    /**
     * removeHeader(key, value) with single String value that matches
     * Branch L168: String class → true, L169: equals matches → remove header
     */
    @Test
    public void testRemoveHeaderKeyValueStringMatch() {
        resp.addHeader("X-Custom", "valueToRemove");
        // The stored value is String type (single value)
        resp.removeHeader("X-Custom", "valueToRemove");
        // Header should be removed
        Assertions.assertNull(resp.getHeader("X-Custom"));
    }

    /**
     * removeHeader(key, value) with single String value that does NOT match
     * Branch L168: String class → true, L169: equals fails → skip removal
     */
    @Test
    public void testRemoveHeaderKeyValueStringNoMatch() {
        resp.addHeader("X-Custom", "actualValue");
        // Try to remove a different value
        resp.removeHeader("X-Custom", "differentValue");
        // Header should still exist with original value
        Assertions.assertEquals("actualValue", resp.getHeader("X-Custom"));
    }

    // ==================== setContentLength uncovered branches ====================

    /**
     * setContentLength with negative value
     * Branch L187: contentLength < 0 → throw IllegalArgumentException
     */
    @Test
    public void testSetContentLengthNegative() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> resp.setContentLength(-1));
    }

    /**
     * setContentLength with chunked=true
     * Branch L191: chunked || headersSent → true (chunked=true)
     */
    @Test
    public void testSetContentLengthWhenChunked() {
        resp.setChunked(true);
        // Setting content length when chunked should be silently ignored (return without setting)
        resp.setContentLength(100);
        // contentLength should remain -1 because chunked=true caused early return
        Assertions.assertEquals(-1, resp.getContentLength());
    }

    /**
     * setContentLength with headersSent=true
     * Branch L191: chunked || headersSent → true (headersSent=true)
     */
    @Test
    public void testSetContentLengthWhenHeadersSent() throws Exception {
        // Set headersSent to true via reflection
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);

        // Setting content length when headersSent should be silently ignored
        resp.setContentLength(200);
        // contentLength should remain -1
        Assertions.assertEquals(-1, resp.getContentLength());
    }

    // ==================== setChunked/removeChunkedEncoding with headersSent ====================

    /**
     * setChunked with headersSent=true → early return
     * Branch L214: if (headersSent) return;
     */
    @Test
    public void testSetChunkedWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);

        // Should not throw and should not change chunked state
        Assertions.assertDoesNotThrow(() -> resp.setChunked(true));
        Assertions.assertFalse(resp.isChunked());
    }

    /**
     * setChunkedEncoding with headersSent=true → early return
     * Branch L229: if (headersSent) return;
     */
    @Test
    public void testSetChunkedEncodingWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);

        Assertions.assertDoesNotThrow(() -> resp.setChunkedEncoding());
        Assertions.assertFalse(resp.isChunked());
    }

    /**
     * removeChunkedEncoding with headersSent=true → early return
     * Branch L243: if (headersSent) return;
     */
    @Test
    public void testRemoveChunkedEncodingWhenHeadersSent() throws Exception {
        resp.setChunked(true);
        Assertions.assertTrue(resp.isChunked());

        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);

        resp.removeChunkedEncoding();
        // chunked should still be true because removeChunkedEncoding returned early
        Assertions.assertTrue(resp.isChunked());
    }

    // ==================== isCorrupted uncovered branches ====================

    /**
     * isCorrupted when responseState is STATE_CORRUPTED
     * Branch L550: responseState == STATE_CORRUPTED → true
     * We need to set responseState via reflection since it's private
     */
    @Test
    public void testIsCorruptedTrue() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 2); // STATE_CORRUPTED = 2
        Assertions.assertTrue(resp.isCorrupted());
    }

    // ==================== HttpCompleteResponse constructors ====================

    // ==================== getHeader with multi-value (List) uncovered branches ====================

    /**
     * getHeader when value is a List (multi-value header)
     * This requires adding the same header key twice to trigger List creation
     * Branch L131: value.getClass() == String.class → false (it's a List)
     * Falls through to L135-136: returns first element
     */
    @Test
    public void testGetHeaderMultiValue() {
        resp.addHeader("X-Multi", "first");
        resp.addHeader("X-Multi", "second");
        // getHeader should return the first value
        String result = resp.getHeader("X-Multi");
        Assertions.assertEquals("first", result);
    }

    // ==================== flush() silentlyUnavailable branch ====================

    /**
     * flush() when isSilentlyUnavailable() returns true
     * Branch L411: isSilentlyUnavailable() → true → return silently
     */
    @Test
    public void testFlushWhenCompleted() throws Exception {
        // Set responseState to STATE_COMPLETED = 1
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 1);
        // Should not throw
        Assertions.assertDoesNotThrow(() -> resp.flush());
    }

    /**
     * flush() when response is corrupted
     */
    @Test
    public void testFlushWhenCorrupted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 2);
        Assertions.assertDoesNotThrow(() -> resp.flush());
    }

    // ==================== writeChunked silentlyUnavailable branch ====================

    /**
     * writeChunked when isSilentlyUnavailable() returns true
     * Branch L509: isSilentlyUnavailable() → true → return silently
     */
    @Test
    public void testWriteChunkedWhenCompleted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 1);
        Assertions.assertDoesNotThrow(() -> resp.writeChunked("test".getBytes()));
    }

    // ==================== write() null/empty check ====================

    /**
     * write() with null bytes
     * Branch L480: bytes == null → return early
     */
    @Test
    public void testWriteNullBytes() {
        Assertions.assertDoesNotThrow(() -> resp.write(null, 0, 10));
    }

    /**
     * write() with count <= 0
     * Branch L480: count <= 0 → return early
     */
    @Test
    public void testWriteZeroCount() {
        Assertions.assertDoesNotThrow(() -> resp.write(new byte[10], 0, 0));
    }

    // ==================== handover() test ====================

    /**
     * Test handover sets responseState to STATE_COMPLETED
     */
    @Test
    public void testHandover() throws Exception {
        resp.handover();
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        Assertions.assertEquals(1, stateField.get(resp)); // STATE_COMPLETED = 1
    }

    // ==================== write() large data path ====================

    /**
     * write() when data size exceeds BODY_MEMORY_THRESHOLD
     * Branch L484: Math.max(bodyBuf.size(), count) > BODY_MEMORY_THRESHOLD → true
     * This calls flush() which calls ensureHeadersSent → ctx.write → ctx.flush
     * We need to make flush() work by not having isSilentlyUnavailable return true
     */
    @Test
    public void testWriteLargeData() throws Exception {
        // Set body size large enough to trigger the threshold check
        // BODY_MEMORY_THRESHOLD = 65536 (check actual value)
        // We need count > BODY_MEMORY_THRESHOLD to trigger flush
        // Since flush calls ensureHeadersSent which calls ctx.write, mock those
        // Actually we can write enough data to exceed threshold
        int threshold = getBodyMemoryThreshold();
        byte[] largeData = new byte[threshold + 1];
        // This will trigger the threshold branch and call flush()
        // flush() calls ensureHeadersSent → ctx.write (which is mocked, does nothing)
        Assertions.assertDoesNotThrow(() -> resp.write(largeData, 0, largeData.length));
    }

    // ==================== earlyHints uncovered branches ====================

    /**
     * earlyHints when isSilentlyUnavailable() → return early
     * Branch L532: isSilentlyUnavailable() || headersSent → true
     */
    @Test
    public void testEarlyHintsWhenCompleted() throws Exception {
        java.lang.reflect.Field stateField = HttpDefaultResponse.class.getDeclaredField("responseState");
        stateField.setAccessible(true);
        stateField.set(resp, 1); // STATE_COMPLETED
        Assertions.assertDoesNotThrow(() -> resp.earlyHints("</style.css>; rel=preload"));
    }

    /**
     * earlyHints when headersSent → return early
     */
    @Test
    public void testEarlyHintsWhenHeadersSent() throws Exception {
        java.lang.reflect.Field headersSentField = HttpInternalResponse.class.getDeclaredField("headersSent");
        headersSentField.setAccessible(true);
        headersSentField.set(resp, true);
        Assertions.assertDoesNotThrow(() -> resp.earlyHints("</style.css>; rel=preload"));
    }

    // ==================== writeChunked with empty data ====================

    /**
     * writeChunked with null data
     * Branch L525: data != null && data.length > 0 → false for null
     */
    @Test
    public void testWriteChunkedNullData() throws Exception {
        resp.setChunked(true);
        // Mock ctx to avoid real I/O when ensureHeadersSent is called
        // Since writeChunked calls ensureHeadersSent → ctx.write → OK with mock
        Assertions.assertDoesNotThrow(() -> resp.writeChunked(null));
    }

    /**
     * writeChunked with empty data
     * Branch L525: data != null && data.length > 0 → false for empty
     */
    @Test
    public void testWriteChunkedEmptyData() throws Exception {
        resp.setChunked(true);
        Assertions.assertDoesNotThrow(() -> resp.writeChunked(new byte[0]));
    }

    // ==================== isSilentlyUnavailable private ====================
    // This is private so we can't test directly, but it's exercised by
    // flush() and writeChunked tests above.

    // ==================== Helper ====================

    /**
     * Get the BODY_MEMORY_THRESHOLD value via reflection
     */
    private int getBodyMemoryThreshold() throws Exception {
        java.lang.reflect.Field thresholdField = HttpConf.class.getDeclaredField("BODY_MEMORY_THRESHOLD");
        thresholdField.setAccessible(true);
        return thresholdField.getInt(null);
    }

    // ==================== sendFile / notFound ====================

    /**
     * sendFile null file -> notFound() -> 404 status.
     * Note: the real data-sending internals (sendFile0 / sendFileBuffered / compressAndSendFile)
     * rely on final ChannelContext methods (option/write/channel) bound to a live socket, so they
     * cannot be exercised by a Mockito mock or a stub subclass and require an integration test.
     */
    @Test
    public void testSendFileNullReturnsNotFound() throws Exception {
        resp.sendFile((java.io.File) null);
        Assertions.assertEquals(HttpStatus.NOT_FOUND, resp.getStatus());
    }

    /**
     * Minimal subclass of HttpDefaultResponse that exposes internal state for verification.
     */
    static class TestDefaultResp extends HttpDefaultResponse {

        TestDefaultResp(HttpRequest request, ChannelContext ctx) {
            super(request, ctx);
        }

        // Expose hasExplicitContentLength for verification
        boolean hasExplicitContentLength() {
            return hasExplicitContentLength;
        }

        boolean hasExplicitContentType() {
            return hasExplicitContentType;
        }
    }
}
