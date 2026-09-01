package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * tests for WebSocketConnectionImpl.
 */
class WebSocketConnectionImplTest {

    @Test
    void testRequestGetter() {
        HttpRequest mockReq = mock(HttpRequest.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(mockReq,
                mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertSame(mockReq, conn.request());
    }

    @Test
    void testAccountGetterSetter() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertNull(conn.getAccount());
        conn.setAccount("user123");
        assertEquals("user123", conn.getAccount());
    }

    @Test
    void testGroupIdGetterSetter() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertNull(conn.getGroupId());
        conn.setGroupId("group-x");
        assertEquals("group-x", conn.getGroupId());
    }

    @Test
    void testSendInputStreamNull() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertThrows(IllegalArgumentException.class, () -> conn.sendInputStream(null));
    }

    @Test
    void testSendFileNull() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertThrows(IllegalArgumentException.class, () -> conn.sendFile(null));
    }

    @Test
    void testPushNull() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.push(null);
    }

    @Test
    void testPingClosed() throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.close(1000, "");
        assertThrows(IllegalStateException.class, conn::ping);
    }

    @Test
    void testPongClosed() throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.close(1000, "");
        assertThrows(IllegalStateException.class, conn::pong);
    }

    @Test
    void testDisconnectTwice() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.disconnect();
        conn.disconnect();
    }

    @Test
    void testClose() throws Exception {
        // close() twice (already closed guard) 
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        conn.close(1000, "first");
        conn.close(1001, "second");  // already closed → return
    }

    @Test
    void testSendInputStreamWithData() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        conn.sendInputStream(new ByteArrayInputStream("x".getBytes()));
        verify(mockCtx, atLeastOnce()).writeFlush(any(ByteBuffer.class));
    }

    // ==================== Simple branch for sendText/sendBinary/pong etc. ====================

    @Test
    void testSendTextNull() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertThrows(IllegalArgumentException.class, () -> conn.sendText(null));
    }

    @Test
    void testSendTextClosed() throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.close(1000, "");
        assertThrows(IllegalStateException.class, () -> conn.sendText("x"));
    }

    @Test
    void testSendTextNormal() throws Exception {
        WebSocketResponse mockResp = mock(WebSocketResponse.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mockResp, mock(ChannelContext.class));
        conn.sendText("hello");
        verify(mockResp).sendText("hello");
    }

    @Test
    void testSendBinaryNull() {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        assertThrows(IllegalArgumentException.class, () -> conn.sendBinary(null));
    }

    @Test
    void testSendBinaryClosed() throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.close(1000, "");
        assertThrows(IllegalStateException.class, () -> conn.sendBinary(new byte[1]));
    }

    @Test
    void testSendBinaryNormal() throws Exception {
        WebSocketResponse mockResp = mock(WebSocketResponse.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mockResp, mock(ChannelContext.class));
        byte[] data = {1, 2, 3};
        conn.sendBinary(data);
        verify(mockResp).sendBinary(data);
    }

    @Test
    void testPongNormal() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), new WebSocketResponse(mock(HttpRequest.class), mockCtx), mockCtx);
        conn.pong();
        verify(mockCtx).write(any(ByteBuffer.class));
    }

    @Test
    void testSendInputStreamEmpty() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), new WebSocketResponse(mock(HttpRequest.class), mockCtx), mockCtx);
        conn.sendInputStream(new ByteArrayInputStream(new byte[0]));
        verify(mockCtx).write(any(ByteBuffer.class));
    }

    @Test
    void testSendInputStreamMultiChunk() throws Exception {
        WebSocketResource resource = new WebSocketResource().maxPayloadSize(100);
        UpgradeWebSocketHolder holder = new UpgradeWebSocketHolder(resource, mock(WebSocketConnection.class));
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.binding()).thenReturn(holder);
        byte[] bigData = new byte[250];
        for (int i = 0; i < bigData.length; ++i) bigData[i] = (byte) i;
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), new WebSocketResponse(mock(HttpRequest.class), mockCtx), mockCtx);
        conn.sendInputStream(new ByteArrayInputStream(bigData));
        verify(mockCtx, atLeast(2)).write(any(ByteBuffer.class));
    }

    @Test
    void testSendInputStreamExactMultiples() throws Exception {
        WebSocketResource resource = new WebSocketResource().maxPayloadSize(100);
        UpgradeWebSocketHolder holder = new UpgradeWebSocketHolder(resource, mock(WebSocketConnection.class));
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.binding()).thenReturn(holder);
        byte[] exactData = new byte[200];
        for (int i = 0; i < exactData.length; ++i) exactData[i] = (byte) (i & 0xFF);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), new WebSocketResponse(mock(HttpRequest.class), mockCtx), mockCtx);
        conn.sendInputStream(new ByteArrayInputStream(exactData));
        verify(mockCtx, atLeast(3)).write(any(ByteBuffer.class));
    }

    @Test
    void testIsClosedNormal() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        assertFalse(conn.isClosed());
    }

    @Test
    void testCloseIOException() throws Exception {
        suppressWsErrorLog();
        try {
            WebSocketResponse mockResp = mock(WebSocketResponse.class);
            doThrow(new IOException("test")).when(mockResp).close(anyInt(), anyString());
            ChannelContext mockCtx = mock(ChannelContext.class);
            WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                    mock(HttpRequest.class), mockResp, mockCtx);
            assertThrows(IOException.class, () -> conn.close(1000, "test"));
        } finally {
            restoreWsErrorLog();
        }
    }

    @Test
    void testId() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.contextId()).thenReturn("conn-001");
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        assertEquals("conn-001", conn.id());
    }

    @Test
    void testPushSuccess() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), new WebSocketResponse(mock(HttpRequest.class), mockCtx), mockCtx);
        conn.push(new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "ok".getBytes(), true));
        verify(mockCtx).write(any(ByteBuffer.class));
    }

    @Test
    void testSendFileNormal() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(
                new WebSocketResource().maxPayloadSize(100), mock(WebSocketConnection.class)));
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), new WebSocketResponse(mock(HttpRequest.class), mockCtx), mockCtx);
        java.io.File f = java.io.File.createTempFile("wstest", ".tmp");
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        fos.write("Hello World WebSocket File Transfer".getBytes());
        fos.close();
        try {
            conn.sendFile(f);
        } catch (Exception ignored) {
        } finally {
            f.delete();
        }
    }

    @Test
    void testDisconnectCatchIOException() {
        // disconnect() calls close(1000, "Disconnected"), which calls response.close()
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketResponse mockResp = mock(WebSocketResponse.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mockResp, mockCtx);
        suppressWsErrorLog();
        try {
            conn.disconnect();
        } finally {
            restoreWsErrorLog();
        }
        // disconnect() calls close(1000, ...) which catches IOException internally
    }

    @Test
    void testCloseCtxCloseException() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        doThrow(new RuntimeException("ctx close error")).when(mockCtx).close();
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        suppressWsErrorLog();
        try {
            conn.close(1000, "");
        } finally {
            restoreWsErrorLog();
        }
    }

    // ==================== Timeout detection (via reflection) ====================

    // ==================== Log suppression for expected ERROR output ====================

    private static void suppressWsErrorLog() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("i.g.w.w.h.u.w.WebSocketConnectionImpl");
        logger.setLevel(java.util.logging.Level.OFF);
        logger.setFilter(new java.util.logging.Filter() {
            public boolean isLoggable(java.util.logging.LogRecord record) {
                return false;
            }
        });
    }

    private static void restoreWsErrorLog() {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("i.g.w.w.h.u.w.WebSocketConnectionImpl");
        logger.setLevel(java.util.logging.Level.ALL);
        logger.setFilter(null);
    }

    private Runnable getDetectionTask(WebSocketConnectionImpl conn) throws Exception {
        Field f = WebSocketConnectionImpl.class.getDeclaredField("detectionTask");
        f.setAccessible(true);
        Runnable task = (Runnable) f.get(conn);
        assertNotNull(task);
        return task;
    }

    private void setLastActiveTime(WebSocketConnectionImpl conn, long time) throws Exception {
        Field f = WebSocketConnectionImpl.class.getDeclaredField("lastActiveTime");
        f.setAccessible(true);
        f.set(conn, time);
    }

    /** Call timeoutDetection but catch the NPE from final method scheduleDetectionTask. */
    private WebSocketConnectionImpl createWithTimeout(String strategyName) throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        WebSocketResource.TimeoutStrategy strategy = "PING".equals(strategyName)
                ? WebSocketResource.TimeoutStrategy.PING
                : WebSocketResource.TimeoutStrategy.DISCONNECT;
        try { conn.timeoutDetection(1, strategy); } catch (Exception ignored) { }
        return conn;
    }

    @Test
    void testTimeoutDetectionClosed() throws Exception {
        // Must extract detectionTask BEFORE close() clears it via stopTimeoutDetection()
        WebSocketConnectionImpl conn = createWithTimeout("PING");
        Runnable task = getDetectionTask(conn);
        conn.close(1000, "");
        task.run();
    }

    @Test
    void testTimeoutNotElapsed() throws Exception {
        WebSocketConnectionImpl conn = createWithTimeout("PING");
        conn.updateActiveTime();
        try { getDetectionTask(conn).run(); } catch (Exception ignored) { }
    }

    @Test
    void testTimeoutDoubleElapsed() throws Exception {
        WebSocketConnectionImpl conn = createWithTimeout("PING");
        setLastActiveTime(conn, System.currentTimeMillis() - 3000);
        try { getDetectionTask(conn).run(); } catch (Exception ignored) { }
    }

    @Test
    void testTimeoutPingStrategy() throws Exception {
        WebSocketConnectionImpl conn = createWithTimeout("PING");
        setLastActiveTime(conn, System.currentTimeMillis() - 1500);
        try { getDetectionTask(conn).run(); } catch (Exception ignored) { }
    }

    @Test
    void testTimeoutDisconnectStrategy() throws Exception {
        WebSocketConnectionImpl conn = createWithTimeout("DISCONNECT");
        setLastActiveTime(conn, System.currentTimeMillis() - 1500);
        try { getDetectionTask(conn).run(); } catch (Exception ignored) { }
    }

    /** Second call: detectionTask != null → skip initDetectionTask ( false branch). */
    @Test
    void testTimeoutDetectionTwice() throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        try { conn.timeoutDetection(1, WebSocketResource.TimeoutStrategy.DISCONNECT); } catch (Exception ignored) { }
        try { conn.timeoutDetection(1, WebSocketResource.TimeoutStrategy.PING); } catch (Exception ignored) { }
    }

    // ==================== Additional WebSocketConnectionImpl gaps ====================

    @Test
    void testSendInputStreamClosed() throws Exception {
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mock(ChannelContext.class));
        conn.close(1000, "");
        assertThrows(IllegalStateException.class, () -> conn.sendInputStream(new ByteArrayInputStream("x".getBytes())));
    }

    @Test
    void testSendFileWithRealFile() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketResponse mockResp = mock(WebSocketResponse.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(mock(HttpRequest.class), mockResp, mockCtx);
        java.io.File f = java.io.File.createTempFile("wstest", ".tmp");
        try {
            conn.sendFile(f);
        } catch (Exception ignored) {
        } finally {
            f.delete();
        }
    }

    @Test
    void testIsClosedChannelClosed() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.isChannelClosed()).thenReturn(true);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        assertTrue(conn.isClosed());
    }

    @Test
    void testSubprotocol() {
        WebSocketResponse mockResp = mock(WebSocketResponse.class);
        when(mockResp.subprotocol()).thenReturn("chat");
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mockResp, mock(ChannelContext.class));
        assertEquals("chat", conn.subprotocol());
    }

    @Test
    void testSetGetAttribute() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        conn.setAttribute("key1", "value1");
        conn.setAttribute("key2", 42);
        verify(mockCtx).setAttribute("key1", "value1");
        verify(mockCtx).setAttribute("key2", 42);
        when(mockCtx.getAttribute("key1")).thenReturn("value1");
        assertEquals("value1", conn.getAttribute("key1"));
    }

    @Test
    void testStopTimeoutDetectionCancelled() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        // First call sets up detectionTask
        try { conn.timeoutDetection(1, WebSocketResource.TimeoutStrategy.DISCONNECT); } catch (Exception ignored) {}
        // Get the future and cancel it
        java.lang.reflect.Field f = WebSocketConnectionImpl.class.getDeclaredField("timeoutTaskFuture");
        f.setAccessible(true);
        java.util.concurrent.ScheduledFuture<?> future = (java.util.concurrent.ScheduledFuture<?>) f.get(conn);
        if (future != null) future.cancel(true);
        // Second setTimeoutDetection should see isCancelled()=true
        try { conn.timeoutDetection(1, WebSocketResource.TimeoutStrategy.DISCONNECT); } catch (Exception ignored) {}
    }

    @Test
    void testPushThrowsException() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketResponse mockResp = mock(WebSocketResponse.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(mock(HttpRequest.class), mockResp, mockCtx);
        try {
            conn.push(new WebSocketFrame(WebSocketFrame.FrameType.TEXT, "msg".getBytes(), true));
        } catch (IllegalStateException e) {
            // Expected if writeFlush fails
        }
    }

    @Test
    void testGetChunkSizeDefault() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnectionImpl conn = new WebSocketConnectionImpl(
                mock(HttpRequest.class), mock(WebSocketResponse.class), mockCtx);
        // With resource having maxPayloadSize=0 → getChunkSize returns 65535
        // Need to set up the binding to avoid NPE
        try { conn.timeoutDetection(1, WebSocketResource.TimeoutStrategy.DISCONNECT); } catch (Exception ignored) {}
        // send text uses getChunkSize internally
        try { conn.sendText("hi"); } catch (Exception ignored) {}
    }


    private static class RealChannelPair {
        final java.nio.channels.ServerSocketChannel ssc;
        final java.nio.channels.SocketChannel client;
        final java.nio.channels.SocketChannel server;
        final ChannelContext ctx;

        RealChannelPair() throws Exception {
            ssc = java.nio.channels.ServerSocketChannel.open();
            ssc.bind(new java.net.InetSocketAddress(0));
            int port = ((java.net.InetSocketAddress) ssc.getLocalAddress()).getPort();
            client = java.nio.channels.SocketChannel.open();
            client.connect(new java.net.InetSocketAddress("localhost", port));
            client.configureBlocking(false);
            server = ssc.accept();
            server.configureBlocking(false);
            ctx = new ChannelContext(client, 4096);
            io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder holder =
                    new io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder(
                            new WebSocketResource(), null);
            ctx.binding(holder);
        }

        void close() {
            try { server.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
            try { ssc.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void testTimeoutPingStrategyRealChannel() throws Exception {
        RealChannelPair pair = new RealChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse realResp = new WebSocketResponse(mockReq, pair.ctx);
            WebSocketConnectionImpl conn = new WebSocketConnectionImpl(mockReq, realResp, pair.ctx);
            try { conn.timeoutDetection(1, WebSocketResource.TimeoutStrategy.PING); } catch (Exception ignored) {}
            java.lang.reflect.Field f = WebSocketConnectionImpl.class.getDeclaredField("lastActiveTime");
            f.setAccessible(true);
            f.set(conn, System.currentTimeMillis() - 1500);
            // scheduleDetectionTask inside the task will NPE (no worker), but ping() executes first
            try { getDetectionTask(conn).run(); } catch (Exception ignored) {}
        } finally {
            pair.close();
        }
    }

    @Test
    void testTimeoutNotElapsedRealChannel() throws Exception {
        RealChannelPair pair = new RealChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse realResp = new WebSocketResponse(mockReq, pair.ctx);
            WebSocketConnectionImpl conn = new WebSocketConnectionImpl(mockReq, realResp, pair.ctx);
            try { conn.timeoutDetection(60, WebSocketResource.TimeoutStrategy.PING); } catch (Exception ignored) {}
            conn.updateActiveTime();
            try { getDetectionTask(conn).run(); } catch (Exception ignored) {}
        } finally {
            pair.close();
        }
    }
}
