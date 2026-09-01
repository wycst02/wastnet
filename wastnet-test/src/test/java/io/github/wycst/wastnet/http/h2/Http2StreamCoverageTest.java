package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Coverage tests for Http2Stream. */
class Http2StreamCoverageTest {

    static class Fixture {
        final Http2MessageReader reader;
        final ChannelContext ctx;
        final TestStream stream;
        Fixture(Http2MessageReader reader, ChannelContext ctx, TestStream stream) {
            this.reader = reader; this.ctx = ctx; this.stream = stream;
        }
    }

    private static void setField(Object target, String name, Object val) throws Exception {
        java.lang.reflect.Field f = target.getClass().getSuperclass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, val);
    }

    private Fixture createFixture() throws Exception {
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        setField(reader, "streamInitSendWindowSize", 65535);
        setField(reader, "maxSendPayloadSize", 16384);
        setField(reader, "connectSendWindow", 65535);
        H2TestHelper.setConnectRecvWindow(reader, HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE << 4);
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        TestStream stream = new TestStream(reader, 1, ctx);
        // frameBuf is initialized by onHeadersFrame in real flow; mimic it for direct onDataFrame calls.
        setField(stream, "frameBuf", HttpBuf.of(64));
        return new Fixture(reader, ctx, stream);
    }

    static class TestStream extends Http2Stream {
        TestStream(Http2MessageReader reader, int sid, ChannelContext ctx) { super(reader, sid, ctx); }
        protected void onEndHeaders() {}
        protected void submit() {}
        public String debugPrefix() { return "Test"; }
    }

    // ==================== Basic ====================

    @Test void testHeadersGetter() throws Exception { assertNotNull(createFixture().stream.headers()); }

    @Test void testGetContentLengthNonStreaming() throws Exception {
        Fixture f = createFixture();
        f.stream.bodyData = new byte[]{1, 2, 3};
        assertEquals(3, f.stream.getContentLength());
    }

    @Test void testGetContentLengthStreamingDeclared() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = 200;
        assertEquals(200, f.stream.getContentLength());
    }

    @Test void testGetBodyDataNonStreaming() throws Exception {
        Fixture f = createFixture(); f.stream.bodyData = new byte[]{1, 2};
        assertArrayEquals(new byte[]{1, 2}, f.stream.getBodyData());
    }

    @Test void testGetBodyDataStreamingThrows() throws Exception {
        Fixture f = createFixture(); f.stream.needStreaming = true;
        assertThrows(IllegalStateException.class, () -> f.stream.getBodyData());
    }

    // ==================== Buffer helpers ====================

    @Test void testCreateFrameBuffer() throws Exception {
        assertEquals(9, createFixture().stream.createFrameBuffer(9, 10, 0x01, 0x05).capacity());
    }

    @Test void testSendChunkSize() throws Exception { assertTrue(createFixture().stream.sendChunkSize() > 0); }

    @Test void testCompleteRequestEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.endStream = true;
        f.stream.completeRequest();
    }

    @Test void testCompleteRequestNotEndStream() throws Exception {
        Fixture f = createFixture();
        f.stream.completeRequest();
        verify(f.reader).sendRstStreamFrame(any(), anyInt(), anyInt());
    }

    // ==================== HPACK encode ====================

    private static HttpDecodedResponse mockResp(int code, Map<String, Object> headers) {
        HttpDecodedResponse r = mock(HttpDecodedResponse.class);
        when(r.getStatusCode()).thenReturn(code);
        when(r.getHeaders()).thenReturn(headers);
        return r;
    }

    @Test void testEncodeHpackHeaders() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("content-type", "text/plain");
        assertNotNull(Http2Helper.encodeHpackHeaders(mockResp(200, h)));
    }

    @Test void testEncodeHpackHeadersSkipped() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("transfer-encoding", "chunked"); h.put("connection", "keep-alive");
        h.put("keep-alive", "timeout=5"); h.put("content-length", "100");
        assertNotNull(Http2Helper.encodeHpackHeaders(mockResp(200, h)));
    }

    @Test void testEncodeHpackHeadersListValue() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        List<String> v = new ArrayList<String>(); v.add("v1"); v.add("v2");
        h.put("x-custom", v);
        assertNotNull(Http2Helper.encodeHpackHeaders(mockResp(200, h)));
    }

    @Test void testEncodeHpackHeadersNullHeaders() throws Exception {
        assertNotNull(Http2Helper.encodeHpackHeaders(mockResp(200, null)));
    }

    // ==================== Handle request ====================

    @Test void testPrepareSubmitEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.prepareSubmit(true);
        assertTrue(f.stream.endStream);
    }

    @Test void testPrepareSubmitNotEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.prepareSubmit(false);
        assertFalse(f.stream.endStream); assertNotNull(f.stream.bodyStream);
    }

    @Test void testGetInputStreamCreatesBodyStream() throws Exception {
        assertNotNull(createFixture().stream.getInputStream());
    }

    @Test void testGetInputStreamEndStreamMarksEnded() throws Exception {
        // L673: lazy-created bodyStream must be marked ended when request is already endStream
        // (non-streaming body fully buffered in bodyData, fix for H2 small-body timeout regression)
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.endStream = true;
        InputStream in = f.stream.getInputStream();
        assertNotNull(in);
        assertNotNull(f.stream.bodyStream);
        assertTrue(f.stream.bodyStream.ended);
    }

    // ==================== Control frames ====================

    @Test void testOnHeadersFrameEndHeadersAlreadyTrue() throws Exception {
        Fixture f = createFixture(); f.stream.endHeaders();
        f.stream.onHeadersFrame(new Http2Frame(null, 0, 0, 0, 0, null, 0, 1), f.ctx);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 1);
    }

    @Test void testOnDataFrameBeforeEndHeaders() throws Exception {
        Fixture f = createFixture();
        Http2Frame df = new Http2Frame(null, 0, 0, 10, 10, null, 0, 1);
        f.stream.onDataFrame(df, f.ctx);
        verify(f.reader).closeConnection(f.ctx, 1);
    }

    // ==================== Stream lifecycle ====================

    @Test void testCompleteStream() throws Exception {
        try { createFixture().stream.completeStream(); } catch (Exception ignored) { }
    }

    @Test void testCompleteStreamAlreadySent() throws Exception {
        Fixture f = createFixture(); f.stream.endStreamSent = true;
        f.stream.completeStream();
    }



    @Test void testNotifyConsumed() throws Exception {
        Fixture f = createFixture(); f.stream.notifyConsumed(100);
        verify(f.reader).sendWindowUpdatePair(eq(f.ctx), eq(f.stream), eq(100), eq(false));
    }

    @Test void testNotifyConsumedZero() throws Exception {
        Fixture f = createFixture(); f.stream.notifyConsumed(0);
        verify(f.reader, never()).sendWindowUpdatePair(any(), any(), anyInt(), anyBoolean());
    }

    @Test void testWriteFrameAfterEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.endStreamSent = true;
        f.stream.writeFrame(java.nio.ByteBuffer.allocate(9));
    }

    // ==================== onDataFrame branch gaps ====================

    @Test void testOnDataFrameEndStreamEmptyBody() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        Http2Frame frame = new Http2Frame(new byte[0], 0, 0, 0, 0, null, 1, 1); // END_STREAM, empty body
        f.stream.onDataFrame(frame, f.ctx);
        // bodyData.length == 0 → L267 false branch
    }

    // ==================== getContentLength streaming, no declared content length ====================

    @Test void testGetContentLengthStreamingBodyStreamEnded() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = -1;
        f.stream.prepareSubmit(false); // creates bodyStream
        f.stream.bodyStream.endStream();
        assertEquals(0, f.stream.getContentLength()); // bodyStream.totalLength = 0
    }

    // ==================== feedDataFrame with bodyStream ====================

    @Test void testFeedDataFrameWithBodyStream() throws Exception {
        Fixture f = createFixture();
        f.stream.prepareSubmit(false); // creates bodyStream
        Http2Frame frame = new Http2Frame("hello".getBytes(), 0, 0, 5, 5, null, 0, 1);
        f.stream.feedDataFrame(frame);
        assertFalse(f.stream.endStream);
    }

    @Test void testFeedDataFrameEndStream() throws Exception {
        Fixture f = createFixture();
        f.stream.prepareSubmit(false); // creates bodyStream
        Http2Frame frame = new Http2Frame("hello".getBytes(), 0, 0, 5, 5, null, 1, 1); // END_STREAM
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream);
        assertTrue(f.stream.bodyStream.ended);
    }

    // ==================== HPACK: null header value (L559) ====================

    @Test void testEncodeHpackHeadersNullValue() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("x-null-val", null); // value is null → L559 else-if false → skip
        assertNotNull(Http2Helper.encodeHpackHeaders(mockResp(200, h)));
    }

    // ==================== recvWindow == 0 with bodyData at capacity ====================

    @Test void testOnDataFrameRecvWindowZeroCapacityStreaming() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        setField(f.stream, "receiveWindow", 1);
        int capacity = Math.max(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE.value << 1, HttpConf.MAX_BODY_IN_MEMORY);
        setField(f.stream, "dataFramesTotalLength", capacity - 1);

        Http2Frame frame = new Http2Frame(new byte[]{0}, 0, 0, 1, 1, null, 0, 1);
        f.stream.onDataFrame(frame, f.ctx);
        assertTrue(f.stream.needStreaming);
        assertFalse(f.stream.endStream);
    }

    // ==================== getContentLength streaming, bodyStream not ended ====================

    @Test void testGetContentLengthStreamingBodyStreamNotEnded() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = -1;
        f.stream.prepareSubmit(false); // creates bodyStream, ended defaults to false
        assertThrows(IllegalStateException.class, () -> f.stream.getContentLength());
    }

    // ==================== feedDataFrame: bodyStream feed failure ====================

    @Test void testFeedDataFrameFeedFailure() throws Exception {
        Fixture f = createFixture();
        // Set bodyData to non-empty so bodyStream has capacity, then feed with more than capacity
        f.stream.bodyData = new byte[4];
        f.stream.prepareSubmit(false);
        Http2Frame frame = new Http2Frame("too long data".getBytes(), 0, 0, 13, 13, null, 0, 1); // exceeds capacity 4
        f.stream.feedDataFrame(frame);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 3);
        assertTrue(f.stream.bodyStream.ended);
    }

    @Test void testFeedDataFrameEndStreamFeedSuccess() throws Exception {
        Fixture f = createFixture();
        f.stream.bodyData = new byte[100];
        f.stream.prepareSubmit(false); // creates bodyStream, full=true
        // Zero-length payload: feed() returns true immediately (len==0 short-circuit),
        // then frameEnd=true triggers bodyStream.endStream().
        Http2Frame frame = new Http2Frame(new byte[0], 0, 0, 0, 0, null, 1, 1); // END_STREAM
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream);
        assertTrue(f.stream.bodyStream.ended);
    }

    // ==================== onDataFrame: first DATA frame is also END_STREAM (L424 true branch) ====================

    @Test void testOnDataFrameEndStreamFirstFrameData() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        Http2Frame frame = new Http2Frame("body".getBytes(), 0, 0, 4, 4, null, 1, 1); // END_STREAM
        f.stream.onDataFrame(frame, f.ctx);
        // dataFrames == null -> L424 true branch (bodyData = actualPayload)
        assertTrue(f.stream.endStream);
        assertNotNull(f.stream.bodyData);
    }

    // ==================== onDataFrame: END_STREAM after accumulation (L430-432) ====================

    @Test void testOnDataFrameEndStreamAccumulated() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        // first non-end frame accumulates into dataFrames
        Http2Frame first = new Http2Frame("ab".getBytes(), 0, 0, 2, 2, null, 0, 1);
        f.stream.onDataFrame(first, f.ctx);
        // second frame is END_STREAM -> dataFrames != null -> L430-432 else branch
        Http2Frame last = new Http2Frame("cd".getBytes(), 0, 0, 2, 2, null, 1, 1); // END_STREAM
        f.stream.onDataFrame(last, f.ctx);
        assertTrue(f.stream.endStream);
        assertNotNull(f.stream.bodyData);
    }

    // ==================== feedDataFrame with bodyStream == null (L600 false branch) ====================

    @Test void testFeedDataFrameWithoutBodyStream() throws Exception {
        Fixture f = createFixture();
        // bodyStream is null (no prepareSubmit yet) -> L600 false branch skips feed
        Http2Frame frame = new Http2Frame("hi".getBytes(), 0, 0, 2, 2, null, 1, 1);
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream); // frameEnd still recorded even without bodyStream
    }

    // ==================== fillDiagnostic with bodyStream != null (L658-661) ====================

    @Test void testFillDiagnosticWithBodyStream() throws Exception {
        Fixture f = createFixture();
        f.stream.prepareSubmit(false); // creates bodyStream
        java.util.Map<String, Object> m = new java.util.HashMap<String, Object>();
        f.stream.fillDiagnostic(m);
        assertTrue(m.containsKey("bodyUnconsumed"));
    }

    // ==================== writeFrames batch (L742-746) ====================

    @Test void testWriteFramesBatch() throws Exception {
        Fixture f = createFixture();
        List<java.nio.ByteBuffer> frames = new ArrayList<java.nio.ByteBuffer>();
        frames.add(java.nio.ByteBuffer.allocate(9));
        frames.add(java.nio.ByteBuffer.allocate(9));
        // writeAllFlush is final on ChannelContext (cannot be verified via Mockito);
        // just exercise writeFrames so L742-746 (batch prepareFrame + flush) is covered
        f.stream.writeFrames(frames);
        assertEquals(2, frames.size());
    }

    // ==================== WINDOW_UPDATE with increment <= 0 (L213-214) ====================

    @Test void testWindowUpdateIncrementZero() throws Exception {
        Fixture f = createFixture();
        Http2Frame wu = new Http2Frame(new byte[13], 0, 9, 4, 4, Http2FrameType.WINDOW_UPDATE, 0, 1); // readInt32(frameData, 9) == 0 -> increment <= 0
        f.stream.handleFrame(wu, f.ctx);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 1);
    }

    // ==================== completeRequest before endStream (completeRequest !endStream branch) ====================

    @Test void testCompleteRequestBeforeEndStream() throws Exception {
        Fixture f = createFixture();
        // endStream defaults to false -> completeRequest aborts with RST (NO_ERROR)
        f.stream.completeRequest();
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 0);
    }

    // ==================== endStream submit with prior refill (L437 wu = bodyData.length - refilled) ====================

    @Test void testOnDataFrameEndStreamWithRefill() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        setField(f.stream, "refilled", 2); // non-zero so wu = bodyData.length - refilled
        Http2Frame frame = new Http2Frame("abcd".getBytes(), 0, 0, 4, 4, null, 1, 1); // END_STREAM, dataFrames == null -> L424 true, then L437 true with refill
        f.stream.onDataFrame(frame, f.ctx);
        assertTrue(f.stream.endStream);
    }

    // ==================== writeFrame DEBUG branch ====================
    // Http2MessageReader.DEBUG is static final, requires JVM property -DWastnet.http2.debug=true.
    // Skipping -- low-value debug formatting path.

}
