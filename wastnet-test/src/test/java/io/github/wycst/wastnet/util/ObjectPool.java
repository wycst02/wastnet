package io.github.wycst.wastnet.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generic Object Pool with pre-creation support.
 *
 * @param <T> Pooled object type
 * @author wangyc
 */
public abstract class ObjectPool<T> {

    /**
     * Pooled object wrapper with release capability.
     *
     * @param <T> Object type
     */
    public static final class PooledObject<T> {
        T target;
        private int index;
        private final ObjectPool<T> pool;

        PooledObject(T target, int index, ObjectPool<T> pool) {
            this.target = target;
            this.index = index;
            this.pool = pool;
        }

        public T get() {
            return target;
        }

        /**
         * Release this object back to pool.
         */
        public void release() {
            pool.release(this);
        }
    }

    /** Pool array */
    protected final PooledObject<T>[] pool;

    /** Pool size limit */
    protected final int poolSize;

    /** Timeout in seconds */
    protected final long timeout;

    /** Allocated count */
    final AtomicInteger count = new AtomicInteger(0);

    protected ObjectPool(int poolSize, int initialCapacity) {
        this(poolSize, initialCapacity, 30000L);
    }

    @SuppressWarnings("unchecked")
    protected ObjectPool(int poolSize, int initialCapacity, long timeout) {
        this.poolSize = poolSize;
        this.timeout = timeout;
        this.pool = new PooledObject[poolSize];
        for (int i = 0; i < initialCapacity; ++i) {
            pool[i] = new PooledObject<T>(createObject(), i, this);
        }
    }

    /**
     * Acquire object from pool.
     */
    public PooledObject<T> acquire() {
        int c = count.getAndIncrement();
        if (c >= poolSize) {
            count.decrementAndGet();
            if(supportedExternal()) {
                T externalObject = createExternalObject();
                if(externalObject == null) {
                    return null;
                }
                return new PooledObject<T>(externalObject, -1, this);
            }
            if(timeout <= 0) {
                return null;
            }
            long begin = System.currentTimeMillis();
            while ((c = count.getAndIncrement()) >= poolSize) {
                count.decrementAndGet();
                Thread.yield();
                if(System.currentTimeMillis() - begin > timeout) {
                    return null;
                }
            }
        }
        PooledObject<T> pooledObj = pool[c];
        T target = pooledObj.target;
        if(target == null || !validate(target)) {
            if(target != null) {
                destroyObject(target);
            }
            pooledObj.target = createObject();
        }
        return pooledObj;
    }

    /**
     * Release object back to pool (lock-free).
     */
    protected void release(PooledObject<T> pooledObj) {
        if (pooledObj.index == -1) {
            resetAndDestroyObject(pooledObj.target);
            return;
        }
        try {
            while (true) {
                int c = count.get(), dest = c - 1;
                if(count.compareAndSet(c, dest)) {
                    int i = pooledObj.index;
                    if (i == dest) {
                        return;
                    }
                    PooledObject<T> destObj = pool[dest];
                    pooledObj.index = dest;
                    destObj.index = i;

                    pool[dest] = pooledObj;
                    pool[i] = destObj;
                    return;
                }
            }
        } finally {
            reset(pooledObj.target);
        }
    }

    // --- Abstract methods ---

    /**
     * Create object by index.
     * Called during pool pre-creation or when pooled object is invalid.
     *
     * @return created object
     */
    protected abstract T createObject();

    /**
     * Whether external object is supported.
     *
     * @return true if supported
     */
    protected boolean supportedExternal() {
        return false;
    }

    /**
     * Create object when pool is exhausted.
     * Called when acquiring object but pool is full.
     *
     * @return created external object
     */
    protected abstract T createExternalObject();

    /**
     * Reset object state before reuse.
     * Called when object is released back to pool.
     *
     * @param obj the object to reset
     */
    protected abstract void reset(T obj);

    /**
     * Validate object is still usable.
     * Called when acquiring object from pool.
     *
     * @param obj the object to validate
     * @return true if valid, false to recreate
     */
    protected boolean validate(T obj) {
        return true;
    }

    /**
     * Destroy invalid object.
     * Called when object validation fails and needs to be replaced.
     *
     * @param obj the object to destroy
     */
    protected void destroyObject(T obj) {
    }

    /**
     * Reset and destroy object.
     * Called when object is released back to pool.
     *
     * @param obj the object to reset and destroy
     */
    protected final void resetAndDestroyObject(T obj) {
        reset(obj);
        destroyObject(obj);
    }
}
