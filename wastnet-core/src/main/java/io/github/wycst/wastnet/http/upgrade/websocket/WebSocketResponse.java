package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * WebSocket response implementation
 * Extends HTTP default response, provides WebSocket specific functionality
 *
 * @since 2024-1-27
 * @author wangyc
 */
public class WebSocketResponse extends HttpDefaultResponse {

    private final String supportedSubprotocols;

    private static final HttpBuf SHARED_EMPTY = HttpBuf.empty();

    public WebSocketResponse(HttpRequest request, ChannelContext ctx) {
        super(request, ctx);
        setStatus(HttpStatus.SWITCHING_PROTOCOLS);
        putHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        putHeader(HttpHeaderNames.CONNECTION, HttpHeaderNames.UPGRADE);
        putHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        this.supportedSubprotocols = request.getHeader(HttpHeaderNormalized.getSecWebSocketProtocol(), true);
    }

    @Override
    protected int initialBodySize() {
        return 0;
    }

    public String getSupportedSubprotocols() {
        return supportedSubprotocols;
    }

    public String subprotocol() {
        return directlyGetHeader(HttpHeaderNormalized.getSecWebSocketProtocol());
    }

    /**
     * Release HTTP scaffolding after the 101 handshake is flushed.
     * The WS session writes frames directly via {@code ctx}, so the header
     * buffer and the phantom response can be GC'd.
     */
    void releaseAfterHandshake() {
        this.headerBuf = SHARED_EMPTY;
        // The 101 response is already flushed; the handshake-only headers are no longer needed
        // (keep Sec-WebSocket-Protocol so subprotocol() still works).
        directlyRemoveHeader(HttpHeaderNames.UPGRADE);
        directlyRemoveHeader(HttpHeaderNames.CONNECTION);
        directlyRemoveHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
        // detach the phantom default response held by request.response so it can be GC'd
        if (this.request instanceof HttpInternalRequest) {
            ((HttpInternalRequest) this.request).setResponse(this);
        }
    }

    public static WebSocketResponse create(HttpRequest request, ChannelContext ctx) {
        return new WebSocketResponse(request, ctx);
    }

    @Override
    public void flush() throws IOException {
        ensureHeadersSent();
        ctx.flush();
        // 101 response already sent; release the HTTP scaffolding (headerBuf, etc.). Idempotent.
        releaseAfterHandshake();
    }

    final void writeFlush(byte[] buf) throws IOException {
        ctx.writeFlush(buf);
    }

    /**
     * Send WebSocket text message. Large payloads are auto-split into continuation frames.
     *
     * @param message text message
     * @throws IOException throws IO exception when sending fails
     */
    public void sendText(String message) throws IOException {
        Objects.requireNonNull(message, "Message cannot be null");
        sendText(message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Send WebSocket text message from raw UTF-8 bytes (zero-copy).
     * The bytes are framed directly as a TEXT message without an intermediate
     * String decode/encode. Large payloads are auto-split into continuation frames.
     *
     * @param data text payload in UTF-8 bytes
     * @throws IOException throws IO exception when sending fails
     */
    public void sendText(byte[] data) throws IOException {
        Objects.requireNonNull(data, "Data cannot be null");
        final int chunkSize = getChunkSize();
        if (data.length <= chunkSize) {
            sendFrame(WebSocketFrame.FrameType.TEXT, data, true);
        } else {
            sendFragmented(WebSocketFrame.FrameType.TEXT, data, chunkSize);
        }
    }

    /**
     * Send WebSocket binary data. Large payloads are auto-split into continuation frames.
     *
     * @param data binary data
     * @throws IOException throws IO exception when sending fails
     */
    public void sendBinary(byte[] data) throws IOException {
        Objects.requireNonNull(data, "Data cannot be null");
        final int chunkSize = getChunkSize();
        if (data.length <= chunkSize) {
            sendFrame(WebSocketFrame.FrameType.BINARY, data, true);
        } else {
            sendFragmented(WebSocketFrame.FrameType.BINARY, data, chunkSize);
        }
    }

    /**
     * Split a large payload into continuation frames.
     */
    private void sendFragmented(WebSocketFrame.FrameType type, byte[] data, int chunkSize) throws IOException {
        int offset = 0;
        int remaining = data.length;
        boolean first = true;
        while (remaining > 0) {
            int len = Math.min(chunkSize, remaining);
            boolean isLast = (len == remaining);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);
            WebSocketFrame.FrameType frameType = first ? type : WebSocketFrame.FrameType.CONTINUATION;
            sendFrame(frameType, chunk, isLast);
            offset += len;
            remaining -= len;
            first = false;
        }
    }

    /**
     * Close WebSocket connection
     *
     * @param code   close code
     * @param reason close reason
     * @throws IOException throws IO exception when closing fails
     */
    public void close(int code, String reason) throws IOException {
        if (ctx.isChannelClosed()) return;
        // reuse WebSocketUtils.sendCloseFrame which builds the close payload (with 123-byte reason truncation) and sends it
        WebSocketUtils.sendCloseFrame(ctx, code, reason);
    }

    /**
     * Get chunk size for splitting large payloads, derived from endpoint's maxPayloadSize.
     */
    private int getChunkSize() {
        int maxSize = WebSocketUtils.getMaxPayloadSize(ctx);
        return maxSize > 0 ? maxSize : 65535;
    }

    /**
     * Send WebSocket frame
     *
     * @param type frame type
     * @param data data content
     * @param fin  whether it's the final frame
     * @throws IOException throws IO exception when sending fails
     */
    private void sendFrame(WebSocketFrame.FrameType type, byte[] data, boolean fin) throws IOException {
        // Locked, zero-copy frame send (header & payload separate); flush unless SYNC batch mode.
        WebSocketUtils.sendServerFrame(ctx, type, data, fin);
    }
}