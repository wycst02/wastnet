package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpProxyConnection}.
 * <p>
 * Covers checkDataReceived branches, resolveAdapter paths,
 * registerClientRead, forwardToTarget, close.
 *
 * @author wangyc
 */
public class HttpProxyConnectionTest {

    private static final HttpProxyWorkerManager manager = new HttpProxyWorkerManager();

    private static ChannelContext createCtx() throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        return new ChannelContext(ch, 4096);
    }

    private static HttpProxyWorker createWorker() throws IOException {
        return new HttpProxyWorker(manager);
    }

    // ==================== checkDataReceived: first call sets dataReceived ====================

    @Test
    public void testCheckDataReceivedFirstCallSetsFlag() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        assertFalse(conn.dataReceived);
        conn.checkDataReceived(ByteBuffer.allocate(10));
        assertTrue(conn.dataReceived);
    }

    // ==================== checkDataReceived: second call is no-op ====================

    @Test
    public void testCheckDataReceivedSecondCallNoOp() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.dataReceived = true;  // already received
        conn.checkDataReceived(ByteBuffer.allocate(10));
        // no-op, no exception
    }

    // ==================== checkDataReceived: cancel timeout ====================

    @Test
    public void testCheckDataReceivedCancelsTimeout() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        // Schedule a timeout future (use a real one from HttpProxyWorkerManager)
        conn.timeoutFuture = manager.scheduleTimeout(new Runnable() {
            public void run() {
            }
        }, 10000);
        assertNotNull(conn.timeoutFuture);
        conn.checkDataReceived(ByteBuffer.allocate(10));
        // Timeout should be cancelled
        assertNull(conn.timeoutFuture);
    }

    // ==================== checkDataReceived: upgrade detection with valid 101 ====================

    @Test
    public void testCheckDataReceivedUpgradeDetects101() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.isUpgrade = true;
        conn.clientH2 = false;
        // Buffer starting with "HTTP/1.1 101"
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1 101 Switching Protocols\r\n".getBytes());
        conn.checkDataReceived(buf);
        // Upgrade 101 detected → registerClientRead called → clientKey should be set
        assertNotNull(conn.clientKey);
    }

    // ==================== checkDataReceived: upgrade with insufficient bytes ====================

    @Test
    public void testCheckDataReceivedUpgradeInsufficientBytes() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        conn.isUpgrade = true;
        conn.clientH2 = false;
        // Buffer with less than 12 bytes
        ByteBuffer buf = ByteBuffer.wrap("HTTP/1.1".getBytes());
        conn.checkDataReceived(buf);
        // Insufficient bytes → isUpgrade set to false
        assertFalse(conn.isUpgrade);
    }

    // ==================== resolveAdapter via constructor ====================

    @Test
    public void testConstructorWithH1BothCreatesPassthrough() throws Exception {
        // Real H1 ChannelContexts → both protocols null → PASSTHROUGH (not H2H1ProxyAdapter)
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        assertFalse(conn.adapter instanceof H2H1ProxyAdapter);
        assertFalse(conn.clientH2);
    }

    // ==================== forwardToTarget ====================

    @Test
    public void testForwardToTargetWithTimeout() throws Throwable {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);

        // Create a simplified config with readTimeout
        HttpProxyConfig config = HttpProxyConfig.target("localhost:8080")
                .readTimeout(5000);

        // Use a mock request (forwardToTarget calls adapter.sendRequest which uses the mock)
        HttpRequest request = mock(HttpRequest.class);
        conn.forwardToTarget(request, config);
        // timeoutFuture should be set
        assertNotNull(conn.timeoutFuture);
    }

    @Test
    public void testForwardToTargetWithoutTimeout() throws Throwable {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);

        // Config with readTimeout=0 → no timeout scheduling
        HttpProxyConfig config = HttpProxyConfig.target("localhost:8080")
                .readTimeout(0);

        HttpRequest request = mock(HttpRequest.class);
        conn.forwardToTarget(request, config);
        // No timeout scheduled
        assertNull(conn.timeoutFuture);
    }

    @Test
    public void testForwardToTargetWithUpgrade() throws Throwable {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);

        // Config with upgrade=true, but request is not WebSocket → isUpgrade stays false
        HttpProxyConfig config = HttpProxyConfig.target("localhost:8080")
                .upgrade(true)
                .readTimeout(3000);

        HttpRequest request = mock(HttpRequest.class);
        // Mock headers: no Upgrade header → not a websocket upgrade
        when(request.getHeader(anyString(), anyBoolean())).thenReturn(null);
        conn.forwardToTarget(request, config);
        // isUpgrade should be false (request is not a WebSocket upgrade)
        assertFalse(conn.isUpgrade);
    }

    // ==================== close delegates to cleanup ====================

    @Test
    public void testCloseSetsClosed() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        assertFalse(conn.closed);
        conn.close();
        assertTrue(conn.closed);
    }

    // ==================== isTargetClosed ====================

    @Test
    public void testIsTargetClosed() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext ctx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", ctx, ctx, worker);
        // Channel is still open
        assertFalse(conn.isTargetClosed());
    }

    // ==================== timeout task: data not received → cleanup ====================

    @Test
    public void testTimeoutTaskTriggersCleanup() throws Exception {
        HttpProxyWorker worker = createWorker();
        ChannelContext clientCtx = createCtx();
        ChannelContext targetCtx = createCtx();
        HttpProxyConnection conn = new HttpProxyConnection(1L, "route", clientCtx, targetCtx, worker);
        // dataReceived is still false → timeoutTask should clean up
        conn.timeoutTask.run();
        assertTrue(conn.closed);
    }
}
