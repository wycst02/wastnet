package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2Stream}.
 *
 * @author wangyc
 */
public class Http2StreamTest {

    private static Http2ServerReader newMockReader() {
        Http2ServerReader r = mock(Http2ServerReader.class);
        H2TestHelper.initMockReader(r);
        return r;
    }

    @Test
    public void testCreateFrameBuffer() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ByteBuffer buf = ctx.createFrameBuffer(100, 50, 0x00, 0x01);
        Assertions.assertNotNull(buf);
        Assertions.assertEquals(100, buf.capacity());
        byte[] array = buf.array();
        Assertions.assertEquals(0x00, array[3] & 0xFF); // Type = DATA
        Assertions.assertEquals(0x01, array[4] & 0xFF); // Flags = END_STREAM
        Assertions.assertEquals(1, array[8]); // Stream ID = 1
    }

    @Test
    public void testCreateFrameBufferForHeaders() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 3, mockCtx);

        ByteBuffer buf = ctx.createFrameBuffer(50, 20, 0x01, 0x04);
        byte[] array = buf.array();
        Assertions.assertEquals(0x01, array[3] & 0xFF); // Type = HEADERS
        Assertions.assertEquals(0x04, array[4] & 0xFF); // Flags = END_HEADERS
        Assertions.assertEquals(3, array[8]); // Stream ID = 3
    }

    // ==================== writeHpackString ====================

    @Test
    public void testWriteHpackString() {
        HttpBuf buf = HttpBuf.of(32);
        Http2Helper.writeHpackString(buf, "test");
        byte[] bytes = buf.toBytes();
        Assertions.assertEquals(4, bytes[0] & 0x7F); // length prefix
        Assertions.assertEquals('t', bytes[1]);
        Assertions.assertEquals('e', bytes[2]);
        Assertions.assertEquals('s', bytes[3]);
        Assertions.assertEquals('t', bytes[4]);
    }

    // ==================== writeHpackLiteral boundary tests ====================

    private static String stringOfLen(int len) {
        char[] chars = new char[len];
        for (int i = 0; i < len; ++i) chars[i] = 'A';
        return new String(chars);
    }

    private void assertLiteralHeader(HttpBuf buf, String name, String value) {
        byte[] data = buf.toBytes();
        // First byte: literal flag (0x10)
        Assertions.assertEquals(0x10, data[0] & 0xFF, "missing literal flag");

        int off = 1;
        // Decode HPACK string: name (may be Huffman-encoded for long names)
        boolean nameHuffman = (data[off] & 0x80) != 0;
        int nameLen = data[off] & 0x7F;
        off++;
        if (nameLen == 127) {
            int temp = 0, shift = 0;
            while (true) {
                int b = data[off++] & 0xFF;
                temp += (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
            }
            nameLen = 127 + temp;
        }
        String decodedName;
        if (nameHuffman) {
            byte[] huffOut = new byte[nameLen * 2];
            int decodedLen = HuffmanByteCodec.decodeData(data, off, nameLen, huffOut, 0);
            decodedName = new String(huffOut, 0, decodedLen);
        } else {
            decodedName = new String(data, off, nameLen);
        }
        Assertions.assertEquals(name, decodedName, "name mismatch");
        off += nameLen; // skip raw bytes (original or Huffman-compressed)

        // Decode HPACK string: value (may be Huffman-encoded)
        boolean valueHuffman = (data[off] & 0x80) != 0;
        int valueLen = data[off] & 0x7F;
        off++;
        if (valueLen == 127) {
            int temp = 0, shift = 0;
            while (true) {
                int b = data[off++] & 0xFF;
                temp += (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
            }
            valueLen = 127 + temp;
        }
        String decodedValue;
        if (valueHuffman) {
            byte[] huffOut = new byte[valueLen * 2];
            int decodedLen = HuffmanByteCodec.decodeData(data, off, valueLen, huffOut, 0);
            decodedValue = new String(huffOut, 0, decodedLen);
        } else {
            decodedValue = new String(data, off, valueLen);
        }
        Assertions.assertEquals(value, decodedValue, "value mismatch");
    }

    @Test
    public void testWriteHpackLiteralEmptyValue() {
        HttpBuf buf = HttpBuf.of(64);
        Http2Helper.writeHpackLiteral(buf, "x", "");
        assertLiteralHeader(buf, "x", "");
    }

    @Test
    public void testWriteHpackLiteralShortValue() {
        HttpBuf buf = HttpBuf.of(64);
        Http2Helper.writeHpackLiteral(buf, "x", "hello");
        assertLiteralHeader(buf, "x", "hello");
    }

    @Test
    public void testWriteHpackLiteralValueLength126() {
        // 126 < 127: single-byte length prefix
        String val = stringOfLen(126);
        HttpBuf buf = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralValueLength127() {
        // 127 >= 127: multi-byte length prefix, prefix = 127
        String val = stringOfLen(127);
        HttpBuf buf = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralValueLength128() {
        // 128 >= 127: multi-byte prefix, remainder = 1
        String val = stringOfLen(128);
        HttpBuf buf = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralValueLength200() {
        // 200 >= 127: multi-byte prefix, remainder = 73
        String val = stringOfLen(200);
        HttpBuf buf = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(buf, "x", val);
        assertLiteralHeader(buf, "x", val);
    }

    @Test
    public void testWriteHpackLiteralLongName() {
        // Name also uses writeHpackString, test with long name
        String name = stringOfLen(200);
        HttpBuf buf = HttpBuf.of(512);
        Http2Helper.writeHpackLiteral(buf, name, "v");
        assertLiteralHeader(buf, name, "v");
    }

    // ==================== writeFrame ====================

    @Test
    public void testWriteFrame() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ByteBuffer frame = ByteBuffer.allocate(50);
        ctx.writeFrame(frame);
        verify(mockCtx).write(any(ByteBuffer.class));
    }

    // ==================== headers ====================

    @Test
    public void testHeadersWithEmptyMapByDefault() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 0, mockCtx);
        Assertions.assertTrue(ctx.headers().isEmpty());
    }

    @Test
    public void testGetContentLengthDefault() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);
        Assertions.assertEquals(0L, ctx.getContentLength());
    }

    // ==================== handover ====================

    @Test
    public void testHandoverSetsFlag() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        Assertions.assertFalse(ctx.handovered);
        ctx.handover();
        Assertions.assertTrue(ctx.handovered);
    }

    // ==================== getInputStream ====================

    @Test
    public void testGetInputStreamCreatesBodyStream() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        InputStream in = ctx.getInputStream();
        Assertions.assertNotNull(in);
        Assertions.assertTrue(in instanceof Http2BodyInputStream);
        // Second call returns same instance
        Assertions.assertSame(in, ctx.getInputStream());
    }

    // ==================== completeRequest ====================

    @Test
    public void testCompleteRequestSendsRstStreamWhenNotEnded() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStream = false;
        ctx.completeRequest();
        verify(mockReader).sendRstStreamFrame(mockCtx, 1, 0);
    }

    @Test
    public void testCompleteRequestNoOpWhenAlreadyEnded() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStream = true;
        ctx.completeRequest();
        verify(mockReader, never()).sendRstStreamFrame(any(), anyInt(), anyInt());
    }

    // ==================== completeStream ====================

    @Test
    public void testCompleteStreamRemovesStream() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        when(mockCtx.getWriteBufferSize()).thenReturn(65535);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStreamSent = true; // skip fallback DATA frame
        ctx.completeStream();
        verify(mockReader).removeStream(1);
    }

    @Test
    public void testCompleteStreamSendsEndStreamIfNotSent() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getWriteBufferSize()).thenReturn(65535);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.endStreamSent = false;
        ctx.completeStream();
        verify(mockCtx).writeFlush(any(ByteBuffer.class));
        verify(mockReader).removeStream(1);
    }

    // ==================== handleFrame dispatch ====================

    @Test
    public void testHandleFrameRstStreamRemovesStream() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        // RST_STREAM frame (9-byte header + 4-byte error code, per RFC 7540 §6.4)
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(new byte[]{0, 0, 4, 3, 0, 0, 0, 0, 1}); // length=4, type=3 RST_STREAM, streamId=1
        buf.putInt(0); // error code = NO_ERROR
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        ctx.handleFrame(frame, mockCtx);
        verify(mockReader).removeStream(1);
    }

    @Test
    public void testHandleFrameWindowUpdateIncrementsSendWindow() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        mockReader.connectSendWindow = 100000;
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        long before = ctx.sendWindow;
        // WINDOW_UPDATE frame (9-byte header + 4-byte increment)
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(new byte[]{0, 0, 4, 8, 0, 0, 0, 0, 1}); // type=8 WINDOW_UPDATE, streamId=1
        buf.putInt(5000); // increment = 5000
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        ctx.handleFrame(frame, mockCtx);
        Assertions.assertEquals(before + 5000, ctx.sendWindow);
    }

    // ==================== restoreConnectionWindow ====================

    @Test
    public void testRestoreConnectionWindowWhenBelowStreamWindow() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        H2TestHelper.setConnectRecvWindow(mockReader, HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE << 4);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.receiveWindow = 30000;
        mockReader.connectRecvWindow.set(10000); // below stream window

        ctx.restoreConnectionWindow();
        verify(mockReader).sendConnectionWindowUpdate(eq(mockCtx), eq(20000), eq(true));
    }

    @Test
    public void testRestoreConnectionWindowWhenAboveStreamWindow() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerReader mockReader = newMockReader();
        H2TestHelper.setConnectRecvWindow(mockReader, HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE << 4);
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        ctx.receiveWindow = 10000;
        mockReader.connectRecvWindow.set(30000); // above stream window, no restore needed

        ctx.restoreConnectionWindow();
        verify(mockReader, never()).sendConnectionWindowUpdate(any(), anyInt());
    }

    // ==================== sendChunkSize ====================

    @Test
    public void testSendChunkSizeReturnsMinOfLimits() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getWriteBufferSize()).thenReturn(20000);
        Http2ServerReader mockReader = newMockReader();
        mockReader.maxSendPayloadSize = 16384;
        Http2Stream ctx = new Http2ServerStream(mockReader, 1, mockCtx);

        int chunkSize = ctx.sendChunkSize();
        // min(16384, 20000 - 9) = 16384
        Assertions.assertEquals(16384, chunkSize);
    }

    //

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

    static class TestStream extends Http2Stream {
        TestStream(Http2MessageReader reader, int sid, ChannelContext ctx) { super(reader, sid, ctx); }
        protected void onEndHeaders() {}
        protected void submit() {}
        public String debugPrefix() { return "Test"; }
    }

    private static HttpDecodedResponse mockResp(int code, Map<String, Object> headers) {
        HttpDecodedResponse r = mock(HttpDecodedResponse.class);
        when(r.getStatusCode()).thenReturn(code);
        when(r.getHeaders()).thenReturn(headers);
        return r;
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
        setField(stream, "frameBuf", HttpBuf.of(64));
        return new Fixture(reader, ctx, stream);
    }

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

    @Test void testPrepareSubmitEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.prepareSubmit(true);
        assertTrue(f.stream.endStream);
    }

    @Test void testPrepareSubmitNotEndStream() throws Exception {
        Fixture f = createFixture(); f.stream.prepareSubmit(false);
        assertFalse(f.stream.endStream); assertNotNull(f.stream.bodyStream);
    }

    @Test void testGetInputStreamEndStreamMarksEnded() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.endStream = true;
        InputStream in = f.stream.getInputStream();
        assertNotNull(in);
        assertNotNull(f.stream.bodyStream);
        assertTrue(f.stream.bodyStream.ended);
    }

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

    @Test void testOnDataFrameEndStreamEmptyBody() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        Http2Frame frame = new Http2Frame(new byte[0], 0, 0, 0, 0, null, 1, 1);
        f.stream.onDataFrame(frame, f.ctx);
    }

    @Test void testGetContentLengthStreamingBodyStreamEnded() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = -1;
        f.stream.prepareSubmit(false);
        f.stream.bodyStream.endStream();
        assertEquals(0, f.stream.getContentLength());
    }

    @Test void testFeedDataFrameWithBodyStream() throws Exception {
        Fixture f = createFixture();
        f.stream.prepareSubmit(false);
        Http2Frame frame = new Http2Frame("hello".getBytes(), 0, 0, 5, 5, null, 0, 1);
        f.stream.feedDataFrame(frame);
        assertFalse(f.stream.endStream);
    }

    @Test void testFeedDataFrameEndStream() throws Exception {
        Fixture f = createFixture();
        f.stream.prepareSubmit(false);
        Http2Frame frame = new Http2Frame("hello".getBytes(), 0, 0, 5, 5, null, 1, 1);
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream);
        assertTrue(f.stream.bodyStream.ended);
    }

    @Test void testEncodeHpackHeadersNullValue() throws Exception {
        Map<String, Object> h = new HashMap<String, Object>();
        h.put("x-null-val", null);
        assertNotNull(Http2Helper.encodeHpackHeaders(mockResp(200, h)));
    }

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

    @Test void testGetContentLengthStreamingBodyStreamNotEnded() throws Exception {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.declaredContentLength = -1;
        f.stream.prepareSubmit(false);
        assertThrows(IllegalStateException.class, () -> f.stream.getContentLength());
    }

    @Test void testFeedDataFrameFeedFailure() throws Exception {
        Fixture f = createFixture();
        f.stream.bodyData = new byte[4];
        f.stream.prepareSubmit(false);
        Http2Frame frame = new Http2Frame("too long data".getBytes(), 0, 0, 13, 13, null, 0, 1);
        f.stream.feedDataFrame(frame);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 3);
        assertTrue(f.stream.bodyStream.ended);
    }

    @Test void testFeedDataFrameEndStreamFeedSuccess() throws Exception {
        Fixture f = createFixture();
        f.stream.bodyData = new byte[100];
        f.stream.prepareSubmit(false);
        Http2Frame frame = new Http2Frame(new byte[0], 0, 0, 0, 0, null, 1, 1);
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream);
        assertTrue(f.stream.bodyStream.ended);
    }

    @Test void testOnDataFrameEndStreamFirstFrameData() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        Http2Frame frame = new Http2Frame("body".getBytes(), 0, 0, 4, 4, null, 1, 1);
        f.stream.onDataFrame(frame, f.ctx);
        assertTrue(f.stream.endStream);
        assertNotNull(f.stream.bodyData);
    }

    @Test void testOnDataFrameEndStreamAccumulated() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        Http2Frame first = new Http2Frame("ab".getBytes(), 0, 0, 2, 2, null, 0, 1);
        f.stream.onDataFrame(first, f.ctx);
        Http2Frame last = new Http2Frame("cd".getBytes(), 0, 0, 2, 2, null, 1, 1);
        f.stream.onDataFrame(last, f.ctx);
        assertTrue(f.stream.endStream);
        assertNotNull(f.stream.bodyData);
    }

    @Test void testFeedDataFrameWithoutBodyStream() throws Exception {
        Fixture f = createFixture();
        Http2Frame frame = new Http2Frame("hi".getBytes(), 0, 0, 2, 2, null, 1, 1);
        f.stream.feedDataFrame(frame);
        assertTrue(f.stream.endStream);
    }

    @Test void testFillDiagnosticWithBodyStream() throws Exception {
        Fixture f = createFixture();
        f.stream.prepareSubmit(false);
        java.util.Map<String, Object> m = new java.util.HashMap<String, Object>();
        f.stream.fillDiagnostic(m);
        assertTrue(m.containsKey("bodyUnconsumed"));
    }

    @Test void testWriteFramesBatch() throws Exception {
        Fixture f = createFixture();
        List<java.nio.ByteBuffer> frames = new ArrayList<java.nio.ByteBuffer>();
        frames.add(java.nio.ByteBuffer.allocate(9));
        frames.add(java.nio.ByteBuffer.allocate(9));
        f.stream.writeFrames(frames);
        assertEquals(2, frames.size());
    }

    @Test void testWindowUpdateIncrementZero() throws Exception {
        Fixture f = createFixture();
        Http2Frame wu = new Http2Frame(new byte[13], 0, 9, 4, 4, Http2FrameType.WINDOW_UPDATE, 0, 1);
        f.stream.handleFrame(wu, f.ctx);
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 1);
    }

    @Test void testCompleteRequestBeforeEndStream() throws Exception {
        Fixture f = createFixture();
        f.stream.completeRequest();
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 0);
    }

    @Test void testOnDataFrameEndStreamWithRefill() throws Exception {
        Fixture f = createFixture();
        f.stream.endHeaders();
        setField(f.stream, "refilled", 2);
        Http2Frame frame = new Http2Frame("abcd".getBytes(), 0, 0, 4, 4, null, 1, 1);
        f.stream.onDataFrame(frame, f.ctx);
        assertTrue(f.stream.endStream);
    }
}
