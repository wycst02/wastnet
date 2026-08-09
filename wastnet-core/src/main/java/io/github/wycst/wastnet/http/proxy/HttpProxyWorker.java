package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Worker thread that manages a shared Selector for multiple HttpProxyConnection instances.
 *
 * @author wangyc
 */
public class HttpProxyWorker extends Thread {

    static final Log log = LogFactory.getLog(HttpProxyWorker.class);

    // "HTTP/1.1 101" = 12 bytes, split as long(8) + int(4) for efficient comparison
    private static final long UPGRADE_101_LONG;
    private static final int UPGRADE_101_INT;

    static {
        byte[] prefix = "HTTP/1.1 101".getBytes();
        ByteBuffer tmp = ByteBuffer.wrap(prefix);
        UPGRADE_101_LONG = tmp.getLong(0);
        UPGRADE_101_INT = tmp.getInt(8);
    }

    /**
     * Check if the buffer starts with "HTTP/1.1 101" using getLong+getInt.
     * Buffer must be in flip state with at least 12 bytes remaining.
     */
    static boolean isUpgrade101(ByteBuffer buffer) {
        return buffer.getLong(0) == UPGRADE_101_LONG && buffer.getInt(8) == UPGRADE_101_INT;
    }

    Selector selector;
    volatile boolean registering;
    final Map<Long, HttpProxyConnection> connections = new ConcurrentHashMap<Long, HttpProxyConnection>();
    final HttpProxyWorkerManager manager;
    private volatile boolean shutdown = false;

    public HttpProxyWorker() throws IOException {
        this(HttpProxyWorkerManager.GLOBAL_WORKER_MANAGER);
    }

    public HttpProxyWorker(HttpProxyWorkerManager manager) throws IOException {
        this.manager = manager;
        this.selector = Selector.open();
    }

    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(16384);
        while (!shutdown) {
            try {
                int ready = selector.select();
                if (ready == 0) {
                    if (registering) {
                        // synchronize here to solve the first channel registration and reading issues(windows platform)
                        synchronized (this) {
                            wait();
                        }
                    }
                    continue;
                }
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    try {
                        if (!key.isValid()) {
                            continue;
                        }
                        HttpProxyConnection conn = (HttpProxyConnection) key.attachment();
                        final boolean isTarget = key == conn.targetKey;
                        ChannelContext writeCtx = isTarget ? conn.clientCtx : conn.targetCtx;
                        if (writeCtx.isChannelClosed()) {
                            cleanupConnection(conn);
                            continue;
                        }
                        ChannelContext readCtx = isTarget ? conn.targetCtx : conn.clientCtx;
                        int n = -1;
                        try {
                            n = readCtx.read(buffer);
                        } catch (Exception ignored) {
                        }
                        if (buffer.position() > 0) {
                            buffer.flip();
                            if(isTarget) {
                                conn.checkDataReceived(buffer);
                            }
                            conn.adapter.onData(buffer, writeCtx, isTarget, conn);
                            buffer.clear();
                        }
                        if (n < 0) {
                            cleanupConnection(conn);
                        }
                    } catch (Exception e) {
                        log.error("[HttpProxy] Error processing key: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.error("[HttpProxy] Selector error: {}", e.getMessage());
            }
        }
        try {
            selector.close();
        } catch (IOException ignored) {
        }
    }

    void cleanupConnection(HttpProxyConnection connection) {
        // Idempotent: only first caller wins
        if (connection.closed) {
            return;
        }
        connection.closed = true;
        connections.remove(connection.connectionId);
        // Cancel read timeout future
        ScheduledFuture<?> future = connection.timeoutFuture;
        if (future != null) {
            future.cancel(false);
            connection.timeoutFuture = null;
        }
        log.debug("[Proxy] cleanup connection: {} (route: {})", connection.connectionId, connection.routeId);
        if(!connection.clientCtx.isChannelClosed()) {
            // Remove close listener to avoid duplicate cleanup
            connection.clientCtx.removeCloseListener(connection.closeListener);
        }
        // Close target channel
        connection.targetCtx.close();
        // Close client channel if registered for bidirectional relay
        if (connection.clientKey != null) {
            connection.clientCtx.close();
        }
    }

    public void clearConnections(String routeId) {
        for (HttpProxyConnection connection : connections.values()) {
            if (routeId.equals(connection.routeId)) {
                cleanupConnection(connection);
            }
        }
    }

    public void shutdown() {
        shutdown = true;
        selector.wakeup();
    }
}