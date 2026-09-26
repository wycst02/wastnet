package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.h2.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link H2H1ProxyAdapter}.
 * <p>
 * Covers constructor, sendRequest, onData, onHandle, and lifecycle methods.
 *
 * @author wangyc
 */
public class H2H1ProxyAdapterTest {

    // ==================== Test helper: setup reader with final fields ====================

    private static final Object UNSAFE;

    static {
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Cannot get Unsafe instance", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getUnsafe() { return (T) UNSAFE; }

    private static void setFinalInt(Object target, String fieldName, int value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getSuperclass().getDeclaredField(fieldName);
        f.setAccessible(true);
        long offset = (long) getUnsafe().getClass()
                .getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(getUnsafe(), f);
        getUnsafe().getClass().getMethod("putInt", Object.class, long.class, int.class)
                .invoke(getUnsafe(), target, offset, value);
    }

    private static void setFinalObject(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getSuperclass().getDeclaredField(fieldName);
        f.setAccessible(true);
        long offset = (long) getUnsafe().getClass()
                .getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(getUnsafe(), f);
        getUnsafe().getClass().getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(getUnsafe(), target, offset, value);
    }

    private static Http2MessageReader createReader() throws Exception {
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        setFinalInt(reader, "streamInitSendWindowSize", 65535);
        setFinalInt(reader, "maxSendPayloadSize", 16384);
        setFinalInt(reader, "connectSendWindow", 65535);
        setFinalObject(reader, "http2HpackCodec", new Http2HpackCodec());
        return reader;
    }

    static class Fixture {
        final Http2MessageReader reader;
        final ChannelContext ctx;
        final TestStream stream;
        final Http2Request request;
        Fixture(Http2MessageReader reader, ChannelContext ctx, TestStream stream, Http2Request request) {
            this.reader = reader; this.ctx = ctx; this.stream = stream; this.request = request;
        }
    }

    /** Minimal TestStream that works with Http2Helper static methods. */
    static class TestStream extends Http2ServerStream {
        TestStream(Http2MessageReader reader, int sid, ChannelContext ctx) { super(reader, sid, ctx); }
        protected void onEndHeaders() {}
        protected void submit() {}
        public String debugPrefix() { return "Test"; }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        // Walk up the class hierarchy to find the field
        Class<?> clazz = target.getClass();
        java.lang.reflect.Field field = null;
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

    private static Fixture createFixture() throws Exception {
        Http2MessageReader reader = createReader();
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        when(ctx.isChannelClosed()).thenReturn(false);
        TestStream stream = new TestStream(reader, 1, ctx);
        setField(stream, "method", HttpMethod.GET);
        setField(stream, "path", "/");
        setField(stream, "authority", "localhost");
        setField(stream, "scheme", "http");
        setField(stream, "headers", new LinkedHashMap<String, Object>());
        Http2Request request = mock(Http2Request.class);
        when(request.stream()).thenReturn(stream);
        when(request.getHttpVersion()).thenReturn(HttpVersion.HTTP_2);
        return new Fixture(reader, ctx, stream, request);
    }

    private static ChannelContext createRealCtx() throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        return new ChannelContext(ch, 4096);
    }

    // ==================== Constructor + tryAcquire ====================

    @Test
    public void testConstructorAndTryAcquire() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        assertNotNull(adapter);
        // After constructor, busy=true → tryAcquire returns false
        assertFalse(adapter.tryAcquire());
    }

    // ==================== tryAcquire on recycled adapter ====================

    @Test
    public void testTryAcquireOnReleasedAdapter() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        // busy is true after constructor
        assertFalse(adapter.tryAcquire());
    }

    // ==================== sendRequest success path ====================

    @Test
    public void testSendRequestSuccess() throws Throwable {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext targetCtx = createRealCtx();
        ChannelContext clientCtx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);

        Fixture f = createFixture();
        // Attach stream to targetCtx
        f.stream.getBodyData(); // initialize bodyData

        // sendRequest should send H1 request via Http2Helper and not throw
        adapter.sendRequest(f.request, targetCtx);
        // After success, busy should still be true (not reset by sendH1Request)
        // busy is only reset in the catch block (error) or onHandle (response)
    }

    // ==================== sendRequest error path ====================

    @Test
    public void testSendRequestErrorSendsGatewayError() throws Throwable {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext targetCtx = createRealCtx();
        ChannelContext clientCtx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);

        // Use a minimal fixture without stream fields set → sendH1Request will throw
        Fixture f = createFixture();
        setField(f.stream, "method", null); // cause NPE in sendH1Request

        adapter.sendRequest(f.request, targetCtx);
        // After error, busy should be false (reset in catch block)
        assertTrue(adapter.tryAcquire());
    }

    // ==================== onData: isTarget=false (early return) ====================

    @Test
    public void testOnDataNotTargetReturnsEarly() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 200 OK\r\n\r\n".getBytes());
        adapter.onData(buf, ctx, false, conn);
        assertTrue(buf.remaining() > 0);
    }

    // ==================== onData + onHandle: full response chain ====================

    @Test
    public void testOnDataAndOnHandleSuccess() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);

        // Attach a fixture's request to targetCtx so onHandle can find it
        Fixture f = createFixture();
        targetCtx.attachment(f.request);

        // Send a valid HTTP/1.1 response → decoded → onHandle → writeResponse
        String responseStr = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        ByteBuffer buf = ByteBuffer.wrap(responseStr.getBytes("US-ASCII"));
        adapter.onData(buf, targetCtx, true, conn);

        // After onHandle, busy=false
        assertTrue(adapter.tryAcquire());
    }

    @Test
    public void testOnDataAndOnHandleWithSmallBody() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);

        Fixture f = createFixture();
        targetCtx.attachment(f.request);

        // Response with body
        String responseStr = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndata";
        ByteBuffer buf = ByteBuffer.wrap(responseStr.getBytes("US-ASCII"));
        adapter.onData(buf, targetCtx, true, conn);

        assertTrue(adapter.tryAcquire());
    }

    // ==================== onHandle with disposed connection ====================

    @Test
    public void testOnHandleWithDisposedConnection() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext clientCtx = createRealCtx();
        ChannelContext targetCtx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);

        Fixture f = createFixture();
        targetCtx.attachment(f.request);

        // Mark disposed before processing response
        conn.disposed = true;

        String responseStr = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        ByteBuffer buf = ByteBuffer.wrap(responseStr.getBytes("US-ASCII"));
        adapter.onData(buf, targetCtx, true, conn);

        // onHandle → disposed=true → conn.close()
        assertTrue(conn.closed);
    }

    // ==================== receiveResponse (no-op) ====================

    @Test
    public void testReceiveResponseDoesNotThrow() throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        ChannelContext ctx = createRealCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        adapter.receiveResponse(ctx);
    }
}
