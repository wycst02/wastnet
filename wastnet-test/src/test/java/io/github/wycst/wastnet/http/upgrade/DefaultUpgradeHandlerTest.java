package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpInternalResponse;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketFrame;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.http.reader.HttpChannelProtocolReader;
import io.github.wycst.wastnet.http.reader.HttpMessageReader;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * tests for DefaultUpgradeHandler.
 */
class DefaultUpgradeHandlerTest {

    // Suppress production log output during tests (JUL-based Log framework)
    static {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(
                "io.github.wycst.wastnet.http.upgrade.DefaultUpgradeHandler");
        log.setLevel(Level.OFF);
        log.setUseParentHandlers(false);
    }

    private final DefaultUpgradeHandler handler = new DefaultUpgradeHandler();

    /** Create WebSocketFrame via reflection (constructor is package-private in websocket package). */
    private static WebSocketFrame wsFrame(WebSocketFrame.FrameType type, byte[] data, boolean fin) throws Exception {
        Constructor<WebSocketFrame> c = WebSocketFrame.class.getDeclaredConstructor(
                WebSocketFrame.FrameType.class, byte[].class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(type, data, fin);
    }

    // ==================== getResource ====================

    @Test
    void testGetResourceNotFound() {
        assertNull(handler.getResource("/nonexistent"));
    }

    @Test
    void testGetResourceExactMatch() {
        handler.ws("/ws/chat", new WebSocketResource());
        assertNotNull(handler.getResource("/ws/chat"));
    }

    @Test
    void testGetResourcePrefixMatch() {
        handler.ws("/ws/chat/", new WebSocketResource());
        assertNotNull(handler.getResource("/ws/chat/user/1001"));
    }

    // ==================== ws / h2c ====================

    @Test
    void testWsRegistration() {
        WebSocketResource r = new WebSocketResource();
        assertSame(r, handler.ws("/test", r));
    }

    @Test
    void testH2cRegistration() {
        handler.h2c("/h2c");
        assertNotNull(handler.getResource("/h2c"));
    }

    // ==================== isH2cUpgradeRequest ====================

    @Test
    void testIsH2cUpgradeRequestValid() {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("h2c", "Upgrade, HTTP2-Settings");
        assertTrue(DefaultUpgradeHandler.isH2cUpgradeRequest(req));
    }

    @Test
    void testIsH2cUpgradeRequestNotH2c() {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("websocket");
        assertFalse(DefaultUpgradeHandler.isH2cUpgradeRequest(req));
    }

    @Test
    void testIsH2cUpgradeRequestMissingConnection() {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("h2c", null);
        assertFalse(DefaultUpgradeHandler.isH2cUpgradeRequest(req));
    }

    // ==================== handle with various frame types ====================

    private void setupBinding(ChannelContext mockCtx) {
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        WebSocketResource resource = new WebSocketResource();
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
    }

    @Test
    void testHandleContinuation() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.CONTINUATION, new byte[0], true));
        verify(mockCtx, atLeastOnce()).binding();
    }

    @Test
    void testHandleText() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.TEXT, "hi".getBytes(), true));
        verify(mockCtx, atLeastOnce()).binding();
    }

    @Test
    void testHandleBinary() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.BINARY, new byte[]{1}, true));
        verify(mockCtx, atLeastOnce()).binding();
    }

    @Test
    void testHandleClose() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.CLOSE, new byte[]{0x03, (byte) 0xE8}, true));
        verify(mockCtx, atLeastOnce()).close();
    }

    @Test
    void testHandleCloseWithReason() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.CLOSE, new byte[]{0x03, (byte) 0xE8, 'o', 'k'}, true));
        verify(mockCtx, atLeastOnce()).close();
    }

    @Test
    void testHandlePing() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.PING, new byte[0], true));
        verify(mockCtx, atLeastOnce()).binding();
    }

    @Test
    void testHandlePingWithData() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.PING, "data".getBytes(), true));
        verify(mockCtx, atLeastOnce()).binding();
    }

    @Test
    void testHandlePong() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        setupBinding(mockCtx);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.PONG, new byte[0], true));
        verify(mockCtx, atLeastOnce()).binding();
    }

    @Test
    void testHandleBindingNullReturnsEarly() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.binding()).thenReturn(null);
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.TEXT, "x".getBytes(), true));
    }

    @Test
    void testHandleThrowsCallsOnErrorAndClose() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        doThrow(new RuntimeException("boom")).when(mockConn).updateActiveTime();
        WebSocketResource resource = new WebSocketResource();
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.TEXT, "hi".getBytes(), true));
        verify(mockConn, atLeastOnce()).close(anyInt(), anyString());
    }

    // ==================== onClosed ====================

    @Test
    void testOnClosedWithNullBinding() throws Exception {
        handler.onClosed(mock(ChannelContext.class));
    }


    @Test
    void testUpgradeBeforeHandshakeSetsBinding() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/ws/bound");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);

        // Real subclass whose beforeHandshake sets binding on ctx via response's ctx
        WebSocketResource res = new WebSocketResource() {
            @Override
            public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
                response.setStatus(io.github.wycst.wastnet.http.HttpStatus.OK);
                try {
                    java.lang.reflect.Field ctxField = io.github.wycst.wastnet.http.HttpDefaultResponse.class
                            .getDeclaredField("ctx");
                    ctxField.setAccessible(true);
                    ChannelContext respCtx = (ChannelContext) ctxField.get(response);
                    respCtx.binding("pre-bound");
                } catch (Exception ignored) {
                }
                return true;
            }
        };
        when(mockCtx.binding()).thenReturn("pre-bound");
        handler.ws("/ws/bound", res);
        assertFalse(handler.upgrade(mockReq, mockCtx));
    }

    @Test
    void testUpgradeBeforeHandshakeReturnsFalse() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/ws/reject");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(anyString(), anyBoolean())).thenReturn(null);

        WebSocketResource res = new WebSocketResource() {
            @Override
            public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
                return false;
            }
        };
        handler.ws("/ws/reject", res);
        assertFalse(handler.upgrade(mockReq, mockCtx));
    }

    // ==================== isH2cUpgradeRequest: Connection has HTTP2-Settings but no Upgrade ====================

    @Test
    void testIsH2cUpgradeRequestMissingUpgradeInConnection() {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("h2c", "HTTP2-Settings");
        assertFalse(DefaultUpgradeHandler.isH2cUpgradeRequest(req));
    }


    @Test
    void testHandleThrowsAndOnErrorAlsoThrows() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        doThrow(new RuntimeException("first boom")).when(mockConn).updateActiveTime();
        WebSocketResource resource = new WebSocketResource() {
            @Override
            public void onError(WebSocketConnection connection, Throwable error) {
                throw new RuntimeException("second boom");
            }
        };
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.TEXT, "hi".getBytes(), true));
        verify(mockConn, atLeastOnce()).close(anyInt(), anyString());
    }


    @Test
    void testUpgradeWebSocketSuccess() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.contextId()).thenReturn("test-ws-id");
        // (HttpChannelProtocolReader is final, so it cannot be spied directly)
        HttpChannelProtocolReader reader = new HttpChannelProtocolReader();
        java.lang.reflect.Field readerField = HttpChannelProtocolReader.class.getDeclaredField("reader");
        readerField.setAccessible(true);
        readerField.set(reader, mock(HttpMessageReader.class));
        doReturn(reader).when(mockCtx).reader();
        doNothing().when(mockCtx).flush();
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/ws/ok");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(HttpHeaderNormalized.getUpgrade(), true)).thenReturn("websocket");
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("Upgrade");
        when(mockReq.getHeader(HttpHeaderNormalized.getSecWebSocketKey(), true)).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
        when(mockReq.getHeader("Sec-WebSocket-Version", true)).thenReturn("13");
        when(mockReq.getHeader(HttpHeaderNormalized.getOrigin(), true)).thenReturn("https://a.com");
        // handshake() calls request.getResponse().handover() to suppress the outer
        // auto 200 OK; the outer response must be non-null (non-null in production).
        when(mockReq.getResponse()).thenReturn(mock(HttpInternalResponse.class));

        WebSocketResource res = new WebSocketResource(0); // timeout 0 -> no scheduling
        res.allowedOrigins("https://a.com");
        handler.ws("/ws/ok", res);
        assertTrue(handler.upgrade(mockReq, mockCtx));
        verify(mockCtx, atLeastOnce()).binding(any());
        // verify the pre-close listener was registered
        verify(mockCtx, atLeastOnce()).addClearListener(any(Runnable.class));
    }


    @Test
    void testUpgradeOriginRejected() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/ws/origin");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(HttpHeaderNormalized.getUpgrade(), true)).thenReturn("websocket");
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("Upgrade");
        when(mockReq.getHeader(HttpHeaderNormalized.getSecWebSocketKey(), true)).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
        when(mockReq.getHeader("Sec-WebSocket-Version", true)).thenReturn("13");
        when(mockReq.getHeader(HttpHeaderNormalized.getOrigin(), true)).thenReturn("https://evil.com");

        WebSocketResource res = new WebSocketResource();
        res.allowedOrigins("https://a.com"); // origin mismatch -> isOriginAllowed false
        handler.ws("/ws/origin", res);
        assertTrue(handler.upgrade(mockReq, mockCtx));
    }


    @Test
    void testUpgradeHandshakeReturnsNull() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/ws/nokey");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(HttpHeaderNormalized.getUpgrade(), true)).thenReturn("websocket");
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("keep-alive");
        when(mockReq.getHeader(HttpHeaderNormalized.getSecWebSocketKey(), true)).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
        when(mockReq.getHeader("Sec-WebSocket-Version", true)).thenReturn("13");
        when(mockReq.getHeader(HttpHeaderNormalized.getOrigin(), true)).thenReturn("https://a.com");

        WebSocketResource res = new WebSocketResource();
        res.allowedOrigins("https://a.com");
        handler.ws("/ws/nokey", res);
        assertFalse(handler.upgrade(mockReq, mockCtx));
    }


    @Test
    void testOnClosedWithBinding() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        WebSocketResource resource = mock(WebSocketResource.class);
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
        handler.onClosed(mockCtx);
        verify(resource, atLeastOnce()).handleOnClose(mockCtx);
    }


    @Test
    void testUpgradeH2cResourceNotH2cRequest() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/h2c/x");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        when(mockReq.getHeader(HttpHeaderNormalized.getUpgrade(), true)).thenReturn("h2c");
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("Upgrade");
        when(mockReq.getHeader("HTTP2-Settings", true)).thenReturn(null);

        handler.h2c("/h2c");
        assertFalse(handler.upgrade(mockReq, mockCtx));
    }



    @Test
    void testHandleCloseEmptyPayload() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        WebSocketResource resource = mock(WebSocketResource.class);
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.CLOSE, new byte[]{0x03}, true));
        verify(mockCtx, atLeastOnce()).close();
    }
}
