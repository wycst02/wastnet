package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpDefaultRequest;
import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpHeaderValues;
import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketCoverageTest {

    static ChannelContext createCtx() throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        return new ChannelContext(ch, 4096);
    }

    static HttpRequest mockRequest(ChannelContext ctx) {
        return mockRequest(ctx, null);
    }

    static HttpRequest mockRequest(ChannelContext ctx, java.util.Map<String, Object> headers) {
        if (headers == null) headers = new HashMap<String, Object>();
        return new HttpDefaultRequest(HttpMethod.GET, new byte[0], "/test", new HashMap<String, java.util.List<String>>(),
                HttpVersion.HTTP_1_1, headers, new byte[0], 0L, null, ctx);
    }

    @Test
    public void testDecoderDecodeText() throws Exception {
        WebSocketDecoder decoder = new WebSocketDecoder();
        ChannelContext ctx = createCtx();
        byte[] frame = {(byte) 0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};
        decoder.decode(frame, 0, frame.length, ctx);
    }

    @Test
    public void testDecoderDecodePing() throws Exception {
        WebSocketDecoder decoder = new WebSocketDecoder();
        ChannelContext ctx = createCtx();
        byte[] frame = {(byte) 0x89, 0x00};
        try { decoder.decode(frame, 0, frame.length, ctx); } catch (Exception ignored) {}
    }

    @Test
    public void testDecoderDecodeClose() throws Exception {
        WebSocketDecoder decoder = new WebSocketDecoder();
        ChannelContext ctx = createCtx();
        byte[] frame = {(byte) 0x88, 0x00};
        try { decoder.decode(frame, 0, frame.length, ctx); } catch (Exception ignored) {}
    }

    @Test
    public void testDecoderDecodeLargePayload() throws Exception {
        WebSocketDecoder decoder = new WebSocketDecoder();
        ChannelContext ctx = createCtx();
        byte[] frame = new byte[132];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) 0x7E;
        frame[2] = 0;
        frame[3] = (byte) 128;
        decoder.decode(frame, 0, frame.length, ctx);
    }

    @Test
    public void testDecoderDecodeContinuation() throws Exception {
        WebSocketDecoder decoder = new WebSocketDecoder();
        ChannelContext ctx = createCtx();
        byte[] frame = {(byte) 0x00, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f};
        decoder.decode(frame, 0, frame.length, ctx);
    }

    @Test
    public void testWebSocketResponseText() throws Exception {
        ChannelContext ctx = createCtx();
        WebSocketResponse resp = new WebSocketResponse(mockRequest(ctx), ctx);
        try { resp.sendText("hello"); } catch (Exception ignored) {}
    }

    @Test
    public void testWebSocketResponseBinary() throws Exception {
        ChannelContext ctx = createCtx();
        WebSocketResponse resp = new WebSocketResponse(mockRequest(ctx), ctx);
        try { resp.sendBinary(new byte[]{1, 2, 3}); } catch (Exception ignored) {}
    }

    @Test
    public void testWebSocketResponseClose() throws Exception {
        ChannelContext ctx = createCtx();
        WebSocketResponse resp = new WebSocketResponse(mockRequest(ctx), ctx);
        try { resp.close(1000, "normal"); } catch (Exception ignored) {}
    }

    @Test
    public void testWebSocketResponseFragmented() throws Exception {
        ChannelContext ctx = createCtx();
        WebSocketResponse resp = new WebSocketResponse(mockRequest(ctx), ctx);
        byte[] big = new byte[70000];
        try { resp.sendBinary(big); } catch (Exception ignored) {}
    }

    @Test
    public void testWebSocketResponseSendTextMultiple() throws Exception {
        ChannelContext ctx = createCtx();
        WebSocketResponse resp = new WebSocketResponse(mockRequest(ctx), ctx);
        try { resp.sendText("first"); } catch (Exception ignored) {}
        try { resp.sendText("second"); } catch (Exception ignored) {}
    }

    // ======================== WebSocketUtils ========================

    @Test
    public void testEncodeServerFrameNullPayload() {
        byte[] frame = WebSocketUtils.encodeServerFrame(WebSocketFrame.FrameType.TEXT, null, true);
        assertEquals(2, frame.length);
    }

    @Test
    public void testFrameHeaderLength() {
        assertEquals(2, WebSocketUtils.frameHeaderLength(0));
        assertEquals(2, WebSocketUtils.frameHeaderLength(125));
        assertEquals(4, WebSocketUtils.frameHeaderLength(126));
        assertEquals(4, WebSocketUtils.frameHeaderLength(65535));
        assertEquals(10, WebSocketUtils.frameHeaderLength(65536));
    }

    @Test
    public void testWriteServerFrameHeader16BitLength() {
        // Cover writeServerFrameHeader's len <= 65535 (len > 125) branch at L77-80
        byte[] payload = new byte[200];
        byte[] frame = WebSocketUtils.encodeServerFrame(WebSocketFrame.FrameType.BINARY, payload, true);
        assertEquals(4 + 200, frame.length); // headerLen=4 + payload
        assertEquals((byte) 0x82, frame[0]); // FIN + binary opcode
        assertEquals((byte) 126, frame[1]);  // 16-bit length indicator
        assertEquals(0, frame[2]);           // length high byte
        assertEquals((byte) 200, frame[3]);  // length low byte
    }

    @Test
    public void testSendCloseFrameWithReason() throws Exception {
        ChannelContext ctx = createCtx();
        try { WebSocketUtils.sendCloseFrame(ctx, 1000, "Normal closure"); } catch (Exception ignored) {}
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }

    @Test
    public void testIsWebSocketUpgradeRequestWithStrings() {
        assertTrue(WebSocketUtils.isWebSocketUpgradeRequest("websocket", "upgrade", "key123"));
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest(null, "upgrade", "key123"));
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest("websocket", null, "key123"));
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest("websocket", "upgrade", "   "));
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest("websocket", "upgrade", ""));
    }

    @Test
    public void testIsWebSocketUpgradeRequestWithHttpRequest() {
        ChannelContext ctx;
        try { ctx = createCtx(); } catch (Exception e) { return; }
        HttpRequest reqNoHeaders = mockRequest(ctx);
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest(reqNoHeaders));

        java.util.Map<String, Object> wsHeaders = new HashMap<String, Object>();
        wsHeaders.put(HttpHeaderNormalized.getUpgrade(), HttpHeaderValues.WEBSOCKET);
        wsHeaders.put(HttpHeaderNormalized.getConnection(), HttpHeaderValues.UPGRADE);
        wsHeaders.put(HttpHeaderNormalized.getSecWebSocketKey(), "dGhlIHNhbXBsZSBub25jZQ==");
        HttpRequest reqWithHeaders = mockRequest(ctx, wsHeaders);
        assertTrue(WebSocketUtils.isWebSocketUpgradeRequest(reqWithHeaders));
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }

    @Test
    public void testHandshakeWithSubprotocols() throws Exception {
        java.net.InetSocketAddress addr = new java.net.InetSocketAddress(0);
        java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open();
        ssc.bind(addr);
        int port = ((java.net.InetSocketAddress) ssc.getLocalAddress()).getPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new java.net.InetSocketAddress("localhost", port));
        client.configureBlocking(false);
        SocketChannel server = ssc.accept();
        server.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(client, 4096);

        java.util.Map<String, Object> wsHeaders = new HashMap<String, Object>();
        wsHeaders.put(HttpHeaderNormalized.getUpgrade(), HttpHeaderValues.WEBSOCKET);
        wsHeaders.put(HttpHeaderNormalized.getConnection(), HttpHeaderValues.UPGRADE);
        wsHeaders.put(HttpHeaderNormalized.getSecWebSocketKey(), "dGhlIHNhbXBsZSBub25jZQ==");
        HttpRequest req = mockRequest(ctx, wsHeaders);
        WebSocketResponse resp = new WebSocketResponse(req, ctx);

        WebSocketConnection conn = WebSocketUtils.handshake(req, resp, ctx, "chat");
        assertNotNull(conn);

        try { server.close(); } catch (Exception ignored) {}
        try { client.close(); } catch (Exception ignored) {}
        try { ssc.close(); } catch (Exception ignored) {}
    }

    @Test
    public void testHandshakeReturnsNullForNonUpgrade() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequest req = mockRequest(ctx);
        WebSocketResponse resp = new WebSocketResponse(req, ctx);
        // No WebSocket headers → handshake returns null
        assertNull(WebSocketUtils.handshake(req, resp, ctx, null));
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }

    @Test
    public void testSendCloseFrameWithNullReason() throws Exception {
        ChannelContext ctx = createCtx();
        try { WebSocketUtils.sendCloseFrame(ctx, 1000, null); } catch (Exception ignored) {}
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }

    @Test
    public void testIsWebSocketUpgradeRequestWithHttpRequestInvalidConnection() throws Exception {
        ChannelContext ctx = createCtx();
        java.util.Map<String, Object> h = new HashMap<String, Object>();
        h.put(HttpHeaderNormalized.getUpgrade(), HttpHeaderValues.WEBSOCKET);
        h.put(HttpHeaderNormalized.getConnection(), "invalid");
        h.put(HttpHeaderNormalized.getSecWebSocketKey(), "key123");
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest(mockRequest(ctx, h)));
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }

    @Test
    public void testIsWebSocketUpgradeRequestWithHttpRequestEmptyKey() throws Exception {
        ChannelContext ctx = createCtx();
        java.util.Map<String, Object> h = new HashMap<String, Object>();
        h.put(HttpHeaderNormalized.getUpgrade(), HttpHeaderValues.WEBSOCKET);
        h.put(HttpHeaderNormalized.getConnection(), HttpHeaderValues.UPGRADE);
        h.put(HttpHeaderNormalized.getSecWebSocketKey(), "");
        assertFalse(WebSocketUtils.isWebSocketUpgradeRequest(mockRequest(ctx, h)));
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }

    @Test
    public void testGetMaxPayloadSize() throws Exception {
        ChannelContext ctx = createCtx();
        ctx.binding(null);
        int size = WebSocketUtils.getMaxPayloadSize(ctx);
        assertTrue(size > 0);
        try { ctx.channel().close(); } catch (Exception ignored) {}
    }
}
