package io.github.wycst.wastnet.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Unit tests for {@link DirectBufferPool}: acquire/release/get happy paths and
 * the heap-fallback branch (a single thread always maps to the same slot, so a
 * second acquire without releasing the first forces the CAS to fail).
 */
public class DirectBufferPoolTest {

    @Test
    public void testAcquireReturnsDirectBuffer() {
        DirectBufferPool.PooledBuffer pb = DirectBufferPool.acquireBuffer();
        try {
            Assertions.assertTrue(pb.get().isDirect());
            Assertions.assertEquals(8192, pb.get().capacity());
        } finally {
            pb.release();
        }
    }

    @Test
    public void testAcquireFallsBackToHeapOnBusySlot() {
        DirectBufferPool.PooledBuffer first = DirectBufferPool.acquireBuffer();
        try {
            // same thread -> same slot; CAS fails -> heap fallback
            DirectBufferPool.PooledBuffer second = DirectBufferPool.acquireBuffer();
            try {
                Assertions.assertTrue(first.get().isDirect());
                Assertions.assertFalse(second.get().isDirect());
            } finally {
                second.release(); // heap buffer: no-op
            }
        } finally {
            first.release();
        }
    }

    @Test
    public void testReleaseReturnsBufferToPool() {
        DirectBufferPool.PooledBuffer first = DirectBufferPool.acquireBuffer();
        // occupy the slot
        DirectBufferPool.PooledBuffer fallback = DirectBufferPool.acquireBuffer();
        try {
            Assertions.assertFalse(fallback.get().isDirect());
        } finally {
            fallback.release();
        }
        // release the pooled one -> slot free again
        first.release();
        // a fresh acquire on the same thread yields a direct (pooled) buffer again
        DirectBufferPool.PooledBuffer again = DirectBufferPool.acquireBuffer();
        try {
            Assertions.assertTrue(again.get().isDirect());
        } finally {
            again.release();
        }
    }

    @Test
    public void testReleaseHeapBufferIsNoOp() {
        DirectBufferPool.PooledBuffer first = DirectBufferPool.acquireBuffer();
        try {
            DirectBufferPool.PooledBuffer heap = DirectBufferPool.acquireBuffer();
            // must not throw on a heap (non-direct) buffer
            heap.release();
            Assertions.assertFalse(heap.get().isDirect());
        } finally {
            first.release();
        }
    }

    @Test
    public void testPooledBufferGetReturnsBackingBuffer() {
        DirectBufferPool.PooledBuffer pb = DirectBufferPool.acquireBuffer();
        try {
            ByteBuffer buf = pb.get();
            Assertions.assertNotNull(buf);
            Assertions.assertSame(buf, pb.get());
        } finally {
            pb.release();
        }
    }
}
