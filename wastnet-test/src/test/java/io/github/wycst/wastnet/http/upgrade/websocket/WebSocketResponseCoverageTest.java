package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage tests for WebSocketResponse.
 */
class WebSocketResponseCoverageTest {

    // ==================== Null checks (no real channel needed) ====================

    @Test
    void testSendTextNull() {
        WebSocketResponse resp = new WebSocketResponse(mock(HttpRequest.class), mock(ChannelContext.class));
        assertThrows(IllegalArgumentException.class, () -> resp.sendText(null));
    }

    @Test
    void testSendBinaryNull() {
        WebSocketResponse resp = new WebSocketResponse(mock(HttpRequest.class), mock(ChannelContext.class));
        assertThrows(IllegalArgumentException.class, () -> resp.sendBinary(null));
    }

    // ==================== Real channel tests (needed for writeFlush) ====================

    private static class ChannelPair {
        final ServerSocketChannel ssc;
        final SocketChannel client;
        final SocketChannel server;
        final ChannelContext ctx;

        ChannelPair() throws IOException {
            this(65535);
        }

        ChannelPair(int maxPayloadSize) throws IOException {
            ssc = ServerSocketChannel.open();
            ssc.bind(new InetSocketAddress(0));
            int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
            client = SocketChannel.open();
            client.connect(new InetSocketAddress("localhost", port));
            client.configureBlocking(false);
            server = ssc.accept();
            server.configureBlocking(false);
            ctx = new ChannelContext(client, 4096);
            WebSocketResource resource = new WebSocketResource().maxPayloadSize(maxPayloadSize);
            ctx.binding(new UpgradeWebSocketHolder(resource, null));
        }

        void close() {
            try { server.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
            try { ssc.close(); } catch (Exception ignored) {}
        }
    }

    @Test
    void testSendTextSmallPayload() throws Exception {
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.sendText("Hello WebSocket");
        } finally {
            pair.close();
        }
    }

    @Test
    void testSendTextLargePayload() throws Exception {
        // Small chunkSize=5 forces multiple fragmented frames
        ChannelPair pair = new ChannelPair(5);
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.sendText("HelloWorld!");
        } finally {
            pair.close();
        }
    }

    @Test
    void testSendBinarySmallPayload() throws Exception {
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.sendBinary(new byte[]{1, 2, 3, 4, 5});
        } finally {
            pair.close();
        }
    }

    @Test
    void testSendBinaryLargePayload() throws Exception {
        ChannelPair pair = new ChannelPair(5);
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.sendBinary(new byte[100]);
        } finally {
            pair.close();
        }
    }

    @Test
    void testCloseWithReason() throws Exception {
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.close(1000, "Normal closure");
        } finally {
            pair.close();
        }
    }

    @Test
    void testCloseWithNullReason() throws Exception {
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.close(1001, null);
        } finally {
            pair.close();
        }
    }

    @Test
    void testFlush() throws Exception {
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.flush();
        } finally {
            pair.close();
        }
    }

    @Test
    void testGetSupportedSubprotocols() {
        HttpRequest mockReq = mock(HttpRequest.class);
        String secWsProto = io.github.wycst.wastnet.http.HttpHeaderNormalized.getSecWebSocketProtocol();
        when(mockReq.getHeader(eq(secWsProto), anyBoolean())).thenReturn("chat");
        WebSocketResponse resp = new WebSocketResponse(mockReq, mock(ChannelContext.class));
        assertEquals("chat", resp.getSupportedSubprotocols());
    }

    @Test
    void testCreateFactory() {
        HttpRequest mockReq = mock(HttpRequest.class);
        assertNotNull(WebSocketResponse.create(mockReq, mock(ChannelContext.class)));
    }

    @Test
    void testGetChunkSizeZero() throws Exception {
        // maxPayloadSize=0 → getChunkSize() false branch, returns 65535
        ChannelPair pair = new ChannelPair(0);
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.sendText("hi");
        } finally {
            pair.close();
        }
    }

    @Test
    void testCloseAlreadyClosed() throws Exception {
        // close() first time → close() second time sees isChannelClosed → return early (L116)
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            resp.close(1000, "first");
            resp.close(1001, "second");
        } finally {
            pair.close();
        }
    }

    // ==================== L34 subprotocol() ====================

    @Test
    void testSubprotocol() {
        HttpRequest mockReq = mock(HttpRequest.class);
        WebSocketResponse resp = new WebSocketResponse(mockReq, mock(ChannelContext.class));
        // Set Sec-WebSocket-Protocol header via setHeader
        resp.setHeader(io.github.wycst.wastnet.http.HttpHeaderNormalized.getSecWebSocketProtocol(), "chat");
        assertEquals("chat", resp.subprotocol());
    }

    // ==================== L116 close with channel already closed ====================

    @Test
    void testCloseOnClosedChannel() throws Exception {
        ChannelPair pair = new ChannelPair();
        try {
            HttpRequest mockReq = mock(HttpRequest.class);
            WebSocketResponse resp = new WebSocketResponse(mockReq, pair.ctx);
            // Close the actual TCP channel so isChannelClosed() returns true
            pair.client.close();
            resp.close(1000, "channel already closed");
        } finally {
            pair.close();
        }
    }
}
