package io.github.wycst.wastnet.http.reader;

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.http.HttpMessageDecoder;
import io.github.wycst.wastnet.http.HttpRequestDecoder;
import io.github.wycst.wastnet.http.upgrade.UpgradeHolder;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * HTTP/1.x decode entry point.
 *
 * @since 2024-1-19
 * @author wangyc
 */
public class HttpRequestReader extends HttpMessageReader<HttpMessage> {

    /** Reusable decoder instance for WebSocket upgrade path. */
    final static WebSocketDecoder WEBSOCKET_DECODER = new WebSocketDecoder();
    /** Active protocol decoder (HTTP/1.x or WebSocket after upgrade). */
    HttpMessageDecoder messageDecoder;

    /** @param ctx the channel context for this connection */
    public HttpRequestReader(ChannelContext ctx) {
        messageDecoder = new HttpRequestDecoder(ctx);
    }

    @Override
    public void decode(ChannelContext ctx, byte[] buf, int offset, int len) throws IOException {
        messageDecoder.decode(buf, offset, len, ctx);
    }

    /**
     * Switch to WebSocket protocol for the remainder of this connection.
     * {@code switchTo()} is used instead for h2c upgrades.
     */
    @Override
    public void upgrade(UpgradeHolder upgradeHolder) {
        // upgrade() is only called for WebSocket upgrades (h2c uses switchTo()), so isWebSocket() check is unnecessary
        messageDecoder = WEBSOCKET_DECODER;
    }
}