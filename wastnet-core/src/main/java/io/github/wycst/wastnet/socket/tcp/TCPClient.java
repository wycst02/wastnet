package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelCodec;
import io.github.wycst.wastnet.socket.channel.ChannelWriter;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * NIO-based TCP client with built-in reconnect support.
 * <p>
 * Reuses the NIO engine infrastructure (workers, runners, channel context, SSL)
 * from the server side, providing a consistent API for both client and server.
 *
 * @author wangyc
 */
public class TCPClient<M> extends NioEngine<TCPClient<M>> {

    private final String host;
    private SocketChannel channel;
    private ChannelRunner runner;
    private ChannelWorker worker;
    private volatile boolean connected;
    private int connectTimeoutMs = 5000;
    private int reconnectMaxAttempts = 3;
    private long reconnectDelayMs = 1000;
    private volatile boolean autoReconnect;
    private ChannelWriter writer;

    public TCPClient(String host, int port) {
        this(host, port, new NioConfig());
    }

    public TCPClient(String host, int port, NioConfig nioConfig) {
        super(port, nioConfig);
        this.host = host;
    }

    // ==================== Connection Management ====================

    /**
     * Connect to the remote server.
     *
     * @throws IOException if connection fails
     */
    public synchronized TCPClient<M> connect() throws IOException {
        if (connected) {
            LOG.info("already connected");
            return this;
        }
        ensureWorker();
        try {
            this.channel = SocketChannel.open();
            this.channel.configureBlocking(false);
            this.channel.socket().setTcpNoDelay(true);
            this.channel.connect(new InetSocketAddress(host, port));
            long deadline = System.currentTimeMillis() + connectTimeoutMs;
            while (!this.channel.finishConnect()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("connect timeout to " + host + ":" + port);
                }
                Thread.yield();
            }
            this.runner = createClientRunner(this.channel, worker);
            worker.register(this.channel, this.runner);
            this.connected = true;
        } catch (Throwable e) {
            if (this.runner != null) {
                try { this.runner.release(); } catch (IOException ex) { LOG.warn("release runner on connect fail: {}", ex.getMessage()); }
            } else if (this.channel != null) {
                try { this.channel.close(); } catch (IOException ex) { LOG.warn("close channel on connect fail: {}", ex.getMessage()); }
            }
            throw e instanceof IOException ? (IOException) e : new IOException("connect failed", e);
        }
        // Hook auto-reconnect on close (only after success)
        if (autoReconnect) {
            runner.ctx.addCloseListener(() -> {
                if (autoReconnect && connected) {
                    connected = false; // prevent write() using stale runner during reconnect
                    executorService.submit(() -> {
                        try {
                            reconnect();
                        } catch (Throwable e) {
                            LOG.error("auto-reconnect failed", e);
                            connected = false;
                        }
                    });
                }
            });
        }
        LOG.info("connected to {}:{}", host, port);
        return this;
    }

    /**
     * Reconnect with exponential backoff.
     *
     * @throws IOException if all reconnect attempts fail
     */
    public synchronized TCPClient<M> reconnect() throws IOException {
        long delay = reconnectDelayMs;
        IOException lastException = null;
        for (int i = 0; i < reconnectMaxAttempts; ++i) {
            try {
                disconnect();
                return connect();
            } catch (IOException e) {
                lastException = e;
                LOG.info("reconnect attempt {} failed, retrying in {}ms", i + 1, delay);
                try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                delay = Math.min(delay * 2, 60000); // exponential backoff, cap at 60s
            }
        }
        throw lastException != null ? lastException : new IOException("reconnect failed after " + reconnectMaxAttempts + " attempts");
    }

    private void disconnect() {
        connected = false;
        if (runner != null) {
            try { runner.release(); } catch (IOException e) { LOG.warn("release runner on disconnect: {}", e.getMessage()); }
            runner = null;
        }
        if (channel != null) {
            try { channel.close(); } catch (IOException e) { LOG.warn("close channel on disconnect: {}", e.getMessage()); }
            channel = null;
        }
    }

    // ==================== Configuration ====================

    /** Set codec for both reading and writing (client-side). */
    public TCPClient<M> channelCodec(ChannelCodec<?> codec) {
        super.channelCodec(codec);
        this.writer = codec;
        return this;
    }

    public TCPClient<M> connectTimeout(int timeoutMs) {
        this.connectTimeoutMs = timeoutMs;
        return this;
    }

    public TCPClient<M> reconnectAttempts(int maxAttempts) {
        this.reconnectMaxAttempts = maxAttempts;
        return this;
    }

    public TCPClient<M> reconnectDelay(long delayMs) {
        this.reconnectDelayMs = delayMs;
        return this;
    }

    public TCPClient<M> autoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
        return this;
    }

    // ==================== Write API ====================
    /**
     * Send a message using the configured codec.
     * The codec encodes the message into a framed {@link java.nio.ByteBuffer},
     * which is then written and flushed to the server.
     *
     * @throws IOException if the write fails
     */
    @SuppressWarnings("unchecked")
    public synchronized void send(M message) throws IOException {
        java.nio.ByteBuffer buf = writer.write(runner.ctx, message);
        if (buf != null) {
            runner.ctx.writeFlush(buf);
        }
    }

    /**
     * Get the channel context for advanced operations (thread-safe).
     */
    public synchronized ChannelContext context() {
        return runner != null ? runner.ctx : null;
    }

    /**
     * Check if the client is connected (thread-safe).
     */
    public synchronized boolean isConnected() {
        return connected && channel != null && channel.isConnected();
    }

    // ==================== Internal ====================

    /**
     * Ensure the worker thread is started (once per TCPClient lifecycle).
     * The worker survives across connect/disconnect/reconnect cycles.
     * It is only stopped in {@link #close()}.
     */
    private void ensureWorker() throws IOException {
        if (worker != null) return;
        if (writer == null) {
            throw new IOException("channelCodec not configured, call channelCodec() before connect()");
        }
        engineRunFlag = true;
        worker = new ChannelWorker(this);
        new Thread(worker, "client-worker").start();
        LOG.info("TCPClient worker started");
    }

    private ChannelRunner createClientRunner(SocketChannel channel, ChannelWorker worker) throws Exception {
        if (ssl) {
            SSLContext ctx = getSSLContext();
            if (ctx == null) {
                (ctx = SSLContext.getInstance("TLS")).init(null, ChannelSSLContext.TRUST_ALL_MANAGERS, null);
            }
            return new ChannelSSLRunner(worker, new ChannelSSLContext(channel, new SSLEngineContext(ctx, sslCipherSuites, nioConfig.getApplicationProtocols(), nioConfig.getEnabledProtocols(), true)), nioConfig);
        } else {
            return new ChannelRunner(worker, channel, nioConfig);
        }
    }

    /**
     * Close the client and release all resources.
     */
    public synchronized void close() {
        disconnect();
        // Signal worker to stop its event loop
        engineRunFlag = false;
        if (worker != null) {
            worker.wakeup();
            worker = null;
        }
        super.stop();
    }
}
