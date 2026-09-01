package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.exception.SocketException;
import io.github.wycst.wastnet.socket.conf.SocketOptions;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;

/**
 * High-performance TCP server implementation based on NIO selector.
 * <p>
 * Handles accepting incoming connections and dispatching them to workers.
 *
 * @since 2024-1-14
 * @author wangyc
 */
public class TCPServer extends NioEngine<TCPServer> {

    protected boolean localOnly;
    volatile Selector selector;
    ServerSocketChannel serverChannel;
    private ChannelWorker[] workers;
    /** Controls the AcceptDispatcher thread; decoupled from engineRunFlag for graceful shutdown. */
    volatile boolean acceptRunFlag;

    public TCPServer(int port) {
        this(port, new NioConfig());
    }

    public TCPServer(int port, NioConfig nioConfig) {
        super(port, nioConfig);
    }

    // ==================== Server-specific methods ====================

    /**
     * Set whether the server only accepts local connections (bound to 127.0.0.1)
     */
    public TCPServer localOnly(boolean localOnly) {
        this.localOnly = localOnly;
        return this;
    }

    /**
     * Enable SSL/TLS using OpenSSL PEM certificate and private key.
     * <p>
     * If the path does not exist on the filesystem, it is loaded as a classpath resource.
     *
     * @param certResource PEM certificate path or classpath resource
     * @param keyResource  PKCS#8 private key path or classpath resource
     * @return this TCPServer instance
     */
    public TCPServer pemSSL(String certResource, String keyResource) {
        super.sslContextFactory(new PEMSSLContextFactory(certResource, keyResource));
        return this;
    }

    /**
     * Enable SSL/TLS using OpenSSL PEM certificate and private key from input streams.
     *
     * @param certIn PEM certificate stream (e.g. from classpath or in-memory)
     * @param keyIn  PKCS#8 private key stream
     * @return this TCPServer instance
     */
    public TCPServer pemSSL(InputStream certIn, InputStream keyIn) {
        super.sslContextFactory(new PEMSSLContextFactory(certIn, keyIn));
        return this;
    }

    /**
     * Set connection filter for accept-level rejection.
     */
    public TCPServer connectionFilter(ConnectionFilter connectionFilter) {
        nioConfig.setConnectionFilter(connectionFilter);
        return this;
    }

    // ==================== Worker management ====================

    ChannelWorker[] workers() throws IOException {
        int workNum = nioConfig.getWorkerNum();
        ChannelWorker[] selectorWorks = new ChannelWorker[workNum];
        for (int i = 0; i < workNum; ++i) {
            selectorWorks[i] = new ChannelWorker(this);
        }
        return selectorWorks;
    }

    ChannelWorker nextWorker(int clientCnt, ChannelWorker[] workers) {
        if (nioConfig.option(SocketOptions.LOAD_BALANCE_TYPE) == "LEAST_CONN") {
            int minIdx = 0;
            int minConn = workers[0].getConnectionCount();
            for (int i = 1; i < workers.length; ++i) {
                int c = workers[i].getConnectionCount();
                if (c < minConn) {
                    minConn = c;
                    minIdx = i;
                    if (minConn == 0) {
                        break;
                    }
                }
            }
            return workers[minIdx];
        } else {
            return workers[clientCnt & (workers.length - 1)];
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Start the TCP server
     */
    public synchronized TCPServer start() {
        checkServerAvailable();
        try {
            engineRunFlag = true;
            acceptRunFlag = true;
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            ServerSocket serverSocket = serverChannel.socket();
            serverSocket.setReuseAddress(true);
            serverSocket.setReceiveBufferSize(65536);
            serverSocket.bind(localOnly ? new InetSocketAddress("127.0.0.1", port) : new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            this.initSslContext();
            final ChannelWorker[] ioWorkers = workers();
            this.workers = ioWorkers;
            // Startup diagnostic: surface runtime scheduling config so worker count
            // and CPU affinity mismatches (e.g. container cgroup limits) are visible.
            LOG.info("tcp server starting on port {} with {} io workers "
                    + "(availableProcessors={}, syncRunner={}, readBuffer={}, writeBuffer={})",
                    port, ioWorkers.length,
                    Runtime.getRuntime().availableProcessors(),
                    nioConfig.isSyncRunner(),
                    nioConfig.getReadBufferSize(),
                    nioConfig.getWriteBufferSize());
            // Create latch before starting threads: 1 AcceptDispatcher + N workers
            startLatch = new CountDownLatch(1 + ioWorkers.length);
            new AcceptDispatcher(this, ioWorkers).start();
            for (int i = 0; i < ioWorkers.length; ++i) {
                new Thread(ioWorkers[i], "worker-" + i).start();
            }
            // Wait for all threads to enter their event loops before calling onStarted()
            startLatch.await();
            startLatch = null;
        } catch (Throwable e) {
            stop();
            if(e instanceof BindException) {
                throw new SocketException("Failed to bind port " + port + ": " + e.getMessage(), e);
            }
            throw e instanceof RuntimeException ? (RuntimeException) e : new SocketException(e.getMessage(), e);
        }
        onStarted();
        return this;
    }

    /**
     * Callback after server has started. Subclasses may override for custom banner.
     */
    protected void onStarted() {
        LOG.info("server startup on {}", localOnly ? "127.0.0.1:" + port : "port " + port);
    }

    /**
     * Create connection runner based on SSL configuration
     */
    ChannelRunner createConnectionRunner(ChannelWorker worker, SocketChannel socketChannel) throws IOException {
        if (ssl) {
            return new ChannelSSLRunner(worker, socketChannel, nioConfig, getSSLContext(), sslCipherSuites);
        } else {
            return new ChannelRunner(worker, socketChannel, nioConfig);
        }
    }

    /**
     * Stop the service (can be restarted). Equivalent to {@code stop(false)}.
     */
    @Override
    public synchronized void stop() {
        stop(false);
    }

    /**
     * Stop the service (can be restarted).
     * <p>
     * When {@code closeConnections} is {@code true}, existing connections are closed
     * before stopping; otherwise they are left open (workers exit on {@code engineRunFlag}).
     * <p>
     * Note: if you do not need to restart the server, prefer {@link #shutdown()} which
     * also releases thread pools.
     *
     * @param closeConnections if {@code true}, close all existing connections before stopping
     */
    public synchronized void stop(boolean closeConnections) {
        try {
            if (!engineRunFlag) return;
            acceptRunFlag = false;
            if (closeConnections) {
                selector.wakeup();
                closeAllConnections();
            }
            engineRunFlag = false;
            selector.wakeup();
            selector.close();
            serverChannel.close();
            nioConfig.clear();
            onStopped();
        } catch (Throwable e) {
            throw e instanceof RuntimeException ? (RuntimeException) e : new SocketException(e.getMessage(), e);
        }
    }

    /**
     * Shutdown the service immediately: close all connections, stop accepting,
     * and shut down thread pools. The engine cannot be restarted after this call.
     */
    @Override
    public synchronized void shutdown() {
        closeAllConnections();
        super.shutdown();
    }

    /**
     * Attempt a graceful shutdown of the service:
     * <ol>
     *   <li>Stop accepting new connections (workers keep running)</li>
     *   <li>Wait up to {@code gracefulShutdownTimeout} ms for in-flight requests to complete</li>
     *   <li>Force-close remaining connections and complete shutdown</li>
     * </ol>
     * <p>
     * <b>Note:</b> This is a best-effort implementation. On close, per-connection
     * graceful close frames are sent (HTTP/2 GOAWAY, WebSocket CLOSE). It does
     * <b>not</b> guarantee all application-layer in-flight requests complete, nor that
     * HTTP/1.1 keep-alive connections reject new requests after shutdown starts.
     * For a fully clean shutdown, application-level coordination (e.g. load balancer
     * drain, health-check removal) is still required.
     */
    public synchronized void shutdownGraceful() {
        if (!engineRunFlag || workers == null) return;

        // Phase 1: Stop accepting new connections (workers keep running)
        acceptRunFlag = false;
        try {
            if (serverChannel == null) return;
            serverChannel.close();
            selector.wakeup();
        } catch (Throwable ignored) {}

        // Phase 2: Wait for connections to drain naturally
        long deadline = System.currentTimeMillis() + nioConfig.option(SocketOptions.GRACEFUL_SHUTDOWN_TIMEOUT_MS);
        while (System.currentTimeMillis() < deadline) {
            if (!hasInflightRequests()) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Phase 3: Force-close remaining connections + shut down thread pools
        shutdown();
    }

    /**
     * Callback after server has stopped. Subclasses may override for custom output.
     */
    protected void onStopped() {
        LOG.debug("tcp server stopped on port {} and resources released", port);
    }

    // ==================== Connection management helpers ====================

    private void closeAllConnections() {
        if (workers == null) return;
        for (ChannelWorker worker : workers) {
            worker.closeAllConnections();
        }
    }

    private boolean hasInflightRequests() {
        for (ChannelWorker worker : workers) {
            if (worker.hasInflightRequests()) return true;
        }
        return false;
    }
}
