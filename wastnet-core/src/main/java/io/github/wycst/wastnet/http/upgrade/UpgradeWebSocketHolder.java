package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketFrame;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketUtils;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.nio.charset.StandardCharsets;

public class UpgradeWebSocketHolder implements UpgradeHolder {

    static final Log log = LogFactory.getLog(UpgradeWebSocketHolder.class);
    static final byte[] WEBSOCKET_PONG_FRAME = new byte[]{(byte) 0x8A, 0x00};

    public final WebSocketResource resource;
    public final WebSocketConnection connection;
    public final ChannelContext ctx;

    public UpgradeWebSocketHolder(WebSocketResource resource, WebSocketConnection connection, ChannelContext ctx) {
        this.resource = resource;
        this.connection = connection;
        this.ctx = ctx;
    }

    @Override
    public boolean isWebSocket() {
        return true;
    }

    @Override
    public boolean isH2c() {
        return false;
    }

    @Override
    public UpgradeResource upgradeResource() {
        return resource;
    }

    public final void handleFrame(WebSocketFrame frame) {
        WebSocketFrame.FrameType type = frame.getType();
        try {
            connection.updateActiveTime();
            switch (type) {
                case CONTINUATION:
                    resource.onContinuation(connection, frame);
                    break;
                case TEXT:
                    resource.onMessage(connection, frame.getData());
                    break;
                case BINARY:
                    resource.onBinary(connection, frame.getData());
                    break;
                case CLOSE:
                    handleCloseFrame(frame);
                    break;
                case PING:
                    sendPongFrame(frame.getData());
                    break;
                case PONG:
                    break;
            }
            resource.onFrame(connection, frame);
        } catch (Exception e) {
            try {
                resource.onError(connection, e);
            } catch (Exception errorEx) {
                log.error("Error in WebSocket error handler: {}", errorEx.getMessage());
            }
            try {
                connection.close(1011, "Internal server error");
            } catch (Exception closeEx) {
                log.error("Failed to close WebSocket connection: {}", closeEx.getMessage());
            }
        }
    }

    /**
     * Handle WebSocket close frame.
     * <p>
     * Parses close code and reason, notifies resource, sends close frame, and closes connection.
     */
    private void handleCloseFrame(WebSocketFrame frame) {
        try {
            byte[] data = frame.getData();
            int closeCode = 1000; // default normal closure
            String reason = "Normal closure";

            // parse close code and reason
            if (data.length >= 2) {
                closeCode = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                if (data.length > 2) {
                    reason = new String(data, 2, data.length - 2, StandardCharsets.UTF_8);
                }
            }

            // notify resource of close
            resource.handleOnClose(connection, closeCode, reason);

            // send close frame
            WebSocketUtils.sendCloseFrame(ctx, closeCode, reason);

            // close connection
            ctx.close();
        } catch (Exception e) {
            log.error("Error handling close frame: {}", e.getMessage());
            ctx.close();
        }
    }

    /**
     * Send pong frame in response to ping.
     */
    private void sendPongFrame(byte[] pingData) {
        try {
            if (pingData.length == 0) {
                ctx.writeFlush(WEBSOCKET_PONG_FRAME);
            } else {
                ctx.writeFlush(WebSocketUtils.encodeServerFrame(WebSocketFrame.FrameType.PONG, pingData, true));
            }
        } catch (Exception e) {
            log.error("Failed to send pong frame: {}", e.getMessage());
        }
    }
}
