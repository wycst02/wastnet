package io.github.wycst.wastnet.util;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Direct ByteBuffer Memory Pool
 *
 * <p>Design specs:
 * <ul>
 *   <li>Pool size: 2048 x 8KB = 16MB total (direct memory)</li>
 *   <li>All buffers pre-allocated at init</li>
 *   <li>Thread-affinity: a thread claims the slot at (threadId &amp; MASK); on
 *       collision it falls back to a heap buffer</li>
 *   <li>Fallback: allocate heap buffer when the affinity slot is busy</li>
 * </ul>
 *
 * <p>Thread-safety: each {@link PooledBuffer} owns its occupancy state
 * ({@link AtomicInteger}). {@code acquireBuffer()} picks a slot by
 * {@code Thread.currentThread().getId() &amp; MASK} and CAS-es that buffer's
 * state from FREE to INUSE; only the CAS winner ever holds the buffer, so the
 * same buffer can never be owned by two threads (no double hand-out).
 * {@code release} clears the buffer while still exclusively owned and then sets
 * the state back to FREE. This is lock-free and allocation-free in the steady
 * state, with no single monitor and no per-operation GC pressure.
 *
 * <p>Earlier lock-free array/swap designs coupled the size counter with the
 * slot allocator: {@code release()} decremented the counter (logically freeing
 * the last slot) <i>before</i> relocating the still-in-use "last" buffer out of
 * it, creating a double-hand-out window. The thread-affinity design removes that
 * window entirely.
 *
 * @author wangyc
 */
public final class DirectBufferPool {

    private DirectBufferPool() {}

    /**
     * Pool size limit. Must be a power of two so that {@code & MASK} maps a
     * thread id uniformly onto a slot. 2048 x 8KB = 16MB of direct memory.
     */
    private static final int POOL_SIZE = 2048;

    /**
     * Bit mask for slot indexing ({@code POOL_SIZE - 1}).
     */
    private static final int MASK = POOL_SIZE - 1;

    /**
     * Buffer size
     */
    private static final int BUFFER_SIZE = 8192;

    /**
     * Pooled buffers, one per slot.
     */
    private static final PooledBuffer[] POOL = new PooledBuffer[POOL_SIZE];

    static {
        for (int i = 0; i < POOL_SIZE; ++i) {
            POOL[i] = new PooledBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
        }
    }

    /**
     * Pooled Buffer Wrapper. Each instance carries its own occupancy state so a
     * thread claims it via a single CAS on {@link #state}.
     */
    public static final class PooledBuffer {
        static final int FREE = 0;
        static final int INUSE = 1;

        private final ByteBuffer buffer;
        private final AtomicInteger state = new AtomicInteger(FREE);

        PooledBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        public ByteBuffer get() {
            return buffer;
        }

        /**
         * Release this buffer back to pool
         */
        public void release() {
            DirectBufferPool.release(this);
        }
    }

    /**
     * Acquire a pooled buffer (lock-free, thread-affinity).
     *
     * <p>A thread targets the slot {@code threadId & MASK}. If that slot's
     * buffer is free it is claimed via CAS; otherwise this thread falls back to
     * a freshly allocated heap buffer (discarded on release).
     */
    public static PooledBuffer acquireBuffer() {
        int i = (int) (Thread.currentThread().getId() & MASK);
        PooledBuffer pb = POOL[i];
        if (pb.state.compareAndSet(PooledBuffer.FREE, PooledBuffer.INUSE)) {
            return pb;
        }
        return new PooledBuffer(ByteBuffer.allocate(BUFFER_SIZE));
    }

    /**
     * Release a buffer back to the pool. Direct (pooled) buffers are cleared
     * while still exclusively owned by the caller and then marked FREE. Heap
     * fallback buffers are discarded (they were never part of the pool).
     */
    static void release(PooledBuffer pb) {
        if (!pb.buffer.isDirect()) {
            return;
        }
        pb.buffer.clear();
        pb.state.set(PooledBuffer.FREE);
    }
}
