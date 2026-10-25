/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.upgrade;

import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpHeaderValues;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.h2.Http2ServerReader;
import io.github.wycst.wastnet.http.reader.HttpChannelProtocolReader;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResponse;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketUtils;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link UpgradeHandler}.
 * <p>
 * Manages WebSocket and h2c resources in a map, handles WebSocket frame dispatching,
 * and performs protocol upgrade handshake.
 *
 * @author wangyc
 */
public class DefaultUpgradeHandler implements UpgradeHandler, ClearableHandler {

    static final Log log = LogFactory.getLog(DefaultUpgradeHandler.class);

    final Map<String, UpgradeResource> resourceHashMap = new java.util.HashMap<>();
    static final byte[] H2C_101_RESPONSE = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: h2c\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    static final WebSocketResource DEFAULT_WEBSOCKET_RESOURCE = new WebSocketResource(false);

    /**
     * Get upgrade resource by path.
     * <p>
     * Tries exact match first, falls back to prefix match.
     * Prefix match supports dynamic paths like {@code /ws/chat/user/1001}
     * matching a resource registered at {@code /ws/chat/user/}.
     */
    UpgradeResource getResource(String path) {
        if (resourceHashMap.isEmpty()) return null;
        UpgradeResource res = resourceHashMap.get(path);
        if (res != null) return res;
        for (Map.Entry<String, UpgradeResource> entry : resourceHashMap.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Check if the HTTP request contains a valid h2c (HTTP/2 Cleartext) upgrade request.
     * <p>
     * Validates per RFC 7540 Section 3.2:
     * <pre>
     * Upgrade: h2c
     * Connection: contains HTTP2-Settings
     * </pre>
     *
     * @param request the HTTP request
     * @return true if this is a valid h2c upgrade request
     */
    public static boolean isH2cUpgradeRequest(HttpRequest request) {
        String upgrade = request.getHeader(HttpHeaderNormalized.getUpgrade(), true);
        if (!HttpHeaderValues.H2C.equalsIgnoreCase(upgrade)) return false;
        String connection = request.getHeader(HttpHeaderNormalized.getConnection(), true);
        return connection != null
                && connection.contains("HTTP2-Settings")
                && connection.contains("Upgrade");
    }


    @Override
    public void h2c(String path) {
        resourceHashMap.put(path, UpgradeResource.H2C);
    }

    @Override
    public WebSocketResource ws(String path, WebSocketResource webSocketResource) {
        resourceHashMap.put(path, webSocketResource.path(path));
        return webSocketResource;
    }

    /**
     * Framework auto-routing entry: detect the upgrade mode by the request URI
     * and perform the handshake using the registered resource.
     * <p>
     * Looks up the {@link UpgradeResource} mapped to the request URI. A WebSocket
     * resource triggers the websocket upgrade; otherwise an h2c (HTTP/2 Cleartext)
     * upgrade is attempted when the request carries the proper upgrade headers.
     *
     * @param request the http request
     * @param ctx     channel context
     * @return true if an upgrade response was committed (skip normal routing)
     * @throws Exception if upgrade fails
     */
    @Override
    public boolean upgrade(HttpRequest request, ChannelContext ctx) throws Exception {
        UpgradeResource upgradeResource = getResource(request.getRequestUri());
        if (upgradeResource == null) return false;
        if (upgradeResource.isWebSocket()) {
            WebSocketResource webSocketResource = (WebSocketResource) upgradeResource;
            // Framework-enforced origin check (RFC 6455 §10.2), cannot be bypassed by beforeHandshake
            if (!webSocketResource.isOriginAllowed(request)) {
                WebSocketResponse response = WebSocketResponse.create(request, ctx);
                response.status(HttpStatus.FORBIDDEN).body("Forbidden: origin not allowed").commit();
                log.warn("WebSocket upgrade rejected by origin check, path: {}, origin: {}",
                        request.getRequestUri(), request.getHeader(HttpHeaderNormalized.getOrigin(), true));
                return true;
            }
            // origin already validated above; doHandshake does NOT re-check it
            try {
                return doHandshake((WebSocketResource) upgradeResource, request, ctx) != null;
            } catch (Exception e) {
                log.error("WebSocket upgrade handshake failed, path: {}", request.getRequestUri(), e);
                WebSocketResponse.create(request, ctx).status(HttpStatus.BAD_REQUEST).body("Bad Request").commit();
                return true; // response committed; skip normal routing
            }
        } else {
            // h2c upgrade
            if (!isH2cUpgradeRequest(request)) return false;
            upgradeH2c(ctx);
            return true;
        }
    }

    /**
     * Detect the upgrade mode (WebSocket / h2c) and perform the handshake.
     * <p>
     * For an h2c upgrade request, performs the HTTP/2 Cleartext switch and returns null.
     * For a websocket upgrade, uses the default shared {@link WebSocketResource} (broadcast
     * disabled) and returns the established {@link WebSocketConnection}.
     * Returns null for non-upgradable requests or rejected handshakes.
     *
     * @param request the http request
     * @param ctx     channel context
     * @return the websocket connection, or null for h2c / non-upgradable / rejected
     * @throws Exception if upgrade fails
     */
    public static Object tryUpgrade(HttpRequest request, ChannelContext ctx) throws Exception {
        if (isH2cUpgradeRequest(request)) {
            upgradeH2c(ctx);
            return null;
        }
        // default websocket resource with broadcast disabled, for servlet/embedded usage
        return tryUpgradeWebsocket(DEFAULT_WEBSOCKET_RESOURCE, request, ctx);
    }

    /**
     * Perform a WebSocket upgrade using the given resource.
     * <p>
     * Used by {@link io.github.wycst.wastnet.http.HttpRequest#upgrade()} and its
     * overloaded variant so callers can supply a custom {@link WebSocketResource}
     * (e.g. embedded usage with personalized configuration).
     *
     * @param webSocketResource the websocket resource carrying user hooks
     * @param request           the current http request
     * @param ctx               channel context
     * @return the websocket connection, or null if the handshake was rejected
     * @throws Exception if upgrade fails
     */
    public static WebSocketConnection tryUpgradeWebsocket(WebSocketResource webSocketResource, HttpRequest request, ChannelContext ctx) throws Exception {
        // Framework-enforced origin check (RFC 6455 §10.2), cannot be bypassed by beforeHandshake.
        // This is the only origin gate for programmatic upgrade paths; on rejection it returns
        // null WITHOUT writing any response, leaving the application handler to decide how to respond.
        if (!webSocketResource.isOriginAllowed(request)) {
            return null;
        }
        return doHandshake(webSocketResource, request, ctx);
    }

    /**
     * Execute the WebSocket handshake and post-handshake binding.
     * <p>
     * Assumes the origin has already been validated by the caller. Performs
     * {@code beforeHandshake}, the RFC 6455 handshake, connection binding,
     * pre-close listener registration, timeout detection, and protocol switch.
     * Callers: auto-routing {@code upgrade()} (origin pre-checked) and
     * {@code tryUpgradeWebsocket()} (origin checked here before delegating).
     *
     * @param webSocketResource the websocket resource carrying user hooks
     * @param request           the current http request
     * @param ctx               channel context
     * @return the websocket connection, or null if the handshake was rejected
     * @throws Exception if upgrade fails
     */
    private static WebSocketConnection doHandshake(WebSocketResource webSocketResource, HttpRequest request, ChannelContext ctx) throws Exception {
        WebSocketResponse response = WebSocketResponse.create(request, ctx);
        if (webSocketResource.beforeHandshake(request, response)) {
            String subprotocols = webSocketResource.getSubprotocols(response.getSupportedSubprotocols());
            final WebSocketConnection connection = WebSocketUtils.handshake(request, response, ctx, subprotocols);
            if (connection != null) {
                webSocketResource.handleOnOpen(connection);
                if (ctx.binding() != null) {
                    log.warn("WebSocket upgrade rejected: beforeHandshake set binding on ctx");
                    return null;
                }
                UpgradeWebSocketHolder upgradeHolder = new UpgradeWebSocketHolder(webSocketResource, connection, ctx);
                ctx.binding(upgradeHolder);
                // Register pre-close listener: send WebSocket CLOSE frame before TCP close
                ctx.addClearListener(() -> {
                    try {
                        connection.close(1001, "Server shutting down");
                    } catch (Exception ignored) {
                    }
                });
                // start timeout detection
                connection.timeoutDetection(
                        webSocketResource.getTimeout(),
                        webSocketResource.getTimeoutStrategy()
                );
                // perform protocol switch
                ((HttpChannelProtocolReader) ctx.reader()).upgrade(upgradeHolder);
                return connection;
            }
        }
        return null;
    }

    /**
     * Perform the h2c (HTTP/2 Cleartext) protocol switch.
     *
     * @param ctx channel context
     * @throws Exception if switch fails
     */
    private static void upgradeH2c(ChannelContext ctx) throws Exception {
        // Client HTTP2-Settings header (RFC 7540 §3.2) is intentionally not parsed — clients send a standalone SETTINGS frame after the 101 switch (applied by h2Reader.init), so skipping it is safe, not a compliance gap.
        Http2ServerReader h2Reader = new Http2ServerReader(ctx);
        ((HttpChannelProtocolReader) ctx.reader()).switchTo(h2Reader);
        ctx.writeFlush(H2C_101_RESPONSE);
        h2Reader.init(ctx);
    }

    @Override
    public void onClosed(ChannelContext ctx) throws IOException {
        UpgradeHolder upgradeHolder = (UpgradeHolder) ctx.binding();
        if (upgradeHolder != null) {
            upgradeHolder.upgradeResource().handleOnClose(ctx);
        }
    }

    @Override
    public void clear() {
        for (UpgradeResource resource : resourceHashMap.values()) {
            if(resource.isWebSocket()) {
                try {
                    ((WebSocketResource) resource).disconnect();
                } catch (Exception exception) {
                    log.warn("Error disconnecting WebSocket resource: {}", exception.getMessage());
                }
            }
        }
        resourceHashMap.clear();
    }

    /**
     * Remove upgrade resources registered at the given (already context-prefixed) paths,
     * disconnecting WebSocket connections first. Used by dev hot reload to drop only the
     * scanned WebSocket endpoints while leaving manually-registered ones intact.
     *
     * @param paths context-prefixed paths previously registered via {@link #ws(String, WebSocketResource)}
     */
    protected void removeResources(Set<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        for (String p : paths) {
            UpgradeResource resource = resourceHashMap.remove(p);
            if (resource != null && resource.isWebSocket()) {
                try {
                    ((WebSocketResource) resource).disconnect();
                } catch (Exception exception) {
                    log.warn("Error disconnecting WebSocket resource on reload: {}", exception.getMessage());
                }
            }
        }
    }
}
