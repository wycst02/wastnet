package io.github.wycst.wastnet.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency stress test for {@link DirectBufferPool}.
 *
 * <p>The exact same stress harness ({@link #runStress}) is applied to two pools:
 * <ol>
 *   <li>the real {@link DirectBufferPool} (expected to expose the race), and</li>
 *   <li>a functionally-equivalent CORRECT pool built on {@link ConcurrentLinkedQueue}
 *       (control group — must pass, proving the harness itself is not a false-positive generator).</li>
 * </ol>
 *
 * <p>The harness only uses each pool's public acquire/release semantics; it does NOT
 * modify {@link DirectBufferPool}.
 */
public class DirectBufferPoolConcurrencyTest {

    private static final int POOL_SIZE = 1024;
    private static final int BUFFER_SIZE = 8192;

    /**
     * Result counters collected during a stress run.
     */
    static final class Result {
        final AtomicInteger doubleHandOut = new AtomicInteger(0);
        final AtomicInteger contentCorruption = new AtomicInteger(0);
        final AtomicInteger releaseExceptions = new AtomicInteger(0);
        final AtomicInteger acquireExceptions = new AtomicInteger(0);
        final AtomicLong totalOps = new AtomicLong(0);
        // One captured sample proving two LIVE holders share the same buffer.
        final java.util.concurrent.atomic.AtomicReference<String> corruptionSample =
                new java.util.concurrent.atomic.AtomicReference<String>(null);
        final java.util.concurrent.atomic.AtomicReference<String> doubleSample =
                new java.util.concurrent.atomic.AtomicReference<String>(null);
    }

    /**
     * Adapter that hides the concrete pool behind a uniform acquire/release contract.
     * The returned token's object identity is what the harness tracks.
     */
    interface PoolAdapter {
        Object acquire();

        ByteBuffer buffer(Object token);

        boolean pooled(Object token);

        void release(Object token);
    }

    // ------------------------------------------------------------------
    // Test 1: the real pool under scrutiny
    // ------------------------------------------------------------------
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void directBufferPool_keepsInvariantUnderContention() throws InterruptedException {
        PoolAdapter adapter = new PoolAdapter() {
            @Override
            public Object acquire() {
                return DirectBufferPool.acquireBuffer();
            }

            @Override
            public ByteBuffer buffer(Object token) {
                return ((DirectBufferPool.PooledBuffer) token).get();
            }

            @Override
            public boolean pooled(Object token) {
                // pooled buffers are the pre-allocated direct ones; heap fallback is not pooled
                return ((DirectBufferPool.PooledBuffer) token).get().isDirect();
            }

            @Override
            public void release(Object token) {
                ((DirectBufferPool.PooledBuffer) token).release();
            }
        };

        Result r = runStress("DirectBufferPool", adapter);
        assertClean(r);
    }

    // ------------------------------------------------------------------
    // Test 2: CONTROL — a correct pool with identical semantics.
    // Same harness must report zero problems, proving the harness is sound.
    // ------------------------------------------------------------------
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    public void controlCorrectPool_passesSameHarness() throws InterruptedException {
        final CorrectPool correct = new CorrectPool();
        PoolAdapter adapter = new PoolAdapter() {
            @Override
            public Object acquire() {
                return correct.acquire();
            }

            @Override
            public ByteBuffer buffer(Object token) {
                return ((CorrectPool.Slot) token).buffer;
            }

            @Override
            public boolean pooled(Object token) {
                return ((CorrectPool.Slot) token).pooled;
            }

            @Override
            public void release(Object token) {
                correct.release((CorrectPool.Slot) token);
            }
        };

        Result r = runStress("CorrectPool(ConcurrentLinkedQueue)", adapter);
        assertClean(r);
    }

    // ------------------------------------------------------------------
    // Shared stress harness
    // ------------------------------------------------------------------
    private Result runStress(final String label, final PoolAdapter pool) throws InterruptedException {
        final int threads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
        final int iterationsPerThread = 200_000;

        // Tracks currently-held pooled tokens -> holding thread id.
        // If acquire returns a token already present here, the pool double-handed it out.
        final Map<Object, Long> inUse = new ConcurrentHashMap<Object, Long>();
        final Result result = new Result();

        final CountDownLatch start = new CountDownLatch(1);
        final Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final long threadId = t + 1;
            final byte signature = (byte) (t + 1);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterationsPerThread; ++i) {
                        Object token;
                        try {
                            token = pool.acquire();
                        } catch (Throwable ex) {
                            result.acquireExceptions.incrementAndGet();
                            continue;
                        }
                        boolean pooled = pool.pooled(token);
                        if (pooled) {
                            Long prev = inUse.put(token, threadId);
                            if (prev != null) {
                                // Another thread currently holds the exact same pooled token
                                // (it registered it and has NOT removed it yet -> still live).
                                result.doubleHandOut.incrementAndGet();
                                result.doubleSample.compareAndSet(null,
                                        "thread " + threadId + " acquired a buffer still held by live thread " + prev);
                            }
                        }

                        // Write a thread-unique signature, spin briefly, then verify no one clobbered it.
                        ByteBuffer buf = pool.buffer(token);
                        buf.clear();
                        buf.put(0, signature);
                        for (int s = 0; s < 8; s++) {
                            byte seen = buf.get(0);
                            if (seen != signature) {
                                result.contentCorruption.incrementAndGet();
                                result.corruptionSample.compareAndSet(null,
                                        "thread " + threadId + " wrote signature " + signature
                                                + " but then read " + seen
                                                + " (another LIVE thread's signature) from the SAME buffer");
                                break;
                            }
                        }

                        if (pooled) {
                            inUse.remove(token, threadId);
                        }
                        try {
                            pool.release(token);
                        } catch (Throwable ex) {
                            result.releaseExceptions.incrementAndGet();
                        }
                        result.totalOps.incrementAndGet();
                    }
                }
            }, "pool-worker-" + threadId);
            workers[t] = worker;
            worker.start();
        }

        start.countDown();
        for (Thread th : workers) {
            th.join(TimeUnit.SECONDS.toMillis(55));
        }

        System.out.println("[" + label + " stress] threads=" + threads
                + " opsPerThread=" + iterationsPerThread
                + " totalOps=" + result.totalOps.get()
                + " doubleHandOut=" + result.doubleHandOut.get()
                + " contentCorruption=" + result.contentCorruption.get()
                + " releaseExceptions=" + result.releaseExceptions.get()
                + " acquireExceptions=" + result.acquireExceptions.get());
        if (result.doubleSample.get() != null) {
            System.out.println("[" + label + " sample] " + result.doubleSample.get());
        }
        if (result.corruptionSample.get() != null) {
            System.out.println("[" + label + " sample] " + result.corruptionSample.get());
        }
        return result;
    }

    private static void assertClean(Result r) {
        Assertions.assertEquals(0, r.doubleHandOut.get(),
                "Same pooled buffer was handed out to two threads simultaneously");
        Assertions.assertEquals(0, r.releaseExceptions.get(),
                "release() threw (e.g. count underflow -> ArrayIndexOutOfBoundsException)");
        Assertions.assertEquals(0, r.contentCorruption.get(),
                "Buffer content was clobbered by another concurrent holder");
        Assertions.assertEquals(0, r.acquireExceptions.get(),
                "acquire() threw under contention");
    }

    // ------------------------------------------------------------------
    // A minimal, obviously-correct pool with the same purpose as DirectBufferPool:
    // pre-allocate POOL_SIZE direct buffers, hand them out via a lock-free queue,
    // fall back to a heap buffer when empty. No shared mutable array indices.
    // ------------------------------------------------------------------
    static final class CorrectPool {
        static final class Slot {
            final ByteBuffer buffer;
            final boolean pooled;

            Slot(ByteBuffer buffer, boolean pooled) {
                this.buffer = buffer;
                this.pooled = pooled;
            }
        }

        private final Queue<Slot> free = new ConcurrentLinkedQueue<Slot>();

        CorrectPool() {
            for (int i = 0; i < POOL_SIZE; ++i) {
                free.offer(new Slot(ByteBuffer.allocateDirect(BUFFER_SIZE), true));
            }
        }

        Slot acquire() {
            Slot s = free.poll();
            if (s != null) {
                return s;
            }
            return new Slot(ByteBuffer.allocate(BUFFER_SIZE), false);
        }

        void release(Slot s) {
            s.buffer.clear();
            if (s.pooled) {
                free.offer(s);
            }
        }
    }
}
