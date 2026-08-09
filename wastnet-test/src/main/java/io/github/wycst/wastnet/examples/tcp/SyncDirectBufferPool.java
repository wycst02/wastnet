package io.github.wycst.wastnet.examples.tcp;

import java.nio.ByteBuffer;

/**
 * 基于synchronized的Direct ByteBuffer内存池
 * 
 * 设计思路：
 * - 预分配所有DirectBuffer，避免运行时系统调用
 * - 使用synchronized保证线程安全
 * - count标记已分配数量，POOL[0..count-1]为占用态
 */
public final class SyncDirectBufferPool {

    private static final int POOL_SIZE = 1024;
    private static final int BUFFER_SIZE = 8192;
    
    public static final long MEMORY_LIMIT = (long) POOL_SIZE * BUFFER_SIZE;
    
    private static final PooledBuffer[] POOL = new PooledBuffer[POOL_SIZE];
    private static int count = 0;
    private static final Object lock = new Object();
    
    static {
        for (int i = 0; i < POOL_SIZE; ++i) {
            POOL[i] = new PooledBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE), i);
        }
    }
    
    public static final class PooledBuffer {
        private final ByteBuffer buffer;
        private int index;
        
        PooledBuffer(ByteBuffer buffer, int index) {
            this.buffer = buffer;
            this.index = index;
        }
        
        public ByteBuffer getBuffer() {
            return buffer;
        }
        
        public boolean isPooled() {
            return buffer.isDirect();
        }
    }
    
    /**
     * 获取buffer
     */
    public static PooledBuffer acquire() {
        synchronized (lock) {
            if (count < POOL_SIZE) {
                PooledBuffer pb = POOL[count++];
                pb.buffer.clear();
                return pb;
            }
        }
        // 池耗尽，降级为堆内存
        return new PooledBuffer(ByteBuffer.allocate(BUFFER_SIZE), -1);
    }
    
    /**
     * 归还buffer
     */
    public static void release(PooledBuffer pb) {
        if (!pb.isPooled()) {
            return;
        }
        
        synchronized (lock) {
            int i = pb.index;
            int dest = count - 1;
            
            if (i == dest) {
                count--;
                return;
            }
            
            // 交换位置
            PooledBuffer destPb = POOL[dest];
            pb.index = dest;
            destPb.index = i;
            POOL[dest] = pb;
            POOL[i] = destPb;
            count--;
        }
    }
    
    public static int getAvailable() {
        synchronized (lock) {
            return POOL_SIZE - count;
        }
    }
    
    public static int getAllocatedCount() {
        synchronized (lock) {
            return count;
        }
    }
}
