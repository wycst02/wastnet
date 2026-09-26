package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpHeaderNames;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
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

    /**
     * Returns a real {@link ChannelContext} bound to a {@link NioConfig} that mirrors the
     * live {@link HttpConf} values, so tests that reflectively tweak HttpConf still behave
     * as before the config-isolation refactor. (Mockito cannot stub {@code ChannelContext#option}
     * because it is final, so we use a real context + real NioConfig overrides instead.)
     */
    private static ChannelContext mockChannelContext() {
        return mockChannelContext(HttpConf.EXPOSE_SERVER_HEADER);
    }

    private static ChannelContext mockChannelContext(boolean exposeServerHeader) {
        ChannelContext ctx;
        try {
            java.lang.reflect.Constructor<ChannelContext> ctor =
                    ChannelContext.class.getDeclaredConstructor(long.class, java.nio.channels.SocketChannel.class, int.class);
            ctor.setAccessible(true);
            ctx = ctor.newInstance(0L, null, 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        NioConfig nioConfig = new NioConfig();
        nioConfig.option(HttpOptions.EXPOSE_SERVER_HEADER, exposeServerHeader);
        nioConfig.option(HttpOptions.BODY_MEMORY_THRESHOLD, HttpConf.BODY_MEMORY_THRESHOLD);
        nioConfig.option(HttpOptions.GZIP, HttpConf.GZIP);
        nioConfig.option(HttpOptions.GZIP_MIN_SIZE, HttpConf.GZIP_MIN_SIZE);
        ctx.attachNioConfig(nioConfig);
        return ctx;
    }

    /**
     * Build a real {@link ChannelContext} whose {@link NioConfig} starts from the live
     * HttpConf defaults and applies the given overrides. Used to cover per-context config
     * branches without any reflective/Unsafe mutation of HttpConf static fields.
     */
    private static ChannelContext mockChannelContext(NioConfig overrides) {
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
        if (overrides != null) {
            nioConfig.option(HttpOptions.EXPOSE_SERVER_HEADER, overrides.option(HttpOptions.EXPOSE_SERVER_HEADER));
            nioConfig.option(HttpOptions.BODY_MEMORY_THRESHOLD, overrides.option(HttpOptions.BODY_MEMORY_THRESHOLD));
            nioConfig.option(HttpOptions.GZIP, overrides.option(HttpOptions.GZIP));
            nioConfig.option(HttpOptions.GZIP_MIN_SIZE, overrides.option(HttpOptions.GZIP_MIN_SIZE));
        }
        ctx.attachNioConfig(nioConfig);
        return ctx;
    }

    private static MockFixture createMockFixture() {
        return createMockFixture(mockChannelContext());
    }

    private static MockFixture createMockFixture(ChannelContext ctx) {
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
        // frameBuf is package-private and non-final: assign directly (same package).
        stream.frameBuf = HttpBuf.of(256);
        // ctx / reader are final on Http2Stream, so they can only be injected via reflection
        // (setAccessible + Field.set works at runtime on the mock). The mocked stream otherwise
        // has null ctx/reader, which breaks synchronized(stream.ctx) blocks in the response path.
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
        // second add: String -> List merge branch (doAddHeader )
        resp.addHeader("x-multi", "v2");
        // getHeader returns the first value when multiple values exist (List branch )
        assertEquals("v1", resp.getHeader("x-multi"));
        // third add: List append branch (doAddHeader )
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
        File tmp = File.createTempFile("h2test", ".txt");
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
        File tmp = File.createTempFile("h2gzip", ".txt");
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
        MockFixture f = createMockFixture(mockChannelContext(true));
        f.response.commit();
        Map<String, Object> headers = decodeSentHeaders(f);
        assertTrue(headers.containsKey("server"), "server header should be exposed");
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
    void testEncodeHeadersContentLengthSkippedContentTypeRetainedFromH2Headers() throws Exception {
        MockFixture f = createMockFixture();
        // content-length added via addHeader goes into h2Headers and is skipped (managed header);
        // content-type added via addHeader is retained (only setContentType drives the managed header).
        f.response.addHeader("content-type", "application/json");
        f.response.addHeader("content-length", "500");
        f.response.commit();
        Map<String, Object> headers = decodeSentHeaders(f);
        assertEquals("200", headers.get(":status"));
        assertEquals("application/json", headers.get("content-type"));
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
    void testWriteLargeDataExceedingThreshold() throws IOException {
        // Lower BODY_MEMORY_THRESHOLD via per-context config so we can test the large
        // data path without sending hundreds of KB of data.
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.BODY_MEMORY_THRESHOLD, 16);
        MockFixture f = createMockFixture(mockChannelContext(cfg));
        byte[] initial = "helloworld".getBytes();
        f.response.write(initial, 0, initial.length);
        // bodyBuf now has 10 bytes
        // Write 10 more bytes - total would be 20 > 16 → triggers threshold path
        byte[] more = "bufdatahere".getBytes();
        f.response.write(more, 0, more.length);
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class), anyBoolean());
    }

    // ==================== Auto GZIP via complete() ====================

    @Test
    void testAutoGzipTriggersDoSendCompressedResponse() throws Exception {
        // Enable GZIP and set min size to 1 via per-context config so the real
        // ChannelContext reports gzipEnabled()==true and gzipMinSize()==1.
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.GZIP, true);
        cfg.option(HttpOptions.GZIP_MIN_SIZE, 1);
        MockFixture f = createMockFixture(mockChannelContext(cfg));
        // Return "gzip" for Accept-Encoding check
        when(f.request.getHeader(anyString(), anyBoolean())).thenReturn("gzip");

        // Write enough body data to trigger GZIP (size >= 1)
        f.response.body("compress_me".getBytes());
        f.response.complete();
        // Auto GZIP should have sent compressed data via writeFrame
        verify(f.stream, atLeastOnce()).writeFrame(any(ByteBuffer.class), anyBoolean());
    }

    @Test
    void testAutoGzipSkippedWhenGzipDisabled() throws Exception {
        MockFixture f = createMockFixture();
        f.response.body("test".getBytes());
        // GZIP is disabled by default, complete() should fall through to commit()
        f.response.complete();
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }

    @Test
    void testResetInternalViaCompleteWhenAutoCommitFalse() throws Exception {
        MockFixture f = createMockFixture();
        // h2Headers populated so resetInternal's h2Headers.clear() has something to clear
        f.response.addHeader("x-test", "value");
        assertNotNull(f.response.getHeader("x-test"));
        // handover() flips autoCommit to false; complete() then takes the flush()+resetInternal() branch
        f.response.handover();
        f.response.complete();
        // h2Headers cleared by resetInternal (super.resetInternal clears bodyBuf, subclass clears h2Headers)
        assertNull(f.response.getHeader("x-test"));
    }

    @Test
    void testBuildStaticHeaderBlockEncodesMultiValueHeader() throws Exception {
        MockFixture f = createMockFixture();
        // Adding the same header twice converts the value from String to List<String>
        // (doAddHeader ), which drives buildStaticHeaderBlock into its multi-value
        // branch  instead of the plain String branch.
        // Short name/values stay under the 5-byte Huffman threshold, so both entries are
        // written verbatim into the HPACK block and can be asserted on.
        f.response.addHeader("xmv", "p");
        f.response.addHeader("xmv", "q");
        assertEquals(Arrays.asList("p", "q"), f.response.getHeaders("xmv"));

        // commit() -> writeFullResponse() -> buildStaticHeaderBlock()
        f.response.commit();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(f.stream).writeHeadersFrame(captor.capture(), anyBoolean(), anyBoolean());
        String block = new String(captor.getValue(), StandardCharsets.ISO_8859_1);
        int occurrences = 0;
        for (int idx = block.indexOf("xmv"); idx >= 0; idx = block.indexOf("xmv", idx + 3)) {
            occurrences++;
        }
        // the header name is emitted once per value -> proves the List branch ran
        assertEquals(2, occurrences, "multi-value header must be encoded once per value");
    }

    @Test
    void testBuildStaticHeaderBlockKeepsCustomServerHeader() throws Exception {
        // EXPOSE_SERVER_HEADER=true combined with an application-supplied Server header makes the
        // second half of the  condition short-circuit -- the last uncovered branch of
        // buildStaticHeaderBlock (the framework default must then NOT be appended).
        // "zsrv" is <= 5 bytes so it is written verbatim (Huffman only kicks in above 5 bytes).
        MockFixture f = createMockFixture(mockChannelContext(true));
        f.response.addHeader(HttpHeaderNames.SERVER, "zsrv");
        f.response.commit();

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(f.stream).writeHeadersFrame(captor.capture(), anyBoolean(), anyBoolean());
        byte[] block = captor.getValue();
        assertTrue(new String(block, StandardCharsets.ISO_8859_1).contains("zsrv"),
                "application Server header must be encoded");
        // the framework default is 13 bytes -> Huffman encoded, so match its encoded form
        byte[] defaultEncoded = HuffmanByteCodec.encodeData(
                Http2Response.SERVER_VALUE.getBytes(StandardCharsets.UTF_8));
        assertFalse(indexOfBytes(block, defaultEncoded) >= 0,
                "framework Server value must not be sent when the app already set one");
    }

    /** Returns the index of {@code pattern} within {@code data}, or -1 when absent. */
    private static int indexOfBytes(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ==================== doStreamingCompressAndSend (sendFile with compression) ====================

    @Test
    void testSendFileCompressSmallFile() throws Exception {
        // Enable GZIP and set small threshold via per-context config
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.GZIP, true);
        cfg.option(HttpOptions.GZIP_MIN_SIZE, 1);
        cfg.option(HttpOptions.BODY_MEMORY_THRESHOLD, 1024 * 1024);
        MockFixture f = createMockFixture(mockChannelContext(cfg));
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
    }

    @Test
    void testSendFileCompressLargeFile() throws Exception {
        // Enable GZIP and lower threshold to force streaming path via per-context config
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.GZIP, true);
        cfg.option(HttpOptions.GZIP_MIN_SIZE, 1);
        cfg.option(HttpOptions.BODY_MEMORY_THRESHOLD, 16); // file > 16 bytes triggers streaming
        MockFixture f = createMockFixture(mockChannelContext(cfg));
        when(f.request.getHeader(anyString(), anyBoolean())).thenReturn("gzip");

        File tempFile = File.createTempFile("test-", ".html");
        try {
            byte[] content = "This is a larger file that should trigger streaming GZIP compression path in Http2Response!".getBytes();
            java.nio.file.Files.write(tempFile.toPath(), content);
            f.response.sendFile(tempFile, true, -1);
            // Large file → streaming GZIP → writeFrame (headers) + writeDataFrame (compressed data)
            verify(f.stream, atLeastOnce()).writeDataFrame(any(ByteBuffer.class), anyInt());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    void testAutoGzipSkippedWhenBodyTooSmall() throws Exception {
        // With GZIP enabled but body < GZIP_MIN_SIZE (default 2048), auto-gzip is skipped
        NioConfig cfg = new NioConfig();
        cfg.option(HttpOptions.GZIP, true);
        MockFixture f = createMockFixture(mockChannelContext(cfg));
        when(f.request.getHeader(anyString(), anyBoolean())).thenReturn("gzip");
        f.response.body("tiny".getBytes()); // 4 bytes < 2048 GZIP_MIN_SIZE
        f.response.complete();
        assertEquals("200", decodeSentHeaders(f).get(":status"));
    }
}
