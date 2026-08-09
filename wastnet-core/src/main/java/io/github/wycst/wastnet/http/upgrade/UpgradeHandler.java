package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpUpgradeMessage;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

/**
 * Handler interface for HTTP upgrade protocols (WebSocket, h2c).
 * <p>
 * Implementations register upgrade resources via ws() and h2c() methods,
 * and handle upgrade requests via upgrade() callback.
 *
 * @author wangyc
 */
public interface UpgradeHandler {

    /**
     * Handle upgrade message after protocol switch.
     *
     * @param ctx             channel context
     * @param upgradeMessage  upgrade message (WebSocket frame or h2c data)
     * @throws Throwable if handling fails
     */
    void handle(ChannelContext ctx, HttpUpgradeMessage upgradeMessage) throws Throwable;

    /**
     * Register a WebSocket resource for the given path.
     *
     * @param path      WebSocket endpoint path
     * @param resource  WebSocket resource handler
     * @return the registered WebSocket resource
     */
    WebSocketResource ws(String path, WebSocketResource resource);

    /**
     * Register an h2c resource for the given path.
     *
     * @param path h2c endpoint path
     */
    void h2c(String path);

    /**
     * Check if the request is an upgrade request and perform handshake.
     *
     * @param request  HTTP request to check
     * @param ctx      channel context
     * @return true if the request has been fully handled by the upgrade flow —
     *         either the protocol was switched (WebSocket/h2c), or the upgrade
     *         was rejected with a response already committed (e.g. 403 when the
     *         origin check fails); the caller skips normal HTTP routing on true.
     *         false to continue with normal HTTP processing.
     * @throws Exception if upgrade fails
     */
    boolean upgrade(HttpRequest request, ChannelContext ctx) throws Exception;

    /**
     * Called when the channel is closed.
     * <p>
     * Cleanup resources associated with the upgrade.
     *
     * @param ctx  channel context
     * @throws IOException if cleanup fails
     */
    void onClosed(ChannelContext ctx) throws IOException;
}
