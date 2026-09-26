package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.security.MessageDigest;

/**
 * WebSocket utilities.
 */
public class WebSocketUtils {

    private WebSocketUtils() {
    }

    static final Log log = LogFactory.getLog(WebSocketUtils.class);
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /**
     * Encode WebSocket frame (RFC 6455).
     *
     * @param type    frame type
     * @param payload payload data
     * @param fin     final frame flag
     * @return encoded frame bytes
     */
    public static byte[] encodeServerFrame(WebSocketFrame.FrameType type, byte[] payload, boolean fin) {
        return encodeServerFrame(type, payload, 0, payload != null ? payload.length : 0, fin);
    }

    /**
     * Encode WebSocket frame with offset and length (RFC 6455).
     *
     * @param type    frame type
     * @param payload payload data
     * @param offset  offset in payload
     * @param len     number of bytes to encode
     * @param fin     final frame flag
     * @return encoded frame bytes
     */
    public static byte[] encodeServerFrame(WebSocketFrame.FrameType type, byte[] payload, int offset, int len, boolean fin) {
        int headerLength = frameHeaderLength(len);
        byte[] frameBytes = new byte[headerLength + len];
        writeServerFrameHeader(type, len, fin, frameBytes, 0);
        if (len > 0) {
            System.arraycopy(payload, offset, frameBytes, headerLength, len);
        }
        return frameBytes;
    }

    /**
     * Compute the WebSocket frame header size for a given payload length.
     */
    public static int frameHeaderLength(int payloadLength) {
        if (payloadLength <= 125) return 2;
        if (payloadLength <= 65535) return 4;
        return 10;
    }

    /**
     * Write the WebSocket frame header into an existing buffer at the given offset.
     * The caller must ensure the buffer has enough space ({@link #frameHeaderLength} + payload length).
     *
     * @param type     frame type
     * @param len      payload length
     * @param fin      final frame flag
     * @param dest     destination buffer
     * @param destOff  offset in destination buffer
     */
    public static void writeServerFrameHeader(WebSocketFrame.FrameType type, int len, boolean fin,
                                              byte[] dest, int destOff) {
        int pos = destOff;
        dest[pos++] = (byte) ((fin ? 0x80 : 0x00) | (type.opcode & 0x0F));
        if (len <= 125) {
            dest[pos] = (byte) len;
        } else if (len <= 65535) {
            dest[pos++] = (byte) 126;
            dest[pos++] = (byte) ((len >> 8) & 0xFF);
            dest[pos] = (byte) (len & 0xFF);
        } else {
            dest[pos++] = (byte) 127;
            pos += 4; // high 32 bits always zero (int max < 2^31), skip 4 bytes
            dest[pos++] = (byte) ((len >> 24) & 0xFF);
            dest[pos++] = (byte) ((len >> 16) & 0xFF);
            dest[pos++] = (byte) ((len >> 8) & 0xFF);
            dest[pos] = (byte) (len & 0xFF);
        }
    }

    /**
     * Send close frame.
     *
     * @param ctx    channel context
     * @param code   close code
     * @param reason close reason
     */
    public static void sendCloseFrame(ChannelContext ctx, int code, String reason) {
        try {
            // pre-encode reason string to avoid repeated getBytes calls
            byte[] reasonBytes = reason != null ? reason.getBytes(Utils.UTF_8) : new byte[0];
            // RFC 6455: close reason must be <= 123 bytes; truncate if longer
            int reasonLen = Math.min(reasonBytes.length, 123);

            // build close frame payload
            byte[] closeData = new byte[2 + reasonLen];
            closeData[0] = (byte) ((code >> 8) & 0xFF);
            closeData[1] = (byte) (code & 0xFF);
            System.arraycopy(reasonBytes, 0, closeData, 2, reasonLen);

            // encode and send close frame
            byte[] frameData = encodeServerFrame(WebSocketFrame.FrameType.CLOSE, closeData, true);
            ctx.writeFlush(frameData);
        } catch (Exception e) {
            log.error("Failed to send close frame: {}", e.getMessage());
        }
    }

    /**
     * Perform WebSocket handshake.
     *
     * @param request      HTTP request
     * @param response     WebSocket response
     * @param ctx          channel context
     * @param subprotocols subprotocol
     * @return WebSocket connection, null if handshake fails
     * @throws Exception if handshake fails
     */
    public static WebSocketConnection handshake(HttpRequest request, WebSocketResponse response, ChannelContext ctx, String subprotocols) throws Exception {
        // extract upgrade headers
        String upgradeHeader = request.getHeader(HttpHeaderNormalized.getUpgrade(), true);
        String connectionHeader = request.getHeader(HttpHeaderNormalized.getConnection(), true);
        String secWebSocketKey = request.getHeader(HttpHeaderNormalized.getSecWebSocketKey(), true);
        if (!isWebSocketUpgradeRequest(upgradeHeader, connectionHeader, secWebSocketKey)) {
            return null;
        }
        if (subprotocols != null) {
            response.header(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, subprotocols);
        }
        // set Sec-WebSocket-Accept header and flush response
        response.header(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, generateWebSocketAccept(secWebSocketKey))
                .flush();
        ((HttpInternalResponse) request.getResponse()).handover(); // Hand over the outer response so the framework does not append a second
        return new WebSocketConnectionImpl(request, response, ctx);
    }

    /**
     * Generate Sec-WebSocket-Accept value per RFC 6455.
     *
     * @param secWebSocketKey the Sec-WebSocket-Key from client
     * @return Base64-encoded SHA-1 hash of key + GUID
     */
    private static String generateWebSocketAccept(String secWebSocketKey) {
        try {
            String concatenated = secWebSocketKey.trim() + WEBSOCKET_GUID;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] sha1Hash = md.digest(concatenated.getBytes());
            return java.util.Base64.getEncoder().encodeToString(sha1Hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate WebSocket accept key", e);
        }
    }

    /**
     * Check if request is a valid WebSocket upgrade request.
     *
     * @param request the HTTP request
     * @return true if valid WebSocket upgrade request
     */
    public static boolean isWebSocketUpgradeRequest(HttpRequest request) {
        String upgrade = request.getHeader(HttpHeaderNormalized.getUpgrade(), true);
        String connection = request.getHeader(HttpHeaderNormalized.getConnection(), true);
        String secWebSocketKey = request.getHeader(HttpHeaderNormalized.getSecWebSocketKey(), true);

        // probe path (proxy): a missing key means "not an upgrade" instead of an NPE
        return secWebSocketKey != null && isWebSocketUpgradeRequest(upgrade, connection, secWebSocketKey);
    }

    /**
     * Check if the given headers form a valid WebSocket upgrade request.
     *
     * @param upgrade         Upgrade header value
     * @param connection      Connection header value
     * @param secWebSocketKey Sec-WebSocket-Key header value
     * @return true if valid WebSocket upgrade request
     */
    public static boolean isWebSocketUpgradeRequest(String upgrade, String connection, String secWebSocketKey) {
        return HttpHeaderValues.WEBSOCKET.equalsIgnoreCase(upgrade) &&
                HttpHeaderValues.UPGRADE.equalsIgnoreCase(connection) &&
                !secWebSocketKey.trim().isEmpty();
    }

    /**
     * Get the effective max payload size for the current WebSocket connection.
     * Reads from the endpoint-level {@link WebSocketResource#getMaxPayloadSize()},
     * falling back to {@link HttpConf#MAX_WS_FRAME_SIZE} if not bound.
     */
    public static int getMaxPayloadSize(ChannelContext ctx) {
        // holder is always non-null after upgrade; null only via illegal reflection on binding
        UpgradeWebSocketHolder holder = (UpgradeWebSocketHolder) ctx.binding();
        return holder != null ? holder.resource.getMaxPayloadSize() : HttpConf.MAX_WS_FRAME_SIZE;
    }
}