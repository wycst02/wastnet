package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.channel.ChannelWriter;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Per-connection runner that handles read, decode and dispatch for a single SocketChannel.
 */
class ChannelRunner implements Runnable {
    static Log LOG = LogFactory.getLog(ChannelRunner.class);
    protected final ChannelWorker worker;
    protected final ChannelContext ctx;
    protected final NioConfig nioConfig;
    protected final ChannelReader<?> channelReader;
    protected final ChannelHandler<?> channelHandler;
    final ByteBuffer byteBuffer;

    protected boolean ready;
    protected boolean closed;
    volatile boolean runFlag;

    // AUTO fast-path: sync only after FAST_PATH_MIN_STREAK consecutive fast (<FAST_PATH_THRESHOLD_NANOS) requests; any slow one resets.
    private volatile boolean isFastPath;
    private int fastStreak;

    // A request is "fast" when it completes under this duration (nanoseconds).
    static final long FAST_PATH_THRESHOLD_NANOS = 1_000_000L;
    // Consecutive fast requests required before a connection switches to synchronous dispatch.
    static final int FAST_PATH_MIN_STREAK = 3;
    // Cap reads per cycle so one connection can't monopolize the read path and starve others.
    static final int MAX_READ_WAVE = 8;

    public boolean isRunFlag() {
        // true while busy (runFlag) or an HTTP/1.1 streaming body is read by the handler (readHandover); stops the worker re-dispatching run0 and racing on the shared socket.
        return runFlag || ctx.isReadHandedOver();
    }

    /**
     * Sync dispatch decision for this connection: explicit sync-mode config wins,
     * otherwise fall back to the per-connection fast-path flag.
     */
    public final boolean isSync() {
        Boolean syncMode = nioConfig.syncMode;
        return syncMode != null ? syncMode : isFastPath;
    }

    ChannelRunner(ChannelWorker worker, SocketChannel channel, NioConfig nioConfig) throws IOException {
        this(worker, new ChannelContext(channel, nioConfig.getWriteBufferSize()), nioConfig);
    }

    static final ChannelHandler<Object> UNDO = (ctx, message) -> {
    };

    ChannelRunner(ChannelWorker worker, final ChannelContext ctx, NioConfig nioConfig) throws IOException {
        this.worker = worker;
        ctx.setWorker(worker);
        ctx.setNioConfig(nioConfig);
        this.ctx = ctx;
        this.nioConfig = nioConfig;
        this.channelReader = nioConfig.getChannelReader();
        this.channelHandler = nioConfig.getChannelHandler() != null ? nioConfig.getChannelHandler() : UNDO;
        channelHandler.onConnected(ctx);
        ctx.setChannelHandler(channelHandler);
        ctx.setChannelReader(channelReader);
        if (channelReader instanceof ChannelWriter) {
            ctx.setChannelWriter((ChannelWriter<?>) channelReader);
        }
        IdleStateHandler idleStateHandler = nioConfig.getIdleStateHandler();
        if (idleStateHandler != null) {
            ctx.setIdleTrigger(idleStateHandler);
        }
        this.byteBuffer = createReadByteBuffer();
    }

    ByteBuffer createReadByteBuffer() {
        return nioConfig.isSyncMode() ? worker.workerBuffer : ByteBuffer.allocate(nioConfig.getReadBufferSize());
    }

    protected final void run0() {
        // Only predict fast path in AUTO mode (syncMode == null); explicit SYNC/ASYNC ignores it.
        boolean autoMode = nioConfig.syncMode == null;
        long startTime = 0L;
        if (autoMode) startTime = System.nanoTime();
        try {
            runFlag = true;
            before();
            // !ready: pre-ready hook suspended (e.g. SSL handshake waits for more peer data); skip reads this round
            if (this.closed || !this.ready) return;
            try {
                if (ctx.isChannelClosed()) {
                    release();
                    return;
                }
                try {
                    if (handleChannelRead() == -1) {
                        LOG.debug("channel close by client {}", ctx.getId());
                        release();
                    }
                } catch (Throwable throwable) {
                    if (nioConfig.isPrintReadErrorLog()) {
                        LOG.error("read error", throwable);
                    }
                    release();
                }
            } catch (Exception e) {
                if (e instanceof ClosedSelectorException) {
                    LOG.debug("channel close");
                    try {
                        release();
                    } catch (IOException ignored) {
                        LOG.debug("release failed on closed selector");
                    }
                } else {
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }
            }
        } finally {
            runFlag = false;
            if (autoMode) {
                if ((System.nanoTime() - startTime) < FAST_PATH_THRESHOLD_NANOS) {
                    isFastPath = (++fastStreak >= FAST_PATH_MIN_STREAK);
                } else {
                    fastStreak = 0;
                    isFastPath = false;
                }
            }
        }
    }

    public final void run() {
        run0();
    }

    final void before() {
        if (!ready) {
            // ready may already be true if the hook initialized the reader itself (plaintext fallback)
            if (!beforeReady() || ready || ctx.isCloseResolved()) return;
            try {
                channelReader.init(ctx);
                ready = true;
            } catch (Throwable throwable) {
                if (nioConfig.isPrintReadErrorLog()) {
                    LOG.error("Channel init failed", throwable);
                }
                ready = false;
            }
        }
    }

    /**
     * One-time hook invoked before the runner starts serving reads.
     *
     * @return true if ready to serve; false if still waiting for more events
     *         (e.g. SSL handshake suspended until the next OP_READ).
     *         An implementation that initializes the reader itself must set {@link #ready};
     *         before() then skips its own init.
     */
    protected boolean beforeReady() {
        return true;
    }

    /**
     * Wraps {@link ChannelContext#channelRead(ByteBuffer)}: returns 0 (no data) once the
     * request body is handed over to the handler thread, so the decode loop stops draining
     * the socket and the handler can read the remaining body without contention.
     */
    final int channelReadInternal(ByteBuffer buf) throws IOException {
        return ctx.isReadHandedOver() ? 0 : ctx.channelRead(buf);
    }

    /**
     * Handle channel read operations
     *
     * @return the number of bytes read, or -1 if channel is closed
     * @throws IOException if read operation fails
     */
    protected int handleChannelRead() throws IOException {
        ByteBuffer buf = byteBuffer;
        int size = channelReadInternal(buf), cap = buf.capacity(), wave = 0;
        do {
            buf.flip();
            try {
                read(buf);
            } finally {
                buf.clear();
            }
            if(size < cap || ++wave > MAX_READ_WAVE) return size;
        } while ((size = channelReadInternal(buf)) > 0);
        return size;
    }

    /**
     * Release channel resources
     *
     * @throws IOException if close operation fails
     */
    public final void release() throws IOException {
        try {
            ctx.close();
        } finally {
            this.closed = true;
        }
    }

    /**
     * Decode application data that has already been read from the channel.
     * <p>
     * When {@link NioConfig#isPrintApplicationMessage()} is enabled, the payload
     * is logged as text and hex for debugging. Decoding errors are forwarded to
     * {@link ChannelHandler#onException} and then rethrown to the caller.
     *
     * @param buf the buffer holding application-layer bytes to decode
     * @throws IOException if the channel reader fails to decode the payload
     */
    public final void read(ByteBuffer buf) throws IOException {
        if (buf.hasRemaining()) {
            if (nioConfig.isPrintApplicationMessage()) {
                int len = buf.remaining();
                LOG.debug("application message hex dump ({} bytes):\n{}",
                        len, Utils.hexDump(buf.array(), buf.position(), len));
            }
            try {
                channelReader.decode(ctx, buf);
            } catch (Throwable throwable) {
                channelHandler.onException(ctx, throwable);
                throw throwable;
            }
        }
    }

    /**
     * Set read selection key
     *
     * @param readKey the selection key for read operations
     */
    public void setReadKey(SelectionKey readKey) {
        ctx.setReadKey(readKey);
    }

    /**
     * Close the channel
     *
     * @throws IOException if close operation fails
     */
    public void close() throws IOException {
        try {
            wakeup();
            ctx.close();
        } catch (Throwable err) {
            LOG.error("close error", err);
        }
    }

    /**
     * Wake up the channel and reader
     */
    final void wakeup() {
        ctx.wakeup();
        channelReader.wakeup();
    }
}
