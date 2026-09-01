package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpRequestDecoder;
import io.github.wycst.wastnet.http.h2.Http2ClientStream;
import io.github.wycst.wastnet.http.h2.Http2TestHelpers;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class HttpProxyTest {

    private static final HttpProxyWorkerManager manager = new HttpProxyWorkerManager();

    static ChannelContext createCtx() throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        return new ChannelContext(ch, 4096);
    }

    private static HttpProxyWorker createWorker() throws IOException {
        return new HttpProxyWorker(manager);
    }

    @Test
    public void testHttpProxyWorkerIsUpgrade101() throws Exception {
        ByteBuffer empty = ByteBuffer.wrap(new byte[20]);
        assertFalse(HttpProxyWorker.isUpgrade101(empty));
        ByteBuffer upgrade = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols".getBytes());
        assertTrue(HttpProxyWorker.isUpgrade101(upgrade));
    }

    @Test
    public void testHttpProxyWorkerShutdown() throws Exception {
        HttpProxyWorker worker = createWorker();
        worker.shutdown();
    }

    @Test
    public void testHttpProxyWorkerCleanupConnection() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        worker.cleanupConnection(conn);
    }

    @Test
    public void testHttpProxyWorkerCleanupConnectionIdempotent() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        worker.cleanupConnection(conn);
        // Second call should be no-op (connection.closed already true)
        worker.cleanupConnection(conn);
    }

    @Test
    public void testHttpProxyWorkerClearConnections() throws Exception {
        HttpProxyWorker worker = createWorker();
        worker.clearConnections("test-route");
    }

    @Test
    public void testH2H1ProxyAdapterConstructor() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        assertNotNull(adapter);
    }

    @Test
    public void testH2H1ProxyAdapterOnData() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        try { adapter.onData(ByteBuffer.allocate(10), ctx, true, conn); } catch (Exception ignored) {}
    }

    @Test
    public void testHttpProxyConnectionClose() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext clientCtx = createCtx();
        ChannelContext targetCtx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        conn.close();
    }

    @Test
    public void testH2H1ProxyAdapterReceiveResponse() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        adapter.receiveResponse(ctx);
    }

    @Test
    public void testH2H1ProxyAdapterTryAcquire() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        H2H1ProxyAdapter adapter = new H2H1ProxyAdapter(conn);
        assertFalse(adapter.tryAcquire());
    }

    // ==================== resolveAdapter: H2→H2 returns H2H2ProxyAdapter ====================

    @Test
    public void testResolveAdapterH2ToH2ReturnsH2H2Adapter() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext clientCtx = mock(ChannelContext.class);
        ChannelContext targetCtx = mock(ChannelContext.class);
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(clientCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(targetCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(clientCtx.channel()).thenReturn(ch);
        when(targetCtx.channel()).thenReturn(ch);
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        assertTrue(conn.adapter instanceof H2H2ProxyAdapter);
    }

    // ==================== resolveAdapter: H1→H2 returns PASSTHROUGH ====================

    @Test
    public void testResolveAdapterH1ToH2() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext clientCtx = mock(ChannelContext.class);
        ChannelContext targetCtx = mock(ChannelContext.class);
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(clientCtx.getHandShakedApplicationProtocol()).thenReturn(null);
        when(targetCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(clientCtx.channel()).thenReturn(ch);
        when(targetCtx.channel()).thenReturn(ch);
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        assertFalse(conn.adapter instanceof H2H1ProxyAdapter);
    }

    // ==================== cleanupConnection with timeoutFuture ====================

    @Test
    public void testCleanupConnectionCancelsTimeout() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.timeoutFuture = manager.scheduleTimeout(() -> {}, 5000);
        assertNotNull(conn.timeoutFuture);
        worker.cleanupConnection(conn);
        // timeoutFuture cancelled and set to null
        assertNull(conn.timeoutFuture);
    }

    // ==================== cleanupConnection with clientKey ====================

    @Test
    public void testCleanupConnectionClosesClientChannel() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        // Trigger registerClientRead through upgrade detection
        conn.isUpgrade = true;
        conn.clientH2 = false;
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols\r\n".getBytes());
        conn.checkDataReceived(buf);
        assertNotNull(conn.clientKey);
        worker.cleanupConnection(conn);
        assertTrue(conn.closed);
    }

    // ==================== clearConnections with matching route ====================

    @Test
    public void testClearConnectionsMatching() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        // Connection is registered in HttpProxyRoute, not constructor; add manually
        HttpProxyConnection conn = new HttpProxyConnection(1L, "myRoute", ctx, ctx, worker);
        worker.connections.put(1L, conn);
        worker.clearConnections("myRoute");
        assertTrue(conn.closed);
    }

    // ==================== registerClientRead idempotent ====================

    @Test
    public void testRegisterClientReadTwice() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.isUpgrade = true;
        conn.clientH2 = false;
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols\r\n".getBytes());
        conn.checkDataReceived(buf);
        assertNotNull(conn.clientKey);
        // Second call should be no-op
        conn.checkDataReceived(buf);
        assertNotNull(conn.clientKey);
    }

    // ==================== proxy connection + request helpers ====================

    private static HttpProxyConnection proxyConn() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext clientCtx = mock(ChannelContext.class);
        ChannelContext targetCtx = mock(ChannelContext.class);
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        when(clientCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(targetCtx.getHandShakedApplicationProtocol()).thenReturn("h2");
        when(clientCtx.channel()).thenReturn(ch);
        when(targetCtx.channel()).thenReturn(ch);
        return new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
    }

    private static HttpRequest decodeRequest(String raw) throws Exception {
        final HttpRequest[] out = {null};
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 0) {
            @Override
            public int write(ByteBuffer b) {
                return b.remaining();
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                out[0] = (HttpRequest) msg;
            }
        });
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        byte[] data = raw.getBytes("ISO-8859-1");
        d.decode(data, 0, data.length, ctx);
        return out[0];
    }

    // ==================== H2H2ProxyAdapter ====================

    @Test
    public void testH2H2ProxyAdapterConstructor() throws Exception {
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(proxyConn());
        assertNotNull(adapter);
    }

    @Test
    public void testH2H2ProxyAdapterSimpleMethods() throws Exception {
        HttpProxyConnection conn = proxyConn();
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);
        assertTrue(adapter.tryAcquire());
        adapter.receiveResponse(conn.targetCtx);
        // onData isTarget=false -> early return (true branch)
        adapter.onData(ByteBuffer.allocate(0), conn.clientCtx, false, conn);
        // onData isTarget=true -> decode path (else branch)
        adapter.onData(ByteBuffer.allocate(8), conn.clientCtx, true, conn);
    }

    // ==================== H1H2ProxyAdapter (extends H2H2) ====================

    @Test
    public void testH1H2ProxyAdapterConstructor() throws Exception {
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(proxyConn());
        assertNotNull(adapter);
    }

    @Test
    public void testH1H2ProxyAdapterSimpleMethods() throws Exception {
        HttpProxyConnection conn = proxyConn();
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        assertTrue(adapter.tryAcquire());
        adapter.receiveResponse(conn.targetCtx);
        adapter.onData(ByteBuffer.allocate(0), conn.clientCtx, false, conn);
    }

    @Test
    public void testH1H2ProxyAdapterSendRequestGetNoBody() throws Throwable {
        HttpProxyConnection conn = proxyConn();
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        HttpRequest req = decodeRequest("GET /hello HTTP/1.1\r\nHost: localhost\r\nAccept: */*\r\n\r\n");
        adapter.sendRequest(req, conn.targetCtx);
    }

    @Test
    public void testH1H2ProxyAdapterSendRequestPostWithBody() throws Throwable {
        HttpProxyConnection conn = proxyConn();
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        HttpRequest req = decodeRequest("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello");
        adapter.sendRequest(req, conn.targetCtx);
    }

    @Test
    public void testH1H2ProxyAdapterOnHandle() throws Throwable {
        HttpProxyConnection conn = proxyConn();
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        HttpRequest req = decodeRequest("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
        adapter.sendRequest(req, conn.targetCtx);
        // build a client-side response stream matching the registered stream id (1)
        Http2ClientStream clientStream = Http2TestHelpers.responseStream(
                adapter.clientReader, 1, conn.targetCtx, 200, 5, "text/plain", "hello".getBytes());
        adapter.onHandle(conn.targetCtx, clientStream);
    }

    @Test
    public void testH1H2ProxyAdapterOnHandleUnknownStream() throws Throwable {
        HttpProxyConnection conn = proxyConn();
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        Http2ClientStream cs = Http2TestHelpers.responseStream(adapter.clientReader, 999, conn.targetCtx, 200, 0, null, new byte[0]);
        adapter.onHandle(conn.targetCtx, cs);
    }

    @Test
    public void testH1H2ProxyAdapterOnHandleServerProtocolError() throws Throwable {
        HttpProxyConnection conn = proxyConn();
        H1H2ProxyAdapter adapter = new H1H2ProxyAdapter(conn);
        Http2ClientStream cs = Http2TestHelpers.errorStream(adapter.clientReader, 1, conn.targetCtx);
        adapter.onHandle(conn.targetCtx, cs);
    }

    @Test
    public void testH2H2ProxyAdapterOnHandleUnknownStream() throws Throwable {
        HttpProxyConnection conn = proxyConn();
        H2H2ProxyAdapter adapter = new H2H2ProxyAdapter(conn);
        Http2ClientStream cs = Http2TestHelpers.responseStream(adapter.clientReader, 999, conn.targetCtx, 200, 0, null, new byte[0]);
        adapter.onHandle(conn.targetCtx, cs);
    }
}
