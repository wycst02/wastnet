package io.github.wycst.wastnet.examples.http;

/**
 * 令牌桶算法实现
 *
 * <p>原理：
 * <ul>
 *   <li>桶中持有令牌，每次请求消耗令牌</li>
 *   <li>令牌以固定速率匀速补充，补充到容量上限为止</li>
 *   <li>桶空时拒绝请求</li>
 *   <li>允许突发：桶满时可一次性消耗所有令牌</li>
 * </ul>
 *
 * <p>示例：
 * <pre>
 *   TokenBucket bucket = new TokenBucket(100, 50); // 容量100，每秒补充50个
 *
 *   // 在请求处理中使用
 *   if (!bucket.tryAcquire()) {
 *       response.status(429).body("Too Many Requests");
 *       return;
 *   }
 *   // 正常处理
 * </pre>
 *
 * @since 2026-5-11
 * @author wangyc
 */
public class TokenBucket {

    /** 桶容量（最大令牌数） */
    private final long capacity;

    /** 每秒补充令牌数 */
    private final double refillRate;

    /** 当前令牌数 */
    private double tokens;

    /** 上次补充时间（纳秒） */
    private long lastRefillTime;

    public TokenBucket(long capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be positive: " + refillRate);
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.tokens = capacity; // 初始满桶
        this.lastRefillTime = System.nanoTime();
    }

    /**
     * 尝试获取1个令牌
     *
     * @return true 获取成功，false 被限流
     */
    public synchronized boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * 尝试获取指定数量令牌
     *
     * @param requested 请求令牌数
     * @return true 获取成功，false 被限流
     */
    public synchronized boolean tryAcquire(int requested) {
        if (requested <= 0) {
            throw new IllegalArgumentException("requested must be positive: " + requested);
        }
        refill();
        if (tokens >= requested) {
            tokens -= requested;
            return true;
        }
        return false;
    }

    /**
     * 补充令牌（基于时间差计算应补充数量）
     */
    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTime) / 1_000_000_000.0;
        if (elapsed > 0) {
            double newTokens = elapsed * refillRate;
            tokens = Math.min(capacity, tokens + newTokens);
            lastRefillTime = now;
        }
    }

    /**
     * 获取当前可用令牌数
     *
     * @return 当前令牌数
     */
    public synchronized double getTokens() {
        refill();
        return tokens;
    }

    /**
     * 获取桶容量
     *
     * @return 桶容量
     */
    public long getCapacity() {
        return capacity;
    }

    /**
     * 获取补充速率
     *
     * @return 每秒补充令牌数
     */
    public double getRefillRate() {
        return refillRate;
    }
}
