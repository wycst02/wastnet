package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.http.HttpRequestDecoder;
import io.github.wycst.wastnet.http.h2.Http2ClientReader;
import io.github.wycst.wastnet.http.h2.Http2ClientStream;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage tests for {@link H1H2ProxyAdapter} (HTTP/1.1 -> HTTP/2 direction).
 * The real H2 handshake is bypassed by mocking ChannelContext and swapping the
 * final parent-field {@code clientReader} with a mock via Unsafe.
 * Decoded requests are real (HttpDecodedRequest) because their parent-class
 * methods cannot be stubbed by Mockito's default mock maker.
 */
public class H1H2ProxyAdapterTest {

    private static final sun.misc.Unsafe UNSAFE = getUnsafe();

    private final AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();

    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setFinalField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        Field f = null;
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) throw new NoSuchFieldException(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(f), value);
    }

    private static ChannelContext mockCtx(boolean closed) throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getHandShakedApplicationProtocol()).thenReturn(null);
        when(ctx.isSSL()).thenReturn(false);
        when(ctx.binding()).thenReturn("");
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(ctx.channel()).thenReturn(ch);
        when(ctx.isChannelClosed()).thenReturn(closed);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        return ctx;
    }

    private static HttpProxyConnection newConn(ChannelContext clientCtx, ChannelContext targetCtx) throws Exception {
        HttpProxyWorker worker = new HttpProxyWorker();
        return new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
    }

    // Decode a real HttpDecodedRequest (mocking parent-class methods is unsupported).
    private HttpRequest decodeRequest(String raw) throws Exception {
        captured.set(null);
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        byte[] b = raw.getBytes();
        d.decode(b, 0, b.length, ctx);
        return captured.get();
    }

    // ==================== Constructor (L45-47) ====================

    // ==================== sendRequest success (L51-74, no body) ====================

    @Test
    public void testSendRequestSuccess() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        Http2ClientStream stream = mock(Http2ClientStream.class);
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(stream);
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);
        // busy is released after the request is dispatched
        Assertions.assertTrue(adapter.tryAcquire());
    }

    // ==================== sendRequest error -> 502 (L59-68) ====================

    @Test
    public void testSendRequestErrorWrites502() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        // getOrCreateStream returns null (default) -> sendH2RequestHeadersFromH1 NPE -> catch -> 502
        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);
        Assertions.assertTrue(adapter.tryAcquire());
    }

    // ==================== sendRequest error + target closed -> conn.close() (L64-65) ====================

    @Test
    public void testSendRequestErrorTargetClosedCloses() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(true);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);
        Assertions.assertTrue(conn.closed);
    }

    // ==================== sendRequest with body -> sendH1RequestBody (L72) ====================

    @Test
    public void testSendRequestWithBody() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        // Use the real clientReader (created by super()) so getOrCreateStream returns a real
        // Http2ClientStream with a working encoder; mock targetCtx only queues writes.
        HttpRequest request = decodeRequest("POST /path HTTP/1.1\r\nHost: host\r\nContent-Length: 5\r\n\r\nhello");
        adapter.sendRequest(request, targetCtx);
        Assertions.assertTrue(adapter.tryAcquire());
    }

    // ==================== onHandle unknown stream -> early return (L80-85) ====================

    @Test
    public void testOnHandleUnknownStream() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientStream msg = mock(Http2ClientStream.class);
        when(msg.getStreamId()).thenReturn(99);
        adapter.onHandle(clientCtx, msg);
    }

    // ==================== onHandle protocol error -> 502 (L86-90) ====================

    @Test
    public void testOnHandleProtocolError() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        Http2ClientStream stream = mock(Http2ClientStream.class);
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(stream);
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);

        Http2ClientStream msg = mock(Http2ClientStream.class);
        when(msg.getStreamId()).thenReturn(1);
        when(msg.isServerProtocolError()).thenReturn(true);
        adapter.onHandle(clientCtx, msg);
    }

    // ==================== onHandle success with in-memory body (L91-124) ====================

    @Test
    public void testOnHandleSuccessWithBody() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        Http2ClientStream stream = mock(Http2ClientStream.class);
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(stream);
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);

        HttpDecodedResponse response = mock(HttpDecodedResponse.class);
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("x-test", "v");
        headers.put("content-length", "5");
        // List-valued header exercises the multi-value addHeader branch (L101-104)
        headers.put("x-list", java.util.Arrays.asList("a", "b"));
        when(response.getHeaders()).thenReturn(headers);
        when(response.getStatusCode()).thenReturn(200);
        when(response.isStream()).thenReturn(false);
        when(response.getBody()).thenReturn("data".getBytes());
        when(reader.buildResponse(any())).thenReturn(response);

        Http2ClientStream msg = mock(Http2ClientStream.class);
        when(msg.getStreamId()).thenReturn(1);
        when(msg.isServerProtocolError()).thenReturn(false);
        adapter.onHandle(clientCtx, msg);
    }

    // ==================== onHandle success with streaming body (L110-116) ====================

    @Test
    public void testOnHandleSuccessStreamBody() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        Http2ClientStream stream = mock(Http2ClientStream.class);
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(stream);
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);

        // Use a real HttpDecodedResponse: getBodyStream() is final, so a mock stub returns null
        // and the while-loop body (L115) would never execute.
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("x-test", "v");
        HttpDecodedResponse response = new HttpDecodedResponse(HttpVersion.HTTP_2, 200, "host",
                headers, null, 0, "text/plain", true, new ByteArrayInputStream("streamdata".getBytes()));
        when(reader.buildResponse(any())).thenReturn(response);

        Http2ClientStream msg = mock(Http2ClientStream.class);
        when(msg.getStreamId()).thenReturn(1);
        when(msg.isServerProtocolError()).thenReturn(false);
        adapter.onHandle(clientCtx, msg);
    }

    // ==================== onHandle buildResponse throws -> 502 (L125-128) ====================

    @Test
    public void testOnHandleBuildResponseThrows() throws Throwable {
        ChannelContext clientCtx = mockCtx(false);
        ChannelContext targetCtx = mockCtx(false);
        HttpProxyConnection conn = newConn(clientCtx, targetCtx);
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);

        Http2ClientReader reader = mock(Http2ClientReader.class);
        when(reader.nextStreamId()).thenReturn(1);
        Http2ClientStream stream = mock(Http2ClientStream.class);
        when(reader.getOrCreateStream(eq(1), any())).thenReturn(stream);
        when(reader.buildResponse(any())).thenThrow(new RuntimeException("decode failed"));
        setFinalField(adapter, "clientReader", reader);

        HttpRequest request = decodeRequest("GET /path HTTP/1.1\r\nHost: host\r\n\r\n");
        adapter.sendRequest(request, targetCtx);

        Http2ClientStream msg = mock(Http2ClientStream.class);
        when(msg.getStreamId()).thenReturn(1);
        when(msg.isServerProtocolError()).thenReturn(false);
        adapter.onHandle(clientCtx, msg);
    }
}
