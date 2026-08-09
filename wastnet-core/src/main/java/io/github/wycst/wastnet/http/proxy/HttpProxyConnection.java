package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpHeaderValues;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketUtils;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledFuture;

import static io.github.wycst.wastnet.http.proxy.HttpProxyWorker.isUpgrade101;

/**
 * HTTP proxy connection that bridges client and target server.
 *
 * @author wangyc
 */
public class HttpProxyConnection {

    static final Log log = LogFactory.getLog(HttpProxyConnection.class);

    final String routeId;
    final Long connectionId;
    final SelectionKey targetKey;
    final ChannelContext clientCtx;
    final ChannelContext targetCtx;
    final SocketChannel targetChannel;
    final HttpProxyWorker worker;
    HttpProxyAdapter adapter;
    boolean isUpgrade;
    volatile boolean dataReceived;
    boolean clientH2;
    boolean targetH2;
    volatile boolean closed;
    volatile boolean disposed;
    volatile ScheduledFuture<?> timeoutFuture;
    final Runnable closeListener;
    final Runnable timeoutTask;
    SelectionKey clientKey;

    public HttpProxyConnection(Long connectionId, String routeId, ChannelContext clientCtx, ChannelContext targetCtx, final HttpProxyWorker worker) throws IOException {
        this.routeId = routeId;
        this.connectionId = connectionId;
        this.clientCtx = clientCtx;
        this.targetCtx = targetCtx;
        this.targetChannel = targetCtx.channel();
        this.worker = worker;
        this.clientH2 = isH2(clientCtx);
        this.targetH2 = isH2(targetCtx);
        this.adapter = resolveAdapter(this);
        this.closeListener = () -> worker.cleanupConnection(HttpProxyConnection.this);
        this.timeoutTask = () -> {
            if (!dataReceived) {
                log.error("[HttpProxy] Read timeout for connection {}", connectionId);
                // Close target first to prevent late data from polluting client
                worker.cleanupConnection(HttpProxyConnection.this);
                try {
                    clientCtx.write(HttpProxyWorkerManager.GATEWAY_TIMEOUT_RESPONSE.duplicate());
                    clientCtx.flush();
                } catch (Exception ignored) {
                }
            }
        };
        worker.registering = true;
        worker.selector.wakeup();
        try {
            this.targetKey = targetChannel.register(worker.selector, SelectionKey.OP_READ, this);
        } finally {
            worker.registering = false;
            synchronized (this.worker) {
                worker.notify();
            }
        }
        // Add close listener to cleanup when client channel is closed
        clientCtx.addCloseListener(closeListener);
    }

    void checkDataReceived(ByteBuffer buffer) throws IOException {
        // Cancel timeout on first response data
        if (!dataReceived) {
            dataReceived = true;
            // Check WebSocket upgrade before write (buffer is valid here)
            // Upgrade detection only applies to HTTP/1.x connections
            if (!clientH2 && isUpgrade) {
                // readCtx.read is async greedy read, handshake usually won't be less than 12 bytes.
                // If packet splitting due to bug, treated as handshake failure.
                if (buffer.remaining() >= 12 && isUpgrade101(buffer)) {
                    registerClientRead();
                } else {
                    isUpgrade = false;
                }
            }
            ScheduledFuture<?> future = timeoutFuture;
            if (future != null) {
                future.cancel(false);
                timeoutFuture = null;
            }
        }
    }

    /**
     * Check whether the given context uses HTTP/2.
     * <p>
     * TLS connections: checked via ALPN handshake protocol ("h2").
     * Non-SSL connections: checked via h2c prior knowledge attribute marker.
     *
     * @param ctx the channel context to check
     * @return true if the context is using HTTP/2
     */
    boolean isH2(ChannelContext ctx) {
        if ("h2".equals(ctx.getHandShakedApplicationProtocol())) return true;
        return !ctx.isSSL() && "h2c".equals(ctx.binding());
    }

    /**
     * Resolve the appropriate adapter based on handshake protocols.
     */
    private static HttpProxyAdapter resolveAdapter(HttpProxyConnection conn) {
        if (!conn.clientH2 && !conn.targetH2) {
            return HttpProxyAdapter.PASSTHROUGH;
        }
        if (conn.clientH2) {
            return conn.targetH2 ? new H2H2ProxyAdapter(conn) : new H2H1ProxyAdapter(conn);
        }
        return new H1H2ProxyAdapter(conn); // h1 -> h2
    }

    /**
     * Register client channel for OP_READ to start bidirectional relay.
     * Called after 101 Switching Protocols is confirmed from target.
     */
    void registerClientRead() throws IOException {
        if (this.clientKey == null) {
            // No wakeup needed: called from worker thread while selector is not in select()
            this.clientKey = clientCtx.channel().register(worker.selector, SelectionKey.OP_READ, this);
            clientCtx.setReadKey(this.clientKey);
        }
    }

    public void forwardToTarget(HttpRequest request, HttpProxyConfig config) throws Throwable {
        // Determine upgrade status per request (connection may be reused)
        this.isUpgrade = config.upgrade
                && (WebSocketUtils.isWebSocketUpgradeRequest(request)
                || HttpHeaderValues.H2C.equalsIgnoreCase(request.getHeader(HttpHeaderNormalized.getUpgrade(), true)));
        // Reset state for reused connection
        this.dataReceived = false;
        // Cancel previous timeout if any
        if (this.timeoutFuture != null) {
            this.timeoutFuture.cancel(false);
            this.timeoutFuture = null;
        }
        adapter.sendRequest(request, targetCtx);
        worker.selector.wakeup();
        // Schedule read timeout for first response data
        if (config.readTimeout > 0) {
            this.timeoutFuture = worker.manager.scheduleTimeout(timeoutTask, config.readTimeout);
        }
        adapter.receiveResponse(targetCtx);
    }

    public void close() {
        worker.cleanupConnection(this);
    }

    public boolean isTargetClosed() {
        return targetCtx.isChannelClosed();
    }
}