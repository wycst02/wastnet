package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2Response}.
 *
 * @author wangyc
 */
public class Http2ResponseTest {

    // ==================== Helpers ====================

    /**
     * Create a mock-based fixture for Http2Response tests.
     * Returns a real Http2Response with all dependencies mocked.
     */
    static class MockFixture {
        final ChannelContext ctx;
        final Http2ServerStream stream;
        final Http2Request request;
        final Http2Response response;

        MockFixture(ChannelContext ctx, Http2ServerStream stream, Http2Request request, Http2Response response) {
            this.ctx = ctx;
            this.stream = stream;
            this.request = request;
            this.response = response;
        }
    }

    private static MockFixture createMockFixture() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerStream stream = mock(Http2ServerStream.class);
        when(stream.sendChunkSize()).thenReturn(16384);
        when(stream.createFrameBuffer(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int capacity = inv.getArgument(0);
            return ByteBuffer.allocate(capacity);
        });
        try {
            when(stream.acquirePartialSendWindow(anyInt())).thenAnswer(inv -> (Integer) inv.getArgument(0));
        } catch (IOException e) {
            // never thrown on a mock
        }
        try {
            java.lang.reflect.Field f = Http2Stream.class.getDeclaredField("frameBuf");
            f.setAccessible(true);
            f.set(stream, HttpBuf.of(256));
        } catch (Exception ignored) {
        }
        // Http2Stream.ctx / reader are set by the real constructor; mock skips it, so inject them.
        try {
            // spy a real reader so final fields (e.g. flushPending) are initialized
            Http2ServerReader reader = spy(new Http2ServerReader());
            when(reader.indexOfValue(anyString())).thenReturn(-1);
            java.lang.reflect.Field cf = Http2Stream.class.getDeclaredField("ctx");
            cf.setAccessible(true);
            cf.set(stream, ctx);
            java.lang.reflect.Field rf = Http2Stream.class.getDeclaredField("reader");
            rf.setAccessible(true);
            rf.set(stream, reader);
        } catch (Exception ignored) {
        }
        Http2Request req = mock(Http2Request.class);
        when(req.stream()).thenReturn(stream);
        when(req.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        Http2Response resp = new Http2Response(req, stream, ctx);
        return new MockFixture(ctx, stream, req, resp);
    }

    /**
     * Capture the HPACK header block produced by the (mocked) stream and decode it
     * back into a header map. This asserts on the *result* (correctly encoded
     * headers) rather than on which internal write method was called, so the test
     * survives refactors of the sending path.
     */
    private static Map<String, Object> decodeSentHeaders(MockFixture f) throws Exception {
        // writeFullResponse (commit/complete path) calls the 3-arg overload, while the
        // streaming writeHeaders path calls the 2-arg overload; capture whichever fired.
        byte[] block;
        ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
        try {
            verify(f.stream, atLeastOnce()).writeHeadersFrame(cap.capture(), anyBoolean(), anyBoolean());
            block = cap.getValue();
        } catch (AssertionError mismatch) {
            verify(f.stream, atLeastOnce()).writeHeadersFrame(cap.capture(), anyBoolean());
            block = cap.getValue();
        }
        Map<String, Object> headers = new HashMap<>();
        new Http2HpackCodec().client().decodeTo(block, 0, block.length, headers);
        return headers;
    }

    // ==================== Reflection helper ====================

    private static final Object UNSAFE;

    static {
        try {
            Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get Unsafe instance", e);
        }
    }

    /**
     * Temporarily set a static final int field using Unsafe (works on JDK 8-23).
     * Returns the original value for restoration in finally block.
     */
    private static int setFinalStaticInt(Field field, int value) throws Exception {
        long offset = (long) UNSAFE.getClass().getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, field);
        Object base = UNSAFE.getClass().getMethod("staticFieldBase", Field.class).invoke(UNSAFE, field);
        int original = (int) UNSAFE.getClass().getMethod("getInt", Object.class, long.class).invoke(UNSAFE, base, offset);
        UNSAFE.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(UNSAFE, base, offset, value);
        return original;
    }

    /**
     * Temporarily set a static final boolean field using Unsafe (works on JDK 8-23).
     * Returns the original value for restoration in finally block.
     */
    private static boolean setFinalStaticBoolean(Field field, boolean value) throws Exception {
        long offset = (long) UNSAFE.getClass().getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, field);
        Object base = UNSAFE.getClass().getMethod("staticFieldBase", Field.class).invoke(UNSAFE, field);
        boolean original = (boolean) UNSAFE.getClass().getMethod("getBoolean", Object.class, long.class).invoke(UNSAFE, base, offset);
        UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class).invoke(UNSAFE, base, offset, value);
        return original;
    }

    // ==================== getHttpVersion ====================

    @Test
    void testGetHttpVersion() {
        assertEquals(HttpVersion.HTTP_2, createMockFixture().response.getHttpVersion());
    }

    // ==================== Header methods ====================

    @Test
    void testAddHeader() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("content-type", "text/html");
        assertEquals("text/html", resp.getHeader("content-type"));
    }

    @Test
    void testAddHeaderLowercasesKey() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("X-Custom", "val");
        // Both original case and lowercase lookups work because getHeader also lowercases
        assertEquals("val", resp.getHeader("X-Custom"));
        assertEquals("val", resp.getHeader("x-custom"));
    }

    @Test
    void testSetHeaderOverwrites() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("x-custom", "old");
        resp.setHeader("x-custom", "new");
        assertEquals("new", resp.getHeader("x-custom"));
    }

    @Test
    void testGetHeaderReturnsNullForMissing() {
        assertNull(createMockFixture().response.getHeader("nonexistent"));
    }

    @Test
    void testRemoveHeader() {
        MockFixture f = createMockFixture();
        f.response.addHeader("x-test", "value");
        f.response.removeHeader("x-test");
        assertNull(f.response.getHeader("x-test"));
    }

    @Test
    void testAddHeaderMultipleValuesSameKey() {
        Http2Response resp = createMockFixture().response;
        // first add: String stored directly (doAddHeader null branch)
        resp.addHeader("x-multi", "v1");
        // second add: String -> List merge branch (doAddHeader l84-88)
        resp.addHeader("x-multi", "v2");
        // getHeader returns the first value when multiple values exist (List branch l97-98)
        assertEquals("v1", resp.getHeader("x-multi"));
        // third add: List append branch (doAddHeader l89-90)
        resp.addHeader("x-multi", "v3");
        assertEquals("v1", resp.getHeader("x-multi"));
    }

    @Test
    void testGetHeadersSingleValue() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("x-single", "v1");
        List<String> values = resp.getHeaders("x-single");
        assertNotNull(values);
        assertEquals(1, values.size());
        assertEquals("v1", values.get(0));
    }

    @Test
    void testGetHeadersMultipleValues() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("x-multi", "v1");
        resp.addHeader("x-multi", "v2");
        List<String> values = resp.getHeaders("x-multi");
        assertNotNull(values);
        assertEquals(2, values.size());
        assertEquals("v1", values.get(0));
        assertEquals("v2", values.get(1));
    }

    @Test
    void testGetHeadersNotExists() {
        Http2Response resp = createMockFixture().response;
        assertNull(resp.getHeaders("nonexistent"));
    }

    @Test
    void testGetHeaderNames() {
        Http2Response resp = createMockFixture().response;
        resp.addHeader("x-a", "v1");
        resp.addHeader("x-b", "v2");
        Set<String> names = resp.getHeaderNames();
        assertNotNull(names);
        assertTrue(names.contains("x-a"));
        assertTrue(names.contains("x-b"));
    }

    // ==================== Status / Content ====================

    @Test
    void testDefaultStatusIsOk() {
        assertEquals(200, createMockFixture().response.getStatus().code);
    }

    @Test
    void testSetStatus() {
        Http2Response resp = createMockFixture().response;
        resp.status(404);
        assertEquals(404, resp.getStatus().code);
    }

    @Test
    void testSetContentType() {
        Http2Response resp = createMockFixture().response;
        resp.setContentType("application/json");
        assertEquals("application/json", resp.getContentType());
    }

    @Test
    void testSetContentLength() {
        Http2Response resp = createMockFixture().response;
        resp.setContentLength(1024);
        assertEquals(1024, resp.getContentLength());
    }

    @Test
    void testBodySetsContent() {
        Http2Response resp = createMockFixture().response;
        resp.body("test_content".getBytes());
        assertEquals(200, resp.getStatus().code);
    }

    // ==================== write ====================

    @Test
    void testWriteSmallDataBuffersAndSendsHeaders() throws Exception {
        MockFixture f = createMockFixture();
        byte[] data = "hello".getBytes();
        f.response.write(data, 0, data.length);
        // Headers must be encoded and sent; assert on the decoded result, not the call path.
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    @Test
    void testWriteAlreadyCommittedIsNoOp() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        byte[] data = "world".getBytes();
        f.response.write(data, 0, data.length);
        // Headers sent exactly once (from commit); writing after commit is a no-op.
        verify(f.stream, times(1)).writeHeadersFrame(any(byte[].class), anyBoolean(), anyBoolean());
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    @Test
    void testWriteZeroLengthIsNoOp() {
        Http2Response resp = createMockFixture().response;
        assertDoesNotThrow(() -> resp.write(new byte[10], 0, 0));
    }

    // ==================== flush ====================

    @Test
    void testFlushSendsBufferedData() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("data".getBytes());
        f.response.flush();
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class), anyBoolean());
    }

    @Test
    void testFlushAfterCommitIsNoOp() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        f.response.flush();
        // commit() should be the terminal operation
    }

    // ==================== commit ====================

    @Test
    void testCommitSendsHeadersAndBody() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("payload".getBytes());
        f.response.commit();
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    @Test
    void testCommitMultipleTimesIsIdempotent() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        // Second commit should not throw
        f.response.commit();
        assertFalse(f.response.isCorrupted());
    }

    @Test
    void testCommitWithoutBodySendsEndStream() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    // ==================== handover ====================

    @Test
    void testHandover() {
        MockFixture f = createMockFixture();
        f.response.handover();
        assertFalse(f.response.isCorrupted());
    }

    @Test
    void testHandoverMarksStream() {
        MockFixture f = createMockFixture();
        f.response.handover();
        verify(f.stream).handover();
    }

    // ==================== notFound ====================

    @Test
    void testNotFound() throws Exception {
        MockFixture f = createMockFixture();
        f.response.notFound();
        assertEquals(404, f.response.getStatus().code);
        assertEquals("404", decodeSentHeaders(f).get(":status"));
    }

    // ==================== addCacheHeaders ====================

    @Test
    void testAddCacheHeaders() throws Exception {
        MockFixture f = createMockFixture();
        f.response.addCacheHeaders(1024, 123456789L);
        assertNotNull(f.response.getHeader("last-modified"));
        assertNotNull(f.response.getHeader("etag"));
    }

    // ==================== earlyHints ====================

    @Test
    void testEarlyHints() throws Exception {
        MockFixture f = createMockFixture();
        f.response.earlyHints("</style.css>; rel=preload");
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class));
    }

    @Test
    void testEarlyHintsAfterHeadersSentIsNoOp() throws Exception {
        MockFixture f = createMockFixture();
        f.response.commit();
        f.response.earlyHints("</script.js>; rel=preload");
        // Headers sent exactly once (from commit); earlyHints after headersSent is a no-op.
        verify(f.stream, times(1)).writeHeadersFrame(any(byte[].class), anyBoolean(), anyBoolean());
        verify(f.stream, never()).writeFrame(any(ByteBuffer.class), anyBoolean());
    }

    // ==================== isChunked ====================

    @Test
    void testIsChunked() {
        assertFalse(createMockFixture().response.isChunked());
    }

    // ==================== sendFile0 (via mock) ====================

    @Test
    void testSendFile0SmallFile() throws Exception {
        MockFixture f = createMockFixture();
        java.io.File tmp = java.io.File.createTempFile("h2test", ".txt");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write("small content".getBytes());
            fos.close();
            f.response.sendFile0(tmp, tmp.length(), "text/plain", false);
            assertEquals("200", decodeSentHeaders(f).get(":status"));
        } finally {
            tmp.delete();
        }
    }

    @Test
    void testSendFile0WithGzip() throws Exception {
        MockFixture f = createMockFixture();
        java.io.File tmp = java.io.File.createTempFile("h2gzip", ".txt");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write("compressible content".getBytes());
            fos.close();
            f.response.sendFile0(tmp, tmp.length(), "text/plain", true);
            assertEquals("200", decodeSentHeaders(f).get(":status"));
        } finally {
            tmp.delete();
        }
    }

    // ==================== writeStatus branches ====================

    @Test
    void testWriteStatus200() throws Exception {
        MockFixture f = createMockFixture();
        // Default status is 200, writeStatus uses Indexed Header Field (A)
        f.response.commit();
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    @Test
    void testWriteStatus500() throws Exception {
        MockFixture f = createMockFixture();
        f.response.status(500);
        f.response.commit();
        assertEquals("500", decodeSentHeaders(f).get(":status"));
    }

    @Test
    void testServerHeaderExposed() throws Exception {
        Field exposeField = HttpConf.class.getDeclaredField("EXPOSE_SERVER_HEADER");
        exposeField.setAccessible(true);
        boolean origExpose = setFinalStaticBoolean(exposeField, true);
        try {
            MockFixture f = createMockFixture();
            f.response.commit();
            Map<String, Object> headers = decodeSentHeaders(f);
            assertTrue(headers.containsKey("server"), "server header should be exposed");
        } finally {
            setFinalStaticBoolean(exposeField, origExpose);
        }
    }

    @Test
    void testWriteStatusUncommonCode() throws Exception {
        // Non-200/500 status → fallback to Never Indexed (D) + inline value
        MockFixture f = createMockFixture();
        f.response.status(302);
        f.response.commit();
        assertEquals("302", decodeSentHeaders(f).get(":status"));
    }

    // ==================== encodeHeaders branches ====================

    @Test
    void testEncodeHeadersWithContentType() throws Exception {
        MockFixture f = createMockFixture();
        f.response.setContentType("text/plain");
        f.response.commit();
        assertEquals("text/plain", decodeSentHeaders(f).get("content-type"));
    }

    @Test
    void testEncodeHeadersWithContentLength() throws Exception {
        // commit() derives content-length from the buffered body, so write a 200-byte body
        MockFixture f = createMockFixture();
        f.response.body(new byte[200]);
        f.response.commit();
        assertEquals("200", decodeSentHeaders(f).get("content-length"));
    }

    @Test
    void testEncodeHeadersWithCustomHeaders() throws Exception {
        MockFixture f = createMockFixture();
        f.response.addHeader("x-custom", "value1");
        f.response.addHeader("x-another", "value2");
        f.response.commit();
        Map<String, Object> headers = decodeSentHeaders(f);
        assertEquals("value1", headers.get("x-custom"));
        assertEquals("value2", headers.get("x-another"));
    }

    @Test
    void testEncodeHeadersSkipsContentTypeAndLengthFromH2Headers() throws Exception {
        MockFixture f = createMockFixture();
        // content-type/content-length added via addHeader go into h2Headers and must be skipped
        f.response.addHeader("content-type", "application/json");
        f.response.addHeader("content-length", "500");
        f.response.commit();
        Map<String, Object> headers = decodeSentHeaders(f);
        assertEquals("200", headers.get(":status"));
        assertNull(headers.get("content-type"));
        assertNull(headers.get("content-length"));
    }

    // ==================== writeHeadersFrame multi-frame ====================

    @Test
    void testWriteHeadersFrameMultiFrame() throws Exception {
        // Use doCallRealMethod so the actual split logic in Http2Stream.writeHeadersFrame runs
        MockFixture f = createMockFixture();
        when(f.stream.sendChunkSize()).thenReturn(8);
        doCallRealMethod().when(f.stream).writeHeadersFrame(any(byte[].class), anyBoolean(), anyBoolean());

        // Add enough custom headers to exceed 8 bytes of HPACK payload
        for (int i = 0; i < 10; ++i) {
            f.response.addHeader("x-h" + i, "val" + i);
        }
        f.response.commit();

        // writeHeadersFrame splits into HEADERS + CONTINUATION (multi-frame path uses writeFrames)
        verify(f.stream).writeFrames(argThat(frames -> frames.size() >= 2));
    }

    @Test
    void testEncodeHeadersWithAllBranches() throws Exception {
        MockFixture f = createMockFixture();
        f.response.setContentType("application/json");
        f.response.body(new byte[300]);
        f.response.addHeader("x-custom", "test");
        f.response.addHeader("x-date-provided", "yes");
        f.response.commit();
        Map<String, Object> headers = decodeSentHeaders(f);
        assertEquals("application/json", headers.get("content-type"));
        assertEquals("300", headers.get("content-length"));
        assertEquals("test", headers.get("x-custom"));
        assertEquals("yes", headers.get("x-date-provided"));
    }

    // ==================== sendChunkedData multi-chunk ====================

    @Test
    void testSendMultiChunkDataViaCommit() throws Exception {
        MockFixture f = createMockFixture();
        // Body larger than chunkSize (16384) triggers multi-chunk in sendChunkedData
        byte[] largeBody = new byte[40000];
        for (int i = 0; i < largeBody.length; ++i) largeBody[i] = (byte) (i & 0xFF);
        f.response.body(largeBody);
        f.response.commit();
        // Should call writeFrame at least twice (3 chunks: 16384 + 16384 + 7232)
        verify(f.stream, atLeast(2)).writeFrame(any(ByteBuffer.class), anyBoolean());
    }

    @Test
    void testWriteLargeDataExceedingThreshold() throws Exception {
        // Temporarily lower BODY_MEMORY_THRESHOLD so we can test the large data path
        // without sending hundreds of KB of data
        Field thresholdField = HttpConf.class.getDeclaredField("BODY_MEMORY_THRESHOLD");
        thresholdField.setAccessible(true);
        int original = setFinalStaticInt(thresholdField, 16);
        try {
            MockFixture f = createMockFixture();
            byte[] initial = "helloworld".getBytes();
            f.response.write(initial, 0, initial.length);
            // bodyBuf now has 10 bytes
            // Write 10 more bytes - total would be 20 > 16 → triggers threshold path
            byte[] more = "bufdatahere".getBytes();
            f.response.write(more, 0, more.length);
            verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class), anyBoolean());
        } finally {
            setFinalStaticInt(thresholdField, original);
        }
    }

    // ==================== Auto GZIP via complete() ====================

    @Test
    void testAutoGzipTriggersDoSendCompressedResponse() throws Exception {
        // Need to mock request.getHeader("accept-encoding", true) for isGzipSupported()
        Http2ServerStream stream = mock(Http2ServerStream.class);
        when(stream.sendChunkSize()).thenReturn(16384);
        when(stream.createFrameBuffer(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int capacity = inv.getArgument(0);
            return ByteBuffer.allocate(capacity);
        });
        try {
            when(stream.acquirePartialSendWindow(anyInt())).thenAnswer(inv -> (Integer) inv.getArgument(0));
        } catch (IOException e) {
            // never thrown on a mock
        }
        try {
            java.lang.reflect.Field f = Http2Stream.class.getDeclaredField("frameBuf");
            f.setAccessible(true);
            f.set(stream, HttpBuf.of(256));
        } catch (Exception ignored) {
        }
        // Http2Stream.ctx / reader are set by the real constructor; mock skips it, so inject them.
        try {
            ChannelContext gzipCtx = mock(ChannelContext.class);
            Http2ServerReader reader = spy(new Http2ServerReader());
            when(reader.indexOfValue(anyString())).thenReturn(-1);
            java.lang.reflect.Field cf = Http2Stream.class.getDeclaredField("ctx");
            cf.setAccessible(true);
            cf.set(stream, gzipCtx);
            java.lang.reflect.Field rf = Http2Stream.class.getDeclaredField("reader");
            rf.setAccessible(true);
            rf.set(stream, reader);
        } catch (Exception ignored) {
        }
        Http2Request req = mock(Http2Request.class);
        when(req.stream()).thenReturn(stream);
        when(req.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        // Return "gzip" for Accept-Encoding check
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("gzip");

        Http2Response resp = new Http2Response(req, stream, mock(ChannelContext.class));

        // Temporarily enable GZIP and set min size to 1
        Field gzipField = HttpConf.class.getDeclaredField("GZIP");
        Field minSizeField = HttpConf.class.getDeclaredField("GZIP_MIN_SIZE");
        gzipField.setAccessible(true);
        minSizeField.setAccessible(true);
        boolean origGzip = setFinalStaticBoolean(gzipField, true);
        int origMinSize = setFinalStaticInt(minSizeField, 1);
        try {
            // Write enough body data to trigger GZIP (size >= 1)
            resp.body("compress_me".getBytes());
            resp.complete();
            // Auto GZIP should have sent compressed data via writeFrame
            verify(stream, atLeastOnce()).writeFrame(any(ByteBuffer.class), anyBoolean());
        } finally {
            setFinalStaticBoolean(gzipField, origGzip);
            setFinalStaticInt(minSizeField, origMinSize);
        }
    }

    @Test
    void testAutoGzipSkippedWhenGzipDisabled() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("test".getBytes());
        // GZIP is disabled by default, complete() should fall through to commit()
        f.response.complete();
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    // ==================== doStreamingCompressAndSend (sendFile with compression) ====================

    @Test
    void testSendFileCompressSmallFile() throws Exception {
        // Enable GZIP and set small threshold
        Field gzipField = HttpConf.class.getDeclaredField("GZIP");
        Field minSizeField = HttpConf.class.getDeclaredField("GZIP_MIN_SIZE");
        Field thresholdField = HttpConf.class.getDeclaredField("BODY_MEMORY_THRESHOLD");
        gzipField.setAccessible(true);
        minSizeField.setAccessible(true);
        thresholdField.setAccessible(true);
        boolean origGzip = setFinalStaticBoolean(gzipField, true);
        int origMinSize = setFinalStaticInt(minSizeField, 1);
        int origThreshold = setFinalStaticInt(thresholdField, 1024 * 1024);
        try {
            MockFixture f = createMockFixture();
            // Mock Accept-Encoding header
            when(f.request.getHeader(anyString(), anyBoolean())).thenReturn("gzip");

            File tempFile = File.createTempFile("test-", ".html");
            try {
                byte[] content = "Hello World, this is compressible data! ".getBytes();
                java.nio.file.Files.write(tempFile.toPath(), content);
                f.response.sendFile(tempFile, true, -1);
                // Small file (< BODY_MEMORY_THRESHOLD) → in-memory GZIP → doSendCompressedResponse → writeFrame
                verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class), anyBoolean());
            } finally {
                tempFile.delete();
            }
        } finally {
            setFinalStaticBoolean(gzipField, origGzip);
            setFinalStaticInt(minSizeField, origMinSize);
            setFinalStaticInt(thresholdField, origThreshold);
        }
    }

    @Test
    void testSendFileCompressLargeFile() throws Exception {
        // Enable GZIP and lower threshold to force streaming path
        Field gzipField = HttpConf.class.getDeclaredField("GZIP");
        Field minSizeField = HttpConf.class.getDeclaredField("GZIP_MIN_SIZE");
        Field thresholdField = HttpConf.class.getDeclaredField("BODY_MEMORY_THRESHOLD");
        gzipField.setAccessible(true);
        minSizeField.setAccessible(true);
        thresholdField.setAccessible(true);
        boolean origGzip = setFinalStaticBoolean(gzipField, true);
        int origMinSize = setFinalStaticInt(minSizeField, 1);
        int origThreshold = setFinalStaticInt(thresholdField, 16); // file > 16 bytes triggers streaming
        try {
            Http2ServerStream stream = mock(Http2ServerStream.class);
            when(stream.sendChunkSize()).thenReturn(16384);
            when(stream.createFrameBuffer(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
                int capacity = inv.getArgument(0);
                return ByteBuffer.allocate(capacity);
            });
            doNothing().when(stream).writeFrame(any(ByteBuffer.class), anyBoolean());
            doNothing().when(stream).writeDataFrame(any(ByteBuffer.class), anyInt());
            try {
                java.lang.reflect.Field f = Http2Stream.class.getDeclaredField("frameBuf");
                f.setAccessible(true);
                f.set(stream, HttpBuf.of(256));
            } catch (Exception ignored) {
            }
            Http2Request req = mock(Http2Request.class);
            when(req.stream()).thenReturn(stream);
            when(req.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
            when(req.getHeader(anyString(), anyBoolean())).thenReturn("gzip");

            Http2Response resp = new Http2Response(req, stream, mock(ChannelContext.class));

            File tempFile = File.createTempFile("test-", ".html");
            try {
                byte[] content = "This is a larger file that should trigger streaming GZIP compression path in Http2Response!".getBytes();
                java.nio.file.Files.write(tempFile.toPath(), content);
                resp.sendFile(tempFile, true, -1);
                // Large file → streaming GZIP → writeFrame (headers) + writeDataFrame (compressed data)
                verify(stream, atLeastOnce()).writeDataFrame(any(ByteBuffer.class), anyInt());
            } finally {
                tempFile.delete();
            }
        } finally {
            setFinalStaticBoolean(gzipField, origGzip);
            setFinalStaticInt(minSizeField, origMinSize);
            setFinalStaticInt(thresholdField, origThreshold);
        }
    }

    @Test
    void testAutoGzipSkippedWhenBodyTooSmall() throws Exception {
        // With GZIP enabled but body < GZIP_MIN_SIZE (default 2048), auto-gzip is skipped
        Field gzipField = HttpConf.class.getDeclaredField("GZIP");
        gzipField.setAccessible(true);
        boolean origGzip = setFinalStaticBoolean(gzipField, true);
        try {
            MockFixture f = createMockFixture();
            when(f.request.getHeader(anyString(), anyBoolean())).thenReturn("gzip");
            f.response.body("tiny".getBytes()); // 4 bytes < 2048 GZIP_MIN_SIZE
            f.response.complete();
            assertEquals("200", decodeSentHeaders(f).get(":status"));
        } finally {
            setFinalStaticBoolean(gzipField, origGzip);
        }
    }
}
