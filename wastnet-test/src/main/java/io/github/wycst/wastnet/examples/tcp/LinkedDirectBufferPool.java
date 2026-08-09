package io.github.wycst.wastnet.examples.tcp;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Traditional memory pool based on lock-free stack (Treiber stack)
 *
 * <p>Design:
 * <ul>
 *   <li>Pre-allocate all DirectBuffers at init</li>
 *   <li>Use AtomicReference to implement lock-free stack</li>
 *   <li>acquire: pop from stack, release: push onto stack</li>
 *   <li>Fallback: allocate heap buffer when stack is empty</li>
 * </ul>
 *
 * @author wyc
 */
public final class LinkedDirectBufferPool {

    /** Pool size limit (8KB buffer count) */
    private static final int POOL_SIZE = 1024;

    /** Buffer size */
    private static final int BUFFER_SIZE = 8192;

    /** Total memory limit: 8MB */
    public static final long MEMORY_LIMIT = (long) POOL_SIZE * BUFFER_SIZE;

    /** Node for stack */
    private static final class Node {
        final PooledBuffer buffer;
        final Node next;
        Node(PooledBuffer buffer, Node next) {
            this.buffer = buffer;
            this.next = next;
        }
    }

    /** Pooled Buffer */
    public static final class PooledBuffer {
        private final ByteBuffer buffer;
        PooledBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
        }
        public ByteBuffer getBuffer() {
            return buffer;
        }
        public boolean isPooled() {
            return buffer.isDirect();
        }
    }

    /** Lock-free stack top */
    private static final AtomicReference<Node> stack = new AtomicReference<Node>();

    static {
        Node head = null;
        for (int i = 0; i < POOL_SIZE; ++i) {
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            PooledBuffer pb = new PooledBuffer(buf);
            head = new Node(pb, head);
        }
        stack.set(head);
    }

    /**
     * Acquire buffer from pool (lock-free stack pop)
     */
    public static PooledBuffer acquire() {
        while (true) {
            Node cur = stack.get();
            if (cur == null) {
                return new PooledBuffer(ByteBuffer.allocate(BUFFER_SIZE));
            }
            Node next = cur.next;
            if (stack.compareAndSet(cur, next)) {
                cur.buffer.getBuffer().clear();
                return cur.buffer;
            }
        }
    }

    /**
     * Release buffer back to pool (lock-free stack push)
     */
    public static void release(PooledBuffer pb) {
        if (!pb.isPooled()) {
            return;
        }
        while (true) {
            Node cur = stack.get();
            Node newHead = new Node(pb, cur);
            if (stack.compareAndSet(cur, newHead)) {
                return;
            }
        }
    }

    public static int getAvailable() {
        int count = 0;
        Node cur = stack.get();
        while (cur != null) {
            count++;
            cur = cur.next;
        }
        return count;
    }
}
