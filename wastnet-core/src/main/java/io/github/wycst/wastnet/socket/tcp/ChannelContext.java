package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.channel.ChannelWriter;
import io.github.wycst.wastnet.socket.conf.Option;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandlerTrigger;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Channel context representing a TCP connection session.
 *
 * <p>This class encapsulates the state and operations for a single channel connection:</p>
 * <ul>
 *   <li>Wraps underlying NIO SocketChannel</li>
 *   <li>Read/write buffer management (write buffer for batch write optimization)</li>
 *   <li>Decoder and handler binding</li>
 *   <li>Address caching (remote/local to avoid repeated queries)</li>
 *   <li>Connection lifecycle management (close/handler callbacks)</li>
 *   <li>Idle state trigger (heartbeat/timeout detection)</li>
 *   <li>Attachment and attribute management</li>
 *   <li>Scheduled task execution</li>
 * </ul>
 *
 * <p>Each connection has exactly one ChannelContext instance that is created when the connection is accepted.</p>
 *
 * @see ChannelHandler
 * @see ChannelReader
 */
public class ChannelContext {

    final long id;
    final SocketChannel channel;
    SelectionKey readKey;
    IdleStateHandlerTrigger idleStateHandlerTrigger;
    private ChannelHandler<Object> channelHandler;
    private ChannelReader<?> channelReader;
    private ChannelWriter<?> channelWriter;

    // Cache address information to improve performance
    private volatile InetSocketAddress cachedRemoteAddress;
    private volatile InetSocketAddress cachedLocalAddress;
    private volatile boolean addressCacheInitialized = false;

    private Object attachment;

    /** Framework-internal immutable binding — set once per connection, cleared on close. */
    private Object binding;

    private Map<String, Object> attributes;
    private final Object lock = new Object();
    /**
     * Persisted flag set by {@link #wakeup()} (invoked only when readable data is available),
     * checked before {@code lock.wait()} to avoid lost wakeup when a notify races ahead of the wait.
     */
    private boolean readReady;
    final ByteBuffer byteBuffer;
    // Short sleep for busy waiting in worker thread
    // static final long SHORT_SLEEP_MILLIS = 1;
    private ChannelWorker worker;
    NioConfig nioConfig;
    /** Connection-level read timeout in milliseconds, resolved from NioConfig (SocketOptions.READ_TIMEOUT_MS). */
    protected long readTimeoutMs = SocketOptions.READ_TIMEOUT_MS.value;
    /** Connection-level write timeout in milliseconds, resolved from NioConfig (SocketOptions.WRITE_TIMEOUT_MS). */
    protected long writeTimeoutMs = SocketOptions.WRITE_TIMEOUT_MS.value;
    final AtomicBoolean closeResolved = new AtomicBoolean(false);
    /** Fired after readKey.cancel() but before channel.close() — TCP is still writable. */
    private Runnable clearListener;
    private List<Runnable> closeListeners;
    /** empty context **/
    public final static ChannelContext EMPTY_CONTEXT = new ChannelContext(0, null, 0);
    static {
        EMPTY_CONTEXT.closeResolved.set(true); // mark closed so close() short-circuits and never touches the null channel
    }

    /**
     * Create ChannelContext with specified id.
     *
     * @param id the context id
     * @param channel the socket channel
     * @throws IOException if channel operation fails
     */
    ChannelContext(long id, SocketChannel channel) throws IOException {
        this(id, channel, 0);
    }

    /**
     * Create ChannelContext with auto-generated id.
     *
     * @param channel the socket channel
     * @param bufferSize the write buffer size
     * @throws IOException if channel operation fails
     */
    public ChannelContext(SocketChannel channel, int bufferSize) throws IOException {
        this(Utils.id(), channel, bufferSize);
    }

    /**
     * Create ChannelContext with specified id and buffer size.
     *
     * @param id the context id
     * @param channel the socket channel
     * @param bufferSize the write buffer size
     */
    public ChannelContext(long id, SocketChannel channel, int bufferSize) {
        this.id = id;
        this.channel = channel;
        if (bufferSize <= 0) {
            this.byteBuffer = null;
        } else {
            this.byteBuffer = ByteBuffer.allocate(bufferSize);
        }
    }

    /** Refreshes the cached timeout values from the currently bound NioConfig (must be called after nioConfig is set). */
    private void refreshTimeoutOptions() {
        this.readTimeoutMs = option(SocketOptions.READ_TIMEOUT_MS);
        this.writeTimeoutMs = option(SocketOptions.WRITE_TIMEOUT_MS);
    }

    public int getWriteBufferSize() {
        return byteBuffer == null ? 0 : byteBuffer.capacity();
    }

    final boolean isWorkerThread() {
        if (worker == null) {
            return true;
        }
        return ChannelWorker.isSyncWorkerThread();
    }

    // ==================== Write Backpressure (OP_WRITE) ====================

    private final Object writeLock = new Object();

    /**
     * Block until the channel is writable (TCP send buffer has room).
     * <p>
     * <b>Worker-thread path:</b> short {@code Thread.sleep(1)} retry (can't block for OP_WRITE).
     * <b>Thread-pool path:</b> register OP_WRITE on the selector and {@code wait()} efficiently.
     *
     * @param timeoutMs max wait time in milliseconds (&le;0 means no timeout)
     * @return true if channel became writable, false on timeout
     * @throws IOException if an I/O error occurs
     */
    public boolean waitForWrite(long timeoutMs) throws IOException {
        if (isWorkerThread()) {
            // Worker thread: can't block for OP_WRITE (would deadlock), sleep briefly
            try {
                Thread.sleep(1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return true; // caller retries transferTo
        }
        // Thread-pool thread: register OP_WRITE and wait
        doRegisterWrite();
        return doAwaitWritable(timeoutMs);
    }

    private void doRegisterWrite() {
        if (readKey != null && readKey.isValid()) {
            readKey.interestOps(readKey.interestOps() | SelectionKey.OP_WRITE);
            worker.wakeup();
        }
    }

    private void doRemoveWriteInterest() {
        if (readKey != null && readKey.isValid()) {
            readKey.interestOps(readKey.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private boolean doAwaitWritable(long timeoutMs) {
        synchronized (writeLock) {
            try {
                writeLock.wait(timeoutMs > 0 ? timeoutMs : 30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                doRemoveWriteInterest();
                return false;
            }
        }
        doRemoveWriteInterest();
        return true;
    }

    /**
     * Called from the worker thread when OP_WRITE fires.
     */
    void wakeupWrite() {
        // Drop OP_WRITE up front so the worker's select() won't busy-spin until the writer is scheduled.
        doRemoveWriteInterest();
        synchronized (writeLock) {
            writeLock.notifyAll();
        }
    }

    public void close() {
        // Atomic lock-grab: only the first thread to reach here executes the close
        // logic. Concurrent/duplicate close() calls (worker thread vs idle-scan thread,
        // shutdown thread, async business thread) all return immediately with no
        // double onClosed / double decrement / double listeners.
        if (!closeResolved.compareAndSet(false, true)) {
            return;
        }
        if(readKey != null) readKey.cancel();
        try { channelReader.onClosed(this); } catch (Throwable ignored) { }
        try { channelHandler.onClosed(this); } catch (Throwable ignored) { }
        try { channel.close(); } catch (Throwable ignored) { }
        // Wake up any thread blocked waiting for write (e.g. sendFileZeroCopy)
        wakeupWrite();
        // Release idle-state trigger even if onClosed above threw
        if (idleStateHandlerTrigger != null) {
            idleStateHandlerTrigger.release();
        }
        // Clear attributes even if onClosed above threw
        if (attributes != null) {
            attributes.clear();
        }
        // Notify all close listeners
        if (closeListeners != null) {
            for (Runnable listener : closeListeners) {
                try { listener.run(); } catch (Throwable ignored) {}
            }
            closeListeners = null;
        }
        if (worker != null) {
            worker.decrementConnectionCount();
            worker = null;
        }
        attachment = null;
        binding = null;
        attributes = null;
    }

    /**
     * Add a listener to be notified when this channel is closed.
     * Multiple listeners can be added and will be notified in order.
     *
     * @param listener the listener to add
     */
    public void addCloseListener(Runnable listener) {
        if (closeListeners == null) {
            closeListeners = new ArrayList<>();
        }
        closeListeners.add(listener);
    }

    /**
     * Remove a previously added close listener.
     *
     * @param listener the listener to remove
     */
    public void removeCloseListener(Runnable listener) {
        if (closeListeners != null) {
            closeListeners.remove(listener);
        }
    }

    /**
     * Register a listener that fires <b>before</b> the TCP socket is closed,
     * after {@code readKey.cancel()}. The channel is still writable at this point,
     * so protocol-level close frames (GOAWAY, WebSocket CLOSE) can be sent.
     * <p>
     * Unlike {@link #addCloseListener(Runnable)} which fires <i>after</i> the socket
     * is closed, this listener fires <i>before</i> {@code channel.close()}.
     * <p>
     * The listener is executed by {@link #fireClearListener()}, which is called
     * from {@code closeAllConnections()} during shutdown. It is <b>not</b> triggered
     * on normal connection teardown (client disconnect, idle timeout, or explicit
     * {@code connection.close()}).
     * <p>
     * Only one clear listener is supported; setting again overwrites the previous one.
     * <p>
     * <b>Note:</b> The listener runs synchronously on the thread that calls
     * {@code closeAllConnections()} (typically the shutdown thread). It <b>must be
     * lightweight and fast</b> — avoid blocking I/O, long loops, or locking.
     * A slow listener will delay the entire shutdown sequence.
     *
     * @param listener the clear listener
     */
    public void addClearListener(Runnable listener) {
        this.clearListener = listener;
    }

    /**
     * Fire the clear listener if one is registered. Called from
     * {@code ChannelWorker.closeAllConnections()} during shutdown.
     */
    void fireClearListener() {
        if (clearListener != null) {
            try {
                clearListener.run();
            } catch (Throwable ignored) {
            } finally {
                clearListener = null;
            }
        }
    }

    /**
     * Write application data
     *
     * @param bytes the bytes to write
     * @return the number of bytes written
     * @throws IOException if the channel is closed
     */
    public final int write(byte[] bytes) throws IOException {
        return write(ByteBuffer.wrap(bytes));
    }

    /**
     * Write application data with offset and length
     *
     * @param bytes  the bytes to write
     * @param offset the starting offset
     * @param length the number of bytes to write
     * @return the number of bytes written
     * @throws IOException if the channel is closed
     */
    public int write(byte[] bytes, int offset, int length) throws IOException {
        return write(ByteBuffer.wrap(bytes, offset, length));
    }

    /**
     * Write application data
     *
     * @param buf the buffer to write
     * @return the number of bytes written
     * @throws IOException if the channel is closed
     */
    public int write(ByteBuffer buf) throws IOException {
        if (byteBuffer == null) {
            return channelWrite(buf);
        }

        // If data size is greater than or equal to buffer capacity, write directly to improve efficiency
        if (buf.remaining() >= byteBuffer.capacity()) {
            // Flush existing data in buffer first
            if (byteBuffer.position() > 0) {
                flushBuffer();
            }
            return channelWrite(buf);
        }

        // Small data handling: complete in at most two operations
        int totalWritten = 0;

        // First: try direct transfer
        int toTransfer = Math.min(buf.remaining(), byteBuffer.remaining());
        if (toTransfer > 0) {
            totalWritten += copyBufferData(buf, toTransfer);
        }

        // Second: if there is still data, flush then transfer
        if (buf.hasRemaining()) {
            flushBuffer();
            totalWritten += copyBufferData(buf, buf.remaining());
        }
        return totalWritten;
    }

    /** Write data into the buffer under the channel lock without flushing, so it can be carried out by a later flush. */
    public final void writeSync(ByteBuffer buf) throws IOException {
        synchronized(this) {
            write(buf);
        }
    }

    /**
     * Write application data and flush
     *
     * @param buf the buffer to write
     * @throws IOException if the channel is closed
     */
    public final void writeFlush(ByteBuffer buf) throws IOException {
        synchronized(this) {
            write(buf);
            flush();
        }
    }

    /**
     * Write application data and flush
     *
     * @param buf the bytes to write
     * @throws IOException if the channel is closed
     */
    public final void writeFlush(byte[] buf) throws IOException {
        synchronized(this) {
            write(buf);
            flush();
        }
    }

    /**
     * Write application data and flush without throwing.
     *
     * @param buf the bytes to write
     */
    public final void writeFlushWithoutThrow(byte[] buf) {
        if (channel == null) return;
        synchronized(this) {
            try {
                write(buf);
                flush();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Write multiple buffers and flush once.
     *
     * <p>All buffers are written and a single flush is performed inside one
     * synchronized(this) critical section, so the resulting bytes are written
     * contiguously without interleaving from other threads. This is intended for
     * writing an atomic frame sequence (e.g. an HTTP/2 HEADERS frame followed by
     * its CONTINUATION frames) as one unit.
     *
     * @param bufs the buffers to write
     * @throws IOException if the channel is closed
     */
    public final void writeAllFlush(ByteBuffer... bufs) throws IOException {
        writeAllFlush(Arrays.asList(bufs));
    }

    /**
     * Write multiple buffers from a list and flush once.
     *
     * <p>Same semantics as the varargs overload, but accepts a list, which is
     * convenient when the frame count is not known statically (e.g. a HEADERS
     * frame followed by a variable number of CONTINUATION frames). All buffers
     * are written and flushed inside a single synchronized(this) critical
     * section, guaranteeing contiguous, non-interleaved output.
     *
     * @param bufList the buffers to write
     * @throws IOException if the channel is closed
     */
    public final void writeAllFlush(List<ByteBuffer> bufList) throws IOException {
        synchronized (this) {
            for (ByteBuffer buf : bufList) {
                write(buf);
            }
            flush();
        }
    }

    /**
     * Copy buffer data core method
     *
     * @param buf        the source buffer
     * @param toTransfer the number of bytes to transfer
     * @return the number of bytes transferred
     */
    private int copyBufferData(ByteBuffer buf, int toTransfer) {
        if (buf.hasArray()) {
            System.arraycopy(buf.array(), buf.arrayOffset() + buf.position(),
                    byteBuffer.array(), byteBuffer.arrayOffset() + byteBuffer.position(),
                    toTransfer);
            buf.position(buf.position() + toTransfer);
            byteBuffer.position(byteBuffer.position() + toTransfer);
        } else {
            int oldLimit = buf.limit();
            buf.limit(buf.position() + toTransfer);
            byteBuffer.put(buf);
            buf.limit(oldLimit);
        }
        return toTransfer;
    }

    /**
     * Flush buffer data to channel
     *
     * @throws IOException if flush fails
     */
    private void flushBuffer() throws IOException {
        byteBuffer.flip();
        channelWrite(byteBuffer);
        byteBuffer.clear();
    }

    /**
     * Flush all buffered data
     *
     * @throws IOException if flush fails
     */
    public void flush() throws IOException {
        if (byteBuffer != null) {
            flushBuffer();
        }
    }

    /**
     * Write buffer data to channel
     *
     * @param buf the buffer to write
     * @return the number of bytes written
     * @throws IOException if write fails or timeout
     */
    protected final int channelWrite(ByteBuffer buf) throws IOException {
        int len = buf.remaining();
        if (len == 0) return 0;
        try {
            final long deadline = writeTimeoutMs > 0
                    ? System.currentTimeMillis() + writeTimeoutMs : Long.MAX_VALUE;
            while (buf.hasRemaining()) {
                if(isChannelClosed()) {
                    close();
                    return -1;
                }
                int written = channel.write(buf);
                if (written < 0) {
                    throw new IOException("Channel closed during write");
                }
                if (written == 0) {
                    waitForWrite(1);
                }
                if (System.currentTimeMillis() > deadline) {
                    close();
                    throw new SocketTimeoutException("Write timeout after " + writeTimeoutMs + "ms");
                }
            }
            return len;
        } catch (IOException e) {
            close();
            return -1;
        } finally {
            if (idleStateHandlerTrigger != null && !closeResolved.get()) {
                idleStateHandlerTrigger.onWriteTriggered();
            }
        }
    }

    /**
     * Read data from channel into buffer.
     * Triggers idle state handler on successful read.
     *
     * @param buf the buffer to read into
     * @return the number of bytes read, -1 if channel is closed
     * @throws IOException if read fails
     */
    protected final int channelRead(ByteBuffer buf) throws IOException {
        int len = 0, n;
        try {
            if (isChannelClosed()) return -1;
            while ((n = channel.read(buf)) > 0) {
                len += n;
            }
            return len > 0 ? len : n;
        } finally {
            if (idleStateHandlerTrigger != null && len > 0) {
                idleStateHandlerTrigger.onReadTriggered();
            }
        }
    }

    /**
     * Read data from channel into buffer (non-blocking).
     * Reads as many bytes as currently available in the channel.
     *
     * @param buf the buffer to read into
     * @return the number of bytes read, 0 if no data available, -1 if channel is closed
     * @throws IOException if read fails
     */
    public int read(ByteBuffer buf) throws IOException {
        return channelRead(buf);
    }

    /**
     * Blocking read until the entire byte array is filled
     *
     * @param b target byte array
     * @return number of bytes successfully read (equals b.length), returns -1 if channel is closed
     * @throws IOException if channel is closed or IO exception occurs during reading
     */
    public final int readFully(byte[] b) throws IOException {
        return readFully(b, 0, b.length);
    }

    /**
     * Blocking read until the specified length of byte data is filled
     * Returns -1 if channel is closed, otherwise returns the requested length
     *
     * <p>In extreme cases, the channel immediately closes after reading some bytes, resulting in the result still returning -1. This situation can be used to determine protocol errors that depend on length</p>
     *
     * @param b   target byte array
     * @param off array starting offset
     * @param len number of bytes to read
     * @return -1 if channel is closed, otherwise returns len parameter value
     * @throws IOException               if channel is closed or IO exception occurs during reading
     * @throws SocketTimeoutException    if read timeout occurs (configured by wastnet.socket.read-timeout-ms)
     * @throws IndexOutOfBoundsException if off or len parameters do not meet preconditions
     */
    public final int readFully(byte[] b, int off, final int len) throws IOException {
        return readFully(b, off, len, readTimeoutMs);
    }

    /**
     * Blocking read until the specified length of byte data is filled with timeout
     * Returns -1 if channel is closed, otherwise returns the requested length
     *
     * <p>In extreme cases, the channel immediately closes after reading some bytes, resulting in the result still returning -1. This situation can be used to determine protocol errors that depend on length</p>
     *
     * @param b         target byte array
     * @param off       array starting offset
     * @param len       number of bytes to read
     * @param timeoutMs timeout in milliseconds, set to 0 if negative
     * @return -1 if channel is closed, otherwise returns len parameter value
     * @throws IOException               if channel is closed or IO exception occurs during reading
     * @throws SocketTimeoutException    if read timeout occurs
     * @throws IndexOutOfBoundsException if off or len parameters do not meet preconditions
     */
    public int readFully(byte[] b, int off, final int len, long timeoutMs) throws IOException {
        if (len == 0) return 0;
        ByteBuffer byteBuffer = ByteBuffer.wrap(b, off, len);
        long startTime = System.currentTimeMillis();
        while (true) {
            int size = channelRead(byteBuffer);
            if (size == -1) return -1;
            if (byteBuffer.hasRemaining()) {
                awaitReadableWithTimeout(startTime, timeoutMs);
            } else {
                break;
            }
        }
        return len;
    }

    protected void awaitReadableWithTimeout(long startTime, long timeoutMs) throws SocketTimeoutException {
        // Check timeout (0 means unlimited)
        if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs) {
            close();
            throw new SocketTimeoutException("Read timeout after " + timeoutMs + "ms");
        }
        if (timeoutMs < 0) timeoutMs = 0;
        final boolean isWorkerThread = isWorkerThread();
        if (isWorkerThread) {
            Thread.yield();
        } else {
            awaitReadableInternal(timeoutMs);
        }
    }

    void awaitReadableInternal(long timeout) {
        synchronized (lock) {
            // Skip waiting if data already became available, avoiding the lost-wakeup window.
            if (readReady) {
                readReady = false;
                return;
            }
            try {
                lock.wait(timeout);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Wake up waiting threads. Called only when readable data is available,
     * so the persisted readReady flag always indicates data can be read.
     */
    protected void wakeup() {
        synchronized (lock) {
            readReady = true;
            lock.notifyAll();
        }
    }

    /**
     * Check if this is an SSL channel
     *
     * @return true if SSL, false otherwise
     */
    public boolean isSSL() {
        return false;
    }

    /**
     * Check if channel is closed
     *
     * @return true if closed, false otherwise
     */
    public boolean isChannelClosed() {
        return !channel.isOpen();
    }

    /**
     * Get the underlying socket channel
     *
     * @return the socket channel
     */
    public SocketChannel channel() {
        return channel;
    }

    /**
     * Set idle state trigger
     *
     * @param idleStateHandler the idle state handler
     */
    void setIdleTrigger(IdleStateHandler idleStateHandler) {
        idleStateHandlerTrigger = new IdleStateHandlerTrigger(idleStateHandler, this);
    }

    /**
     * Schedule a task to run after delay
     *
     * @param runnable the task to run
     * @param delay    the delay time
     * @param timeUnit the time unit
     * @return scheduled future
     */
    public final ScheduledFuture<?> schedule(Runnable runnable, long delay, TimeUnit timeUnit) {
        return worker.getScheduledExecutorService().schedule(runnable, delay, timeUnit);
    }

    /**
     * Schedule a task to run after delay in milliseconds
     *
     * @param runnable the task to run
     * @param delayMs  the delay time in milliseconds
     * @return scheduled future
     */
    public final ScheduledFuture<?> schedule(Runnable runnable, long delayMs) {
        return schedule(runnable, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Run a task asynchronously when currently on the worker thread.
     *
     * <p>When called on a worker thread, the task is submitted to a background
     * executor so the worker can continue processing other connections.
     * When called from a non-worker thread (e.g. already async), the task runs
     * directly.</p>
     *
     * @param runnable the task to run
     */
    public void runAsync(Runnable runnable) {
        worker.runAsync(runnable);
    }

    /**
     * Get channel context ID
     *
     * @return the context ID
     */
    public long getId() {
        return id;
    }

    /**
     * Get channel context ID
     *
     * @return the context ID
     */
    public String contextId() {
        return Utils.toHexString16(id);
    }

    /**
     * Set channel handler
     *
     * @param channelHandler the channel handler
     */
    @SuppressWarnings("unchecked")
    public void setChannelHandler(ChannelHandler<?> channelHandler) {
        this.channelHandler = (ChannelHandler<Object>) channelHandler;
    }

    /**
     * Set channel reader
     *
     * @param channelReader the channel reader
     */
    void setChannelReader(ChannelReader<?> channelReader) {
        this.channelReader = channelReader;
    }

    /**
     * Get channel reader
     *
     * @return the channel reader
     */
    public ChannelReader<?> reader() {
        return channelReader;
    }

    /**
     * Set channel writer for encoding outbound messages.
     */
    void setChannelWriter(ChannelWriter<?> channelWriter) {
        this.channelWriter = channelWriter;
    }

    /**
     * Send a message using the configured codec (encode + flush).
     * <p>
     * Use this to send application-level messages — the codec automatically
     * wraps them into framed bytes. For raw bytes, use {@link #write(byte[])}.
     *
     * @throws IOException if no writer is configured or write fails
     */
    public void send(Object message) throws IOException {
        if (channelWriter == null) throw new IOException("codec not configured, use write(byte[]) for raw bytes");
        ByteBuffer buf = ((ChannelWriter<Object>) channelWriter).write(this, message);
        if (buf != null) {
            writeFlush(buf);
        }
    }

    /**
     * Set read selection key
     *
     * @param readKey the selection key
     */
    public void setReadKey(SelectionKey readKey) {
        this.readKey = readKey;
    }

    /**
     * Get attachment object
     *
     * @return the attachment
     */
    public Object attachment() {
        return attachment;
    }

    /**
     * Set attachment object
     *
     * @param attachment the attachment to set
     */
    public void attachment(Object attachment) {
        this.attachment = attachment;
    }

    /**
     * Returns the binding object attached to this context.
     * <p>
     * The binding is an immutable once-set attachment used for protocol upgrade
     * (e.g., WebSocket upgrade holder). Once set, it cannot be changed.
     *
     * @return the binding object, or null if not set
     */
    public Object binding() {
        return binding;
    }

    /**
     * Attaches a binding object to this context.
     * <p>
     * The binding is immutable once set — subsequent calls will throw
     * {@link IllegalStateException}. Used for protocol upgrade scenarios
     * such as WebSocket and h2c upgrade holders.
     *
     * @param binding the binding object to attach
     * @throws IllegalStateException if binding is already set
     */
    public void binding(Object binding) {
        if (this.binding != null) {
            throw new IllegalStateException("Binding is immutable once set");
        }
        this.binding = binding;
    }

    /**
     * Get attribute by key
     *
     * @param key the attribute key
     * @return the attribute value
     */
    public Object getAttribute(String key) {
        Map<String, Object> attributes = getAttributes();
        return attributes.get(key);
    }

    /**
     * Set attribute by key
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void setAttribute(String key, Object value) {
        Map<String, Object> attributes = getAttributes();
        attributes.put(key, value);
    }

    /**
     * Remove attribute by key
     *
     * @param key the attribute key
     */
    public void removeAttribute(String key) {
        Map<String, Object> attributes = getAttributes();
        attributes.remove(key);
    }

    /**
     * Get all attribute names
     *
     * @return an Enumeration of attribute names
     */
    public Enumeration<String> getAttributeNames() {
        Map<String, Object> attributes = getAttributes();
        return Collections.enumeration(attributes.keySet());
    }

    /**
     * Get attributes map
     *
     * @return the attributes map
     */
    Map<String, Object> getAttributes() {
        if (attributes == null) {
            attributes = new ConcurrentHashMap<>();
        }
        return attributes;
    }

    /**
     * Get handshaked application protocol
     *
     * @return the application protocol, null if not available
     */
    public String getHandShakedApplicationProtocol() {
        return null;
    }

    /**
     * Check if the channel has the specified application protocol
     *
     * @param protocol the protocol to check
     * @return true if the protocol is supported, false otherwise
     */
    public final boolean hasApplicationProtocol(String protocol) {
        String[] protocols;
        if(nioConfig != null && (protocols = nioConfig.getApplicationProtocols()) != null) {
            for (String p : protocols) {
                if(p != null && p.equals(protocol)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get remote client address
     *
     * @return remote InetSocketAddress, null if unavailable
     */
    public InetSocketAddress getRemoteAddress() {
        // Double-checked locking optimization: check volatile variable first, then synchronize initialization
        if (!addressCacheInitialized) {
            synchronized (this) {
                if (!addressCacheInitialized) {
                    initializeAddressCache();
                }
            }
        }
        return cachedRemoteAddress;
    }

    /**
     * Get local server address
     *
     * @return local InetSocketAddress, null if unavailable
     */
    public InetSocketAddress getLocalAddress() {
        // Double-checked locking optimization: check volatile variable first, then synchronize initialization
        if (!addressCacheInitialized) {
            synchronized (this) {
                if (!addressCacheInitialized) {
                    initializeAddressCache();
                }
            }
        }
        return cachedLocalAddress;
    }

    /**
     * Initialize address cache
     * Get and cache remote and local addresses once at first call
     */
    private void initializeAddressCache() {
        // No need to synchronize again since outer layer already has synchronization protection
        if (addressCacheInitialized) return;

        try {
            SocketAddress remoteAddr = channel.getRemoteAddress();
            SocketAddress localAddr = channel.getLocalAddress();

            cachedRemoteAddress = remoteAddr instanceof InetSocketAddress ?
                    (InetSocketAddress) remoteAddr : null;
            cachedLocalAddress = localAddr instanceof InetSocketAddress ?
                    (InetSocketAddress) localAddr : null;

            addressCacheInitialized = true;
        } catch (Exception e) {
            // Keep cache as null when exception occurs
            cachedRemoteAddress = null;
            cachedLocalAddress = null;
            addressCacheInitialized = true;
        }
    }

    void setWorker(ChannelWorker worker) {
        this.worker = worker;
    }

    void setNioConfig(NioConfig nioConfig) {
        this.nioConfig = nioConfig;
        refreshTimeoutOptions();
    }

    /** Binds the NioConfig only when none is set yet; later calls are ignored so a context's config stays immutable. */
    public void attachNioConfig(NioConfig nioConfig) {
        if (this.nioConfig == null) {
            this.nioConfig = nioConfig;
            refreshTimeoutOptions();
        }
    }

    /**
     * Resolves a per-server configuration option via the {@link NioConfig} bound to this context.
     *
     * @param option the option to read (e.g. {@code HttpOptions.GZIP})
     * @param <T>    value type
     * @return the instance override if present, otherwise {@code option.defaultValue}
     */
    public final <T> T option(Option<T> option) {
        if (nioConfig == null) {
            return option.value;
        }
        return nioConfig.option(option);
    }

    /**
     * Fire an abuse event for the current connection. If the configured
     * {@link ConnectionFilter} implements {@link AbuseHandler}, its
     * {@link AbuseHandler#onAbuse} is invoked; otherwise the event is ignored.
     * Runs on the I/O worker thread — receivers must stay non-blocking and cheap.
     *
     * @param abuseType a short identifier for the abuse, e.g. {@code "RST_FLOOD"}
     */
    public void fireAbuse(String abuseType) {
        ConnectionFilter connectionFilter = nioConfig.getConnectionFilter();
        if (connectionFilter instanceof AbuseHandler) {
            ((AbuseHandler) connectionFilter).onAbuse(this, abuseType);
        }
    }

    /**
     * Entry point to trigger handler execution.
     * Typically called by decoders to pass decoded messages to the handler.
     *
     * @param message the decoded message object
     * @throws IOException if handler execution fails
     */
    public final <T> void invokeHandle(T message) throws IOException {
        channelHandler.onHandle(this, message);
    }
}