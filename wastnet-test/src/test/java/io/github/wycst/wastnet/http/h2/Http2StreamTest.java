package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;

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
        mockReader.connectRecvWindow = new java.util.concurrent.atomic.AtomicLong(Http2MessageReader.CONNECT_RECEIVE_WINDOW_SIZE);
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
        mockReader.connectRecvWindow = new java.util.concurrent.atomic.AtomicLong(Http2MessageReader.CONNECT_RECEIVE_WINDOW_SIZE);
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
}
