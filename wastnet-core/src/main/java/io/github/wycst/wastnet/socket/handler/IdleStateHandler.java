package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.concurrent.TimeUnit;

/**
 * Global singleton
 */
public abstract class IdleStateHandler {

    public enum IdleType {
        Read, Write
    }

    public enum Mode {
        /**
         * Per-connection recursive scheduling, nanosecond precision.
         * Idle detection of each connection is independent — one slow callback
         * does not delay others. Suitable for most scenarios up to ~50K
         * connections (default).
         */
        EXCLUSIVE,
        /**
         * Worker-level periodic scan (~1s precision, scalable).
         * Not global — each Worker runs its own scan. Suitable for
         * large-scale connections (50K+) where ~1s precision is acceptable.
         */
        SHARED
    }

    private final long readerIdleTimeNanos;
    private final long writerIdleTimeNanos;
    private final Mode mode;

    public IdleStateHandler(long readerIdleTime, long writerIdleTime, TimeUnit unit) {
        this(readerIdleTime, writerIdleTime, unit, Mode.EXCLUSIVE);
    }

    public IdleStateHandler(long readerIdleTime, long writerIdleTime, TimeUnit unit, Mode mode) {
        this.readerIdleTimeNanos = readerIdleTime > 0 ? unit.toNanos(readerIdleTime) : 0L;
        this.writerIdleTimeNanos = writerIdleTime > 0 ? unit.toNanos(writerIdleTime) : 0L;
        this.mode = mode;
    }

    public long getReaderIdleTimeNanos() {
        return readerIdleTimeNanos;
    }

    public long getWriterIdleTimeNanos() {
        return writerIdleTimeNanos;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Triggered when a channel has been idle for the configured time.
     *
     * <p><b>Threading note:</b> This method runs on the worker's single-thread
     * scheduled executor. Implementations must be lightweight (e.g. send a
     * heartbeat, close the channel, or update a counter). Heavy work such as
     * database queries or blocking HTTP calls will stall idle detection for
     * all connections bound to the same worker. Offload heavy operations to
     * a dedicated business thread pool if necessary.
     *
     * @param ctx                     the channel context
     * @param idleType                Read or Write idle type
     * @param triggerTotalCount       total number of idle triggers so far
     * @param triggerConsecutiveCount consecutive idle triggers since last activity
     * @throws Throwable if an error occurs during handling
     */
    public abstract void onIdleTriggered(ChannelContext ctx, IdleType idleType, long triggerTotalCount, long triggerConsecutiveCount) throws Throwable;

}
