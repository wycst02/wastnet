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
 * Coverage tests for DefaultUpgradeHandler.
 */
class DefaultUpgradeHandlerCoverageTest {

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
        // No exception = success (early return at handleWebSocket L65)
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

    // ==================== upgrade: beforeHandshake sets binding (L198-200) ====================

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
        // L196 false branch: beforeHandshake returns false
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
        // L170 true (contains HTTP2-Settings), L171 false (doesn't contain Upgrade)
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeader(anyString(), anyBoolean())).thenReturn("h2c", "HTTP2-Settings");
        assertFalse(DefaultUpgradeHandler.isH2cUpgradeRequest(req));
    }

    // ==================== handleWebSocket: onError throws (inner catch L98-99) ====================

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

    // ==================== upgrade: WebSocket success full path (L217-241) ====================

    @Test
    void testUpgradeWebSocketSuccess() throws Exception {
        // Full valid ws upgrade headers -> handshake succeeds, covers L217-241
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.contextId()).thenReturn("test-ws-id");
        // reader().upgrade(holder) is the protocol switch at L240; stub to no-op via spy
        // reader().upgrade(holder) is the protocol switch at L240; inject a mock inner reader via reflection
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

    // ==================== upgrade: origin rejected (L207-211) ====================

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

    // ==================== upgrade: handshake returns null (L216 false branch) ====================

    @Test
    void testUpgradeHandshakeReturnsNull() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/ws/nokey");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        // Connection not "Upgrade" -> isWebSocketUpgradeRequest false -> handshake returns null (L216 false)
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

    // ==================== onClosed with non-null binding (L259-260) ====================

    @Test
    void testOnClosedWithBinding() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        WebSocketResource resource = mock(WebSocketResource.class);
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
        handler.onClosed(mockCtx);
        verify(resource, atLeastOnce()).handleOnClose(mockCtx);
    }

    // ==================== upgrade: h2c resource but not an h2c request (L203 false, L246 false, L183 false) ====================

    @Test
    void testUpgradeH2cResourceNotH2cRequest() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        HttpRequest mockReq = mock(HttpRequest.class);
        when(mockReq.getRequestUri()).thenReturn("/h2c/x");
        when(mockReq.getHttpVersion()).thenReturn(io.github.wycst.wastnet.http.HttpVersion.HTTP_1_1);
        // Upgrade: h2c but Connection lacks HTTP2-Settings -> isH2cUpgradeRequest false -> L246 false
        when(mockReq.getHeader(HttpHeaderNormalized.getUpgrade(), true)).thenReturn("h2c");
        when(mockReq.getHeader(HttpHeaderNormalized.getConnection(), true)).thenReturn("Upgrade");
        when(mockReq.getHeader("HTTP2-Settings", true)).thenReturn(null);

        // h2c("/h2c") registers a non-websocket (h2c) resource, triggering the else branch at L244
        handler.h2c("/h2c");
        assertFalse(handler.upgrade(mockReq, mockCtx));
    }

    // ==================== upgrade: beforeHandshake sets ctx.binding (L218 true -> L219) ====================

    // ==================== handleCloseFrame: empty close payload (L130 false branch) ====================

    @Test
    void testHandleCloseEmptyPayload() throws Throwable {
        ChannelContext mockCtx = mock(ChannelContext.class);
        WebSocketConnection mockConn = mock(WebSocketConnection.class);
        WebSocketResource resource = mock(WebSocketResource.class);
        when(mockCtx.binding()).thenReturn(new UpgradeWebSocketHolder(resource, mockConn));
        // close frame with 1-byte payload (data.length >= 2 is false -> L130 false branch),
        // empty-frame path still closes the context (L144)
        handler.handle(mockCtx, wsFrame(WebSocketFrame.FrameType.CLOSE, new byte[]{0x03}, true));
        verify(mockCtx, atLeastOnce()).close();
    }
}
