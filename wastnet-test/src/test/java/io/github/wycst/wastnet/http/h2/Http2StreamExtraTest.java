package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpBuf;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Supplementary for {@link Http2Stream} protocol/edge paths:
 * <ul>
 *   <li>malformed frame size handling (RST_STREAM / WINDOW_UPDATE / unknown type)</li>
 *   <li>oversized header block -&gt; early 431 response; header decode failure -&gt; RST</li>
 *   <li>trailer block: complete, continuation split, oversized</li>
 *   <li>connection-level receive window underflow on DATA</li>
 *   <li>streaming reclaim, best-effort IO exception swallowing, batched frame flush</li>
 * </ul>
 */
public class Http2StreamExtraTest {

    private static Http2ServerReader mockReader() {
        Http2ServerReader reader = mock(Http2ServerReader.class);
        H2TestHelper.initMockReader(reader);
        H2TestHelper.installCodec(reader);
        H2TestHelper.setConnectRecvWindow(reader, 65535);
        reader.streamInitSendWindowSize = 65535;
        reader.maxSendPayloadSize = 16384;
        return reader;
    }

    private static ChannelContext mockCtx() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65535);
        return ctx;
    }

    private static Http2ServerStream serverStream() {
        return new Http2ServerStream(mockReader(), 1, mockCtx());
    }

    private static TestServerStream serverStream2() {
        return new TestServerStream(mockReader(), mockCtx());
    }

    private static class TestServerStream extends Http2ServerStream {
        TestServerStream(Http2ServerReader reader, ChannelContext ctx) {
            super(reader, 1, ctx);
        }

        @Override
        protected void onEndHeaders() {
        }

        @Override
        public void submit() {
        }

        @Override
        public String debugPrefix() {
            return "S";
        }
    }

    private static Http2Frame frame(int type, int flags, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(9 + payload.length);
        bb.put((byte) (payload.length >> 16));
        bb.put((byte) (payload.length >> 8));
        bb.put((byte) payload.length);
        bb.put((byte) type);
        bb.put((byte) flags);
        bb.putInt(1);
        bb.put(payload);
        bb.flip();
        List<Http2Frame> frames = Http2Frame.fromByteBuffer(bb);
        return frames.get(0);
    }

    @Test
    public void testHandleRstStreamWrongSize() throws Exception {
        Http2ServerStream s = serverStream();
        Http2Frame f = new Http2Frame(new byte[9], 0, 9, 5, 5, Http2FrameType.RST_STREAM, 0, 1);
        s.handleFrame(f, s.ctx);
        verify(s.reader).closeConnection(any(), eq(6));
    }

    @Test
    public void testHandleWindowUpdateWrongSize() throws Exception {
        Http2ServerStream s = serverStream();
        Http2Frame f = new Http2Frame(new byte[9], 0, 9, 5, 5, Http2FrameType.WINDOW_UPDATE, 0, 1);
        s.handleFrame(f, s.ctx);
        verify(s.reader).closeConnection(any(), eq(6));
    }

    @Test
    public void testHandleUnknownFrameWrongSize() throws Exception {
        Http2ServerStream s = serverStream();
        Http2Frame f = new Http2Frame(new byte[9], 0, 9, 4, 4, Http2FrameType.PRIORITY, 0, 1);
        s.handleFrame(f, s.ctx);
        verify(s.reader).sendRstStreamFrame(any(), eq(1), eq(6));
    }

    @Test
    public void testHandleHeadersOversize() throws Exception {
        Http2ServerStream s = serverStream();
        byte[] payload = new byte[20000];
        Http2Frame f = frame(Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_HEADERS, payload);
        s.handleFrame(f, s.ctx);
        verify(s.reader).removeStream(1);
    }

    @Test
    public void testHandleHeadersMissingAuthority() throws Exception {
        Http2ServerStream s = serverStream();
        HttpBuf buf = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(buf, ":method", "GET");
        Http2Helper.writeHpackLiteral(buf, ":path", "/");
        Http2Frame f = frame(Http2Frame.FRAME_TYPE_HEADERS,
                Http2Frame.END_HEADERS | Http2Frame.END_STREAM, buf.toBytes());
        s.handleFrame(f, s.ctx);
        verify(s.reader).sendRstStreamFrame(any(), eq(1), eq(1));
    }

    @Test
    public void testHandleDataFrameConnWindowUnderflow() throws Exception {
        TestServerStream s = serverStream2();
        s.endHeaders();
        s.reader.connectRecvWindow.set(5);
        byte[] payload = new byte[10];
        Http2Frame f = frame(Http2Frame.FRAME_TYPE_DATA, 0x00, payload);
        s.handleFrame(f, s.ctx);
        verify(s.reader).closeConnection(any(), eq(3));
    }

    @Test
    public void testSetTrailersListenerAndIsRemoved() throws Exception {
        Http2ServerStream s = serverStream();
        TrailersListener l = mock(TrailersListener.class);
        s.setTrailersListener(l);
        assertFalse(s.isRemoved());
    }

    @Test
    public void testFireTrailers() throws Exception {
        Http2ServerStream s = serverStream();
        TrailersListener l = mock(TrailersListener.class);
        s.setTrailersListener(l);
        invokeFireTrailers(s);
        verify(l).onTrailers(any());

        Http2ServerStream s2 = serverStream();
        invokeFireTrailers(s2);
    }

    private static void invokeFireTrailers(Http2Stream s) throws Exception {
        Method m = Http2Stream.class.getDeclaredMethod("fireTrailers");
        m.setAccessible(true);
        m.invoke(s);
    }

    @Test
    public void testCompleteStreamIoException() throws Exception {
        TestServerStream s = serverStream2();
        doThrow(new IOException("boom")).when(s.ctx).writeSync(any());
        s.completeStream();
        verify(s.reader).removeStream(1);
    }

    @Test
    public void testPendingConnectionReclaim() throws Exception {
        TestServerStream s = serverStream2();
        s.needStreaming = true;
        s.bodyStream = new Http2BodyInputStream("hello".getBytes(), s);
        assertEquals(5, s.pendingConnectionReclaim());

        TestServerStream s2 = serverStream2();
        s2.needStreaming = false;
        assertEquals(0, s2.pendingConnectionReclaim());
        s2.endStream = true;
        assertEquals(0, s2.pendingConnectionReclaim());
    }

    @Test
    public void testNotifyConsumedIoException() throws Exception {
        TestServerStream s = serverStream2();
        doThrow(new IOException("x")).when(s.reader)
                .sendWindowUpdatePair(any(), any(), anyInt(), anyBoolean());
        s.notifyConsumed(50);
    }

    @Test
    public void testTrailerContinuationComplete() throws Exception {
        TestServerStream s = serverStream2();
        HttpBuf init = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(init, ":method", "POST");
        Http2Helper.writeHpackLiteral(init, ":scheme", "http");
        Http2Helper.writeHpackLiteral(init, ":path", "/upload");
        Http2Helper.writeHpackLiteral(init, ":authority", "host");
        s.handleFrame(frame(Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_HEADERS, init.toBytes()), s.ctx);

        TrailersListener l = mock(TrailersListener.class);
        s.setTrailersListener(l);
        s.bodyStream = new Http2BodyInputStream(new byte[0], s);

        HttpBuf trailer = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(trailer, "x-trailer", "tval");
        s.handleFrame(frame(Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_STREAM, trailer.toBytes()), s.ctx);

        s.handleFrame(frame(Http2Frame.FRAME_TYPE_CONTINUATION, Http2Frame.END_HEADERS, new byte[0]), s.ctx);
        verify(l).onTrailers(any());
    }

    @Test
    public void testTrailerOversize() throws Exception {
        TestServerStream s = serverStream2();
        HttpBuf init = HttpBuf.of(256);
        Http2Helper.writeHpackLiteral(init, ":method", "POST");
        Http2Helper.writeHpackLiteral(init, ":scheme", "http");
        Http2Helper.writeHpackLiteral(init, ":path", "/upload");
        Http2Helper.writeHpackLiteral(init, ":authority", "host");
        s.handleFrame(frame(Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_HEADERS, init.toBytes()), s.ctx);

        byte[] big = new byte[20000];
        s.handleFrame(frame(Http2Frame.FRAME_TYPE_HEADERS,
                Http2Frame.END_HEADERS | Http2Frame.END_STREAM, big), s.ctx);
        verify(s.reader).sendRstStreamFrame(any(), eq(1), eq(1));
    }
}
