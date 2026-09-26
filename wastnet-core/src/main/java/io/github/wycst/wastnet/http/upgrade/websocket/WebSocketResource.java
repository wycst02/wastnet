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
package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.upgrade.UpgradeResource;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket resource handler.
 * <p>
 * Manages WebSocket connections for a given endpoint. Provides lifecycle callbacks
 * ({@link #onOpen}, {@link #onMessage}, {@link #onBinary}, {@link #onClose})
 * and supports idle timeout, broadcast, and payload size configuration.
 * <p>
 * Usage:
 * <pre>{@code
 * router.ws("/ws/chat", new WebSocketResource(300) {
 *     public void onOpen(WebSocketConnection conn) {
 *         System.out.println("open: " + conn.id());
 *     }
 *     public void onMessage(WebSocketConnection conn, String message) {
 *         System.out.println("message: " + message);
 *     }
 * });
 * }</pre>
 *
 * @author wangyc
 */
public class WebSocketResource extends UpgradeResource {

    final Map<String, WebSocketConnection> connectionMap = new ConcurrentHashMap<String, WebSocketConnection>();
    final boolean broadcast;
    // timeout in seconds (non-positive means no timeout)
    final int timeout;
    // timeout strategy: DISCONNECT or PING
    final TimeoutStrategy timeoutStrategy;

    // Max payload size per endpoint (defaults to global config, capped at Integer.MAX_VALUE)
    private int maxPayloadSize = HttpConf.MAX_WS_FRAME_SIZE;
    // Continuation frame handling strategy
    private ContinuationStrategy continuationStrategy = ContinuationStrategy.MERGE;
    // Max continuation frames per message (defaults to global config)
    private int maxContinuations = HttpConf.MAX_WS_CONTINUATIONS;
    // Max time in ms for a fragmented message to complete (defaults to global config)
    private long fragmentMergeTimeoutMs = HttpConf.MAX_WS_FRAGMENT_MERGE_TIMEOUT_MS;
    // Origin allowlist (null means the check is skipped, opt-in). Configured once at registration, never mutated afterwards.
    private Set<String> allowedOrigins;
    private boolean allowMissingOrigin = true;

    /**
     * Creates a WebSocket resource with broadcast enabled and no idle timeout.
     */
    public WebSocketResource() {
        this(true);
    }

    /**
     * @param broadcast whether to enable message broadcast to all connected clients
     */
    public WebSocketResource(boolean broadcast) {
        this(broadcast, 0);
    }

    /**
     * Creates a WebSocket resource with a specified idle timeout (in seconds).
     * When idle timeout expires, the connection is automatically closed.
     *
     * @param timeout idle timeout in seconds (non-positive means no timeout)
     */
    public WebSocketResource(int timeout) {
        this(true, timeout, TimeoutStrategy.DISCONNECT);
    }

    /**
     * Creates a WebSocket resource with a specified idle timeout and timeout handling strategy.
     *
     * @param timeout         idle timeout in seconds (non-positive means no timeout)
     * @param timeoutStrategy timeout handling strategy (DISCONNECT or PING)
     */
    public WebSocketResource(int timeout, TimeoutStrategy timeoutStrategy) {
        this(true, timeout, timeoutStrategy);
    }

    /**
     * Creates a WebSocket resource with broadcast and timeout configuration.
     *
     * @param broadcast whether to enable broadcast
     * @param timeout   idle timeout in seconds (non-positive means no timeout)
     */
    public WebSocketResource(boolean broadcast, int timeout) {
        this(broadcast, timeout, TimeoutStrategy.DISCONNECT);
    }

    /**
     * Creates a fully-configured WebSocket resource.
     *
     * @param broadcast       whether to enable broadcast
     * @param timeout         idle timeout in seconds (non-positive means no timeout)
     * @param timeoutStrategy timeout handling strategy
     */
    public WebSocketResource(boolean broadcast, int timeout, TimeoutStrategy timeoutStrategy) {
        this.broadcast = broadcast;
        this.timeout = timeout;
        this.timeoutStrategy = timeoutStrategy;
    }

    /**
     * Returns whether broadcast is enabled.
     *
     * @return true if broadcast is enabled, false otherwise
     */
    public boolean enableBroadcast() {
        return broadcast;
    }

    /**
     * Returns the timeout handling strategy.
     *
     * @return the timeout strategy ({@link TimeoutStrategy#DISCONNECT} or {@link TimeoutStrategy#PING})
     */
    public TimeoutStrategy getTimeoutStrategy() {
        return timeoutStrategy;
    }

    /**
     * Returns the idle timeout in seconds.
     *
     * @return idle timeout in seconds (0 or negative means no timeout)
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Pre-handshake validation. Return false to reject the WebSocket upgrade.
     *
     * @param request  the HTTP upgrade request
     * @param response the HTTP response (can be used to set error status)
     * @return true to allow upgrade, false to reject
     */
    public boolean beforeHandshake(HttpRequest request, HttpResponse response) {
        return true;
    }

    /**
     * Internal callback: registers the connection and delegates to {@link #onOpen}.
     */
    public final void handleOnOpen(WebSocketConnection connection) {
        if (enableBroadcast()) {
            connectionMap.put(connection.id(), connection);
        }
        onOpen(connection);
    }

    /**
     * Called when a WebSocket connection is established.
     *
     * @param connection the established connection
     */
    public void onOpen(WebSocketConnection connection) {
    }

    /**
     * Called when a text message is received.
     *
     * @param connection the connection that sent the message
     * @param message    the received text
     */
    public void onMessage(WebSocketConnection connection, String message) throws IOException {
    }

    /**
     * Called when a binary message is received.
     *
     * @param connection the connection that sent the message
     * @param data       the received binary data
     */
    public void onBinary(WebSocketConnection connection, byte[] data) throws IOException {
    }

    /**
     * Called for every WebSocket frame received, regardless of frame type
     * (TEXT, BINARY, CONTINUATION, CLOSE, PING, PONG).
     * <p>
     * Note: With {@code MERGE} strategy, CONTINUATION frames are accumulated
     * in the decoder and never reach here — only the merged TEXT/BINARY frame
     * is delivered via {@link #onMessage} / {@link #onBinary}.
     *
     * @param connection the WebSocket connection
     * @param frame      the received frame
     * @throws IOException if an I/O error occurs
     */
    public void onFrame(WebSocketConnection connection, WebSocketFrame frame) throws IOException {
    }

    /**
     * Handle a CONTINUATION frame.
     * <p>
     * Only triggered when the {@link ContinuationStrategy} is not {@code MERGE}
     * (i.e. {@code BATCH} or {@code STREAM}). With {@code MERGE} strategy,
     * continuation frames are accumulated in the decoder and the complete message
     * is delivered via {@link #onMessage} or {@link #onBinary} instead.
     * <p>
     * Use {@link WebSocketFrame#isFin()} to determine whether this is the
     * last continuation frame for the current message.
     *
     * @param connection the WebSocket connection
     * @param frame      the continuation frame
     * @throws IOException if an I/O error occurs
     */
    public void onContinuation(WebSocketConnection connection, WebSocketFrame frame) throws IOException {
    }

    /**
     * Called when a WebSocket connection is closed normally.
     *
     * @param connection the connection being closed
     * @param code       WebSocket close status code (e.g. 1000 for normal closure)
     * @param reason     human-readable close reason
     */
    public void onClose(WebSocketConnection connection, int code, String reason) {
    }

    // Internal method to remove connection if broadcast is enabled
    void removeIfPresent(WebSocketConnection connection) {
        if (enableBroadcast()) {
            connectionMap.remove(connection.id());
        }
    }

    /**
     * Internal callback when a connection is closed. Unregisters the connection and
     * delegates to {@link #onClose}.
     *
     * @param connection the connection being closed
     * @param code       WebSocket close status code
     * @param reason     close reason
     */
    public final void handleOnClose(WebSocketConnection connection, int code, String reason) {
        removeIfPresent(connection);
        onClose(connection, code, reason);
    }

    /**
     * Called when an error occurs on a WebSocket connection.
     *
     * @param connection the connection where the error occurred
     * @param error      the error
     */
    public void onError(WebSocketConnection connection, Throwable error) {
    }

    @Override
    public final boolean isWebSocket() {
        return true;
    }

    /**
     * Disconnect all connections.
     */
    public final void disconnect() {
        disconnect(null);
    }

    /**
     * Disconnect all connections for the specified group.
     *
     * @param groupId the group id
     */
    public final void disconnect(Serializable groupId) {
        Iterator<Map.Entry<String, WebSocketConnection>> iterator = connectionMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WebSocketConnection> entry = iterator.next();
            WebSocketConnection webSocketConnection = entry.getValue();
            if (groupId == null || groupId.equals(webSocketConnection.getGroupId())) {
                webSocketConnection.disconnect();
                iterator.remove();
            }
        }
    }

    /**
     * Disconnect all connections for the specified account.
     *
     * @param account the account id
     */
    public final void disconnectByAccount(Serializable account) {
        if (account == null) return;
        Iterator<Map.Entry<String, WebSocketConnection>> iterator = connectionMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, WebSocketConnection> entry = iterator.next();
            WebSocketConnection webSocketConnection = entry.getValue();
            if (account.equals(webSocketConnection.getAccount())) {
                webSocketConnection.disconnect();
                iterator.remove();
            }
        }
    }

    /**
     * Broadcast message to all connections.
     *
     * @param frame the frame to broadcast
     */
    public final void broadcastMessage(WebSocketFrame frame) {
        broadcastMessage(frame, null);
    }

    /**
     * Broadcast message to specified group.
     *
     * @param frame   the frame to broadcast
     * @param groupId the group id
     */
    public final void broadcastMessage(WebSocketFrame frame, String groupId) {
        Collection<WebSocketConnection> webSocketConnections = connectionMap.values();
        for (WebSocketConnection webSocketConnection : webSocketConnections) {
            if (groupId == null || groupId.equals(webSocketConnection.getGroupId())) {
                webSocketConnection.push(frame);
            }
        }
    }

    /**
     * Push message to specified account.
     *
     * @param frame   the frame to push
     * @param account the account id
     */
    public final void pushMessage(WebSocketFrame frame, String account) {
        WebSocketConnection webSocketConnection = getConnectionByAccount(account);
        if (webSocketConnection != null) {
            webSocketConnection.push(frame);
        }
    }

    /**
     * Get connection by account.
     *
     * @param account the account id
     * @return the connection
     */
    public WebSocketConnection getConnectionByAccount(String account) {
        if (account == null) return null;
        Collection<WebSocketConnection> webSocketConnections = connectionMap.values();
        for (WebSocketConnection webSocketConnection : webSocketConnections) {
            if (account.equals(webSocketConnection.getAccount())) {
                return webSocketConnection;
            }
        }
        return null;
    }

    /**
     * Handle abnormal close.
     *
     * @param ctx the channel context
     */
    @Override
    public final void handleOnClose(ChannelContext ctx) {
        UpgradeWebSocketHolder upgradeHolder = (UpgradeWebSocketHolder) ctx.binding();
        if(upgradeHolder != null) {
            WebSocketConnection webSocketConnection = upgradeHolder.connection;
            removeIfPresent(webSocketConnection);
            onErrorClose(webSocketConnection);
        }
    }

    /**
     * Called when a connection is closed abnormally (e.g. connection reset).
     *
     * @param connection the connection that was closed abnormally
     */
    public void onErrorClose(WebSocketConnection connection) {
    }

    /**
     * Get supported subprotocol, returns the first one by default.
     *
     * @param supportedSubprotocols supported subprotocols
     * @return the selected subprotocol
     */
    public String getSubprotocols(String supportedSubprotocols) {
        if (supportedSubprotocols == null) return null;
        int index = supportedSubprotocols.indexOf(",");
        if (index > 0) {
            return supportedSubprotocols.substring(0, index);
        }
        return supportedSubprotocols;
    }

    /**
     * Returns the maximum WebSocket payload size allowed for this endpoint.
     *
     * @return max payload size in bytes
     */
    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    /**
     * Sets the maximum WebSocket payload size for this endpoint.
     * This overrides the global default ({@link HttpConf#MAX_WS_FRAME_SIZE}).
     *
     * @param maxPayloadSize max payload size in bytes (capped at Integer.MAX_VALUE)
     * @return this instance for chaining
     */
    public WebSocketResource maxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
        return this;
    }

    /**
     * Returns the continuation frame handling strategy.
     *
     * @return the continuation strategy
     */
    public ContinuationStrategy getContinuationStrategy() {
        return continuationStrategy;
    }

    /**
     * Sets the continuation frame handling strategy.
     *
     * @param strategy the continuation strategy ({@link ContinuationStrategy#MERGE}, {@link ContinuationStrategy#BATCH}, or {@link ContinuationStrategy#STREAM})
     * @return this instance for chaining
     */
    public WebSocketResource continuationStrategy(ContinuationStrategy strategy) {
        this.continuationStrategy = strategy;
        return this;
    }

    /**
     * Returns the max continuation frames allowed per fragmented message.
     *
     * @return max continuation count
     */
    public int getMaxContinuations() {
        return maxContinuations;
    }

    /**
     * Sets the max continuation frames allowed per fragmented message.
     * Exceeding this limit triggers a 1009 close frame.
     *
     * @param maxContinuations max continuation count (default 256)
     * @return this instance for chaining
     */
    public WebSocketResource maxContinuations(int maxContinuations) {
        this.maxContinuations = maxContinuations;
        return this;
    }

    /**
     * Returns the max time for a fragmented message to be merged
     * (only applies to MERGE/BATCH strategies, ignored by STREAM).
     *
     * @return timeout in milliseconds
     */
    public long getFragmentMergeTimeoutMs() {
        return fragmentMergeTimeoutMs;
    }

    /**
     * Sets the max time for a fragmented message to be merged.
     * Exceeding this timeout triggers a 1009 close frame.
     * Only applies to MERGE/BATCH strategies, ignored by STREAM.
     *
     * @param fragmentMergeTimeoutMs timeout in ms (default 30_000)
     * @return this instance for chaining
     */
    public WebSocketResource fragmentMergeTimeoutMs(long fragmentMergeTimeoutMs) {
        this.fragmentMergeTimeoutMs = fragmentMergeTimeoutMs;
        return this;
    }

    /**
     * Configure the Origin allowlist against CSWSH (RFC 6455 §10.2).
     * <p>
     * Not configured by default: the check is skipped (opt-in). Once configured,
     * the Origin header must exactly match one entry, or "*" to allow any origin.
     * Requests without an Origin header (non-browser clients) are governed by
     * {@link #allowMissingOrigin(boolean)}.
     *
     * @param origins allowed origins, e.g. "<a href="">https://example.com</a>"
     * @return this instance for chaining
     */
    public WebSocketResource allowedOrigins(String... origins) {
        Set<String> set = new HashSet<>();
        for (String origin : origins) {
            if (origin != null && !origin.isEmpty()) {
                set.add(origin);
            }
        }
        this.allowedOrigins = set;
        return this;
    }

    /**
     * Whether requests without an Origin header are allowed (default true).
     * Browsers always send Origin, so CSWSH payloads always carry one; set false
     * for strict mode that also rejects non-browser clients.
     *
     * @param allowMissingOrigin false to reject requests missing the Origin header
     * @return this instance for chaining
     */
    public WebSocketResource allowMissingOrigin(boolean allowMissingOrigin) {
        this.allowMissingOrigin = allowMissingOrigin;
        return this;
    }

    /**
     * Framework-internal Origin check, invoked before {@link #beforeHandshake}.
     *
     * @param request the HTTP upgrade request
     * @return true if the origin is allowed
     */
    public final boolean isOriginAllowed(HttpRequest request) {
        Set<String> origins = allowedOrigins;
        if (origins == null || origins.contains("*")) return true;
        String origin = request.getHeader(HttpHeaderNormalized.getOrigin(), true);
        if (origin == null || origin.isEmpty()) return allowMissingOrigin;
        return origins.contains(origin);
    }

    /**
     * Continuation frame handling strategy.
     */
    public enum ContinuationStrategy {
        /** Dispatch every frame individually via onFrame, no merging. */
        STREAM,
        /** Buffer continuation frames, flush intermediate merged chunks when approaching maxPayloadSize. */
        BATCH,
        /** Merge all continuation frames into one message; 1009 error if exceeds maxPayloadSize. */
        MERGE
    }

    /**
     * WebSocket timeout strategy.
     */
    public enum TimeoutStrategy {
        /**
         * Disconnect on timeout.
         */
        DISCONNECT,
        /**
         * Send ping on timeout to keep alive.
         */
        PING
    }

}
