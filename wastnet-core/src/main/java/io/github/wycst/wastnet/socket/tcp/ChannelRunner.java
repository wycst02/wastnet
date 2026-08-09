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
import java.util.Arrays;

/**
 * Per-connection runner that handles read, decode and dispatch for a single SocketChannel.
 */
class ChannelRunner extends Thread {
    protected static final Log LOG = LogFactory.getLog(ChannelRunner.class);
    protected final ChannelWorker worker;
    protected final ChannelContext ctx;
    protected final NioConfig nioConfig;
    protected final ChannelReader channelReader;
    protected final ChannelHandler channelHandler;

    protected boolean ready;
    protected boolean closed;
    volatile boolean runFlag;
    final ByteBuffer byteBuffer;

    // Fast path flag: true = sync execution, false = async execution
    // Initialized to false to default async for the first request
    private volatile boolean isFastPath;

    public boolean isRunFlag() {
        return runFlag;
    }

    /**
     * Predict whether to use sync mode for next execution
     *
     * @return true for fast path (sync), false for slow path (async)
     */
    public boolean predictSync() {
        return isFastPath;
    }

    ChannelRunner(ChannelWorker worker, SocketChannel channel, NioConfig nioConfig) throws IOException {
        this(worker, new ChannelContext(channel, nioConfig.getWriteBufferSize()), nioConfig);
    }

    static final ChannelHandler<Object> UNDO = new ChannelHandler<Object>() {
        @Override
        public void onHandle(ChannelContext ctx, Object message) {
        }
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
        return nioConfig.isSyncRunner() ? worker.workerBuffer : ByteBuffer.allocate(nioConfig.getReadBufferSize());
    }

    protected final void run0() {
        long startTime = System.nanoTime();
        try {
            runFlag = true;
            before();
            if (this.closed) return;
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
            // Mark as fast path if execution time < 1ms (1,000,000 nanoseconds)
            isFastPath = (System.nanoTime() - startTime) < 1000000L;
        }
    }

    public final void run() {
        run0();
    }

    final void before() {
        if (!ready) {
            try {
                beforeReady();
            } finally {
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
    }

    /**
     * Hook method called before channel is ready
     */
    protected void beforeReady() {
    }

    /**
     * Handle channel read operations
     *
     * @return the number of bytes read, or -1 if channel is closed
     * @throws IOException if read operation fails
     */
    protected int handleChannelRead() throws IOException {
        ByteBuffer buf = byteBuffer;
        ctx.channelRead(buf);
        int size;
        do {
            buf.flip();
            try {
                read(buf);
            } finally {
                buf.clear();
            }
        } while ((size = ctx.channelRead(buf)) > 0);
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
                byte[] data = Arrays.copyOf(buf.array(), buf.limit());
                LOG.info("text \n{}", new String(data));
                LOG.info("hex \n{}", Utils.printHexString(data, ' '));
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
        } catch (Throwable ignored) {
            LOG.debug("close error", ignored);
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
