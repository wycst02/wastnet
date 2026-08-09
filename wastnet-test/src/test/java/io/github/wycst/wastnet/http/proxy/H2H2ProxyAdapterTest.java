package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.http.h2.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link H2H2ProxyAdapter}.
 * <p>
 * Covers sendRequest, onData, onHandle and lifecycle methods. The final
 * {@code clientReader} field is replaced via Unsafe with a mock to control
 * stream allocation, response decoding and frame dispatch deterministically.
 *
 * @author wangyc
 */
public class H2H2ProxyAdapterTest {

    // ==================== Unsafe + final field injection ====================

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

    @SuppressWarnings("unchecked")
    private static <T> T getUnsafe() { return (T) UNSAFE; }

    private static void setFinalField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null && field == null) {
            try {
                field = clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) throw new NoSuchFieldException(fieldName);
        field.setAccessible(true);
        long offset = (long) getUnsafe().getClass()
                .getMethod("objectFieldOffset", Field.class).invoke(getUnsafe(), field);
        getUnsafe().getClass().getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(getUnsafe(), target, offset, value);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null && field == null) {
            try {
                field = clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) throw new NoSuchFieldException(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== Reader / stream fixtures ====================

    private static Http2MessageReader createReader() throws Exception {
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        // reader is a Mockito proxy; its superclass is the real Http2MessageReader
        Class<?> realClass = reader.getClass().getSuperclass();
        setFinalIntOn(reader, realClass, "streamInitSendWindowSize", 65535);
        setFinalIntOn(reader, realClass, "maxSendPayloadSize", 16384);
        setFinalIntOn(reader, realClass, "connectSendWindow", 65535);
        setFinalField(reader, "http2HpackCodec", new Http2HpackCodec());
        return reader;
    }

    private static void setFinalIntOn(Object target, Class<?> declClass, String fieldName, int value) throws Exception {
        Field f = declClass.getDeclaredField(fieldName);
        f.setAccessible(true);
        long offset = (long) getUnsafe().getClass().getMethod("objectFieldOffset", Field.class).invoke(getUnsafe(), f);
        getUnsafe().getClass().getMethod("putInt", Object.class, long.class, int.class)
                .invoke(getUnsafe(), target, offset, value);
    }

    /** Minimal server stream that works with Http2Helper static methods. */
    static class TestStream extends Http2ServerStream {
        TestStream(Http2MessageReader reader, int sid, ChannelContext ctx) { super(reader, sid, ctx); }
        protected void onEndHeaders() {}
        protected void submit() {}
        public String debugPrefix() { return "Test"; }
    }

    private static ChannelContext createRealCtx() throws IOException {
        // Delegates to mockCtx so the H2 handshake writeFlush is a no-op and the
        // connection's target channel registers successfully.
        return mockCtx(false);
    }

    private static ChannelContext mockCtx(boolean closed) throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        // A real (unconnected) SocketChannel so HttpProxyConnection.register works,
        // while writeFlush on the mock stays a no-op (avoids NotYetConnectedException).
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(ctx.channel()).thenReturn(ch);
        when(ctx.getHandShakedApplicationProtocol()).thenReturn(null);
        when(ctx.isSSL()).thenReturn(false);
        when(ctx.binding()).thenReturn("");
        when(ctx.isChannelClosed()).thenReturn(closed);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        return ctx;
    }

    private static HttpProxyConnection newConn(ChannelContext clientCtx, ChannelContext targetCtx) throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        return new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
    }

    private static Http2Request mockRequest(Http2ServerStream stream) {
        Http2Request request = mock(Http2Request.class);
        when(request.stream()).thenReturn(stream);
        when(request.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        return request;
    }

    // ==================== Constructor + tryAcquire ====================

    @Test
    public void testConstructorAndTryAcquire() throws Exception {
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = newConn(ctx, ctx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);
        assertNotNull(adapter);
        // H2H2 adapter always allows acquisition (multiplexing)
        assertTrue(adapter.tryAcquire());
    }

    @Test
    public void testReceiveResponseDoesNotThrow() throws Exception {
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = newConn(ctx, ctx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);
        adapter.receiveResponse(ctx);
    }

    // ==================== sendRequest success (no body) ====================

    @Test
    public void testSendRequestSuccess() throws Throwable {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);
        // Use the real clientReader so getOrCreateStream returns a real Http2ClientStream
        // whose package-private writeHeadersFrame writes to targetCtx (mock no-op).

        Http2MessageReader r = createReader();
        TestStream serverStream = new TestStream(r, 1, clientCtx);
        setField(serverStream, "method", HttpMethod.GET);
        setField(serverStream, "path", "/");
        setField(serverStream, "authority", "localhost");
        setField(serverStream, "scheme", "http");
        setField(serverStream, "headers", new LinkedHashMap<String, Object>());
        Http2Request request = mockRequest(serverStream);

        adapter.sendRequest(request, targetCtx);
        // no exception -> success path covered
    }

    // ==================== sendRequest with body -> sendH2RequestBody ====================

    @Test
    public void testSendRequestWithBody() throws Throwable {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);
        // Real clientReader so the body DATA frames are written to mock targetCtx (no-op).

        Http2MessageReader r = createReader();
        TestStream serverStream = new TestStream(r, 1, clientCtx);
        setField(serverStream, "method", HttpMethod.POST);
        setField(serverStream, "path", "/");
        setField(serverStream, "authority", "localhost");
        setField(serverStream, "scheme", "http");
        setField(serverStream, "headers", new LinkedHashMap<String, Object>());
        setField(serverStream, "bodyData", "hello".getBytes());
        Http2Request request = mockRequest(serverStream);

        adapter.sendRequest(request, targetCtx);
        // body branch (L101) exercised
    }

    // ==================== sendRequest error -> sendGatewayError (502) ====================

    @Test
    public void testSendRequestErrorSendsGatewayError() throws Throwable {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        // returning null makes sendH2RequestHeaders NPE -> catch -> sendGatewayError
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(null);
        setFinalField(adapter, "clientReader", reader);

        Http2MessageReader r = createReader();
        TestStream serverStream = new TestStream(r, 1, clientCtx);
        setField(serverStream, "method", HttpMethod.GET);
        setField(serverStream, "path", "/");
        setField(serverStream, "authority", "localhost");
        setField(serverStream, "scheme", "http");
        setField(serverStream, "headers", new LinkedHashMap<String, Object>());
        Http2Request request = mockRequest(serverStream);

        adapter.sendRequest(request, targetCtx);
        // target not closed -> connection not closed
        assertFalse(conn.closed);
    }

    // ==================== sendRequest error + target closed -> connection.close() ====================

    @Test
    public void testSendRequestErrorTargetClosedCloses() throws Throwable {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = mockCtx(true);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(null);
        setFinalField(adapter, "clientReader", reader);

        Http2MessageReader r = createReader();
        TestStream serverStream = new TestStream(r, 1, clientCtx);
        setField(serverStream, "method", HttpMethod.GET);
        setField(serverStream, "path", "/");
        setField(serverStream, "authority", "localhost");
        setField(serverStream, "scheme", "http");
        setField(serverStream, "headers", new LinkedHashMap<String, Object>());
        Http2Request request = mockRequest(serverStream);

        adapter.sendRequest(request, targetCtx);
        assertTrue(conn.closed);
    }

    // ==================== onData: not target -> early return ====================

    @Test
    public void testOnDataNotTargetReturnsEarly() throws Exception {
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = newConn(ctx, ctx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        setFinalField(adapter, "clientReader", reader);

        ByteBuffer buf = ByteBuffer.wrap("HTTP/2 frames".getBytes());
        adapter.onData(buf, ctx, false, conn);
        // buffer untouched
        assertEquals(0, buf.position());
    }

    // ==================== onData: target -> feed decoder ====================

    @Test
    public void testOnDataTargetDecodes() throws Exception {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        setFinalField(adapter, "clientReader", reader);

        ByteBuffer buf = ByteBuffer.wrap("HTTP/2 frames".getBytes());
        adapter.onData(buf, targetCtx, true, conn);
        verify(reader).decode(eq(conn.targetCtx), any(byte[].class), eq(0), anyInt());
    }

    // ==================== onHandle: unknown stream -> early return ====================

    @Test
    public void testOnHandleUnknownStream() throws Exception {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        setFinalField(adapter, "clientReader", reader);

        Http2ClientStream clientStream = mock(Http2ClientStream.class);
        when(clientStream.getStreamId()).thenReturn(999);
        adapter.onHandle(targetCtx, clientStream);
        // no exception, early return
    }

    // ==================== onHandle: server stream removed -> early return ====================

    @Test
    public void testOnHandleServerStreamRemoved() throws Exception {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        setFinalField(adapter, "clientReader", reader);

        Http2ServerStream serverStream = mock(Http2ServerStream.class);
        when(serverStream.isRemoved()).thenReturn(true);
        when(serverStream.getStreamId()).thenReturn(1);
        getServerStreamMap(adapter).put(1, serverStream);

        Http2ClientStream clientStream = mock(Http2ClientStream.class);
        when(clientStream.getStreamId()).thenReturn(1);
        adapter.onHandle(targetCtx, clientStream);
    }

    // ==================== onHandle: success -> buildResponse + writeResponse ====================

    @Test
    public void testOnHandleSuccess() throws Exception {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        HttpDecodedResponse decoded = new HttpDecodedResponse(HttpVersion.HTTP_2, 200, "OK",
                new LinkedHashMap<String, Object>(), null, 0, "text/plain");
        when(reader.buildResponse(any())).thenReturn(decoded);
        setFinalField(adapter, "clientReader", reader);

        Http2ServerStream serverStream = mock(Http2ServerStream.class);
        when(serverStream.isRemoved()).thenReturn(false);
        when(serverStream.getStreamId()).thenReturn(1);
        getServerStreamMap(adapter).put(1, serverStream);

        Http2ClientStream clientStream = mock(Http2ClientStream.class);
        when(clientStream.getStreamId()).thenReturn(1);
        adapter.onHandle(targetCtx, clientStream);

        verify(reader).buildResponse(any());
    }

    // ==================== onHandle: buildResponse throws -> catch ====================

    @Test
    public void testOnHandleBuildResponseThrows() throws Exception {
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.buildResponse(any())).thenThrow(new RuntimeException("decode failed"));
        setFinalField(adapter, "clientReader", reader);

        Http2ServerStream serverStream = mock(Http2ServerStream.class);
        when(serverStream.isRemoved()).thenReturn(false);
        when(serverStream.getStreamId()).thenReturn(1);
        getServerStreamMap(adapter).put(1, serverStream);

        Http2ClientStream clientStream = mock(Http2ClientStream.class);
        when(clientStream.getStreamId()).thenReturn(1);
        // exception swallowed by catch
        adapter.onHandle(targetCtx, clientStream);
    }

    // ==================== helper to access package-private serverStreamMap ====================

    @SuppressWarnings("unchecked")
    private static java.util.concurrent.ConcurrentHashMap<Integer, Http2ServerStream> getServerStreamMap(H2H2ProxyAdapter adapter) throws Exception {
        Field f = H2H2ProxyAdapter.class.getDeclaredField("serverStreamMap");
        f.setAccessible(true);
        return (java.util.concurrent.ConcurrentHashMap<Integer, Http2ServerStream>) f.get(adapter);
    }
}
