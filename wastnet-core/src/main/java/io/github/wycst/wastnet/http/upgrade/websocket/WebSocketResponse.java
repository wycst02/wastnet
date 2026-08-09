package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;

/**
 * WebSocket response implementation
 * Extends HTTP default response, provides WebSocket specific functionality
 *
 * @since 2024-1-27
 * @author wangyc
 */
public class WebSocketResponse extends HttpDefaultResponse {

    private final String supportedSubprotocols;

    public WebSocketResponse(HttpRequest request, ChannelContext ctx) {
        super(request, ctx);
        setStatus(HttpStatus.SWITCHING_PROTOCOLS);
        putHeader(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET);
        putHeader(HttpHeaderNames.CONNECTION, HttpHeaderNames.UPGRADE);
        putHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        this.supportedSubprotocols = request.getHeader(HttpHeaderNormalized.getSecWebSocketProtocol(), true);
    }

    public String getSupportedSubprotocols() {
        return supportedSubprotocols;
    }

    public String subprotocol() {
        return directlyGetHeader(HttpHeaderNormalized.getSecWebSocketProtocol());
    }

    public static WebSocketResponse create(HttpRequest request, ChannelContext ctx) {
        return new WebSocketResponse(request, ctx);
    }

    @Override
    public void flush() throws IOException {
        ensureHeadersSent();
        ctx.flush();
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
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        byte[] data = message.getBytes(Utils.UTF_8);
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
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
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
        byte[] frameData = WebSocketUtils.encodeServerFrame(type, data, fin);
        writeFlush(frameData);
    }
}