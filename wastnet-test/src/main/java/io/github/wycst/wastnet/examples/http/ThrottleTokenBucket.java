package io.github.wycst.wastnet.examples.http;

/**
 * 预消费节流令牌桶简单实现
 *
 * <p> 确保持续时间{duration}内最大令牌数量{maxCount}（并发数）
 * <p> 当duration = 1时qps限流
 * <p> 使用标记方法实现
 *
 * @author wangy
 * @since 2021-8-8
 * @Description:
 */
public class ThrottleTokenBucket {

    /**
     * 持续时间
     */
    private final long duration;

    /**
     * 最大令牌数量
     */
    private final int maxCount;

    /**
     * <p> 拒绝策略 1 = reject；
     * <p> 等待策略2 = wait（等待直到获取令牌为止）
     * */
    private int strategy = 1;

    /**
     *
     * 最大等待毫秒数
     *
     * */
    private long maxWait = 30;

    /** 例外处理器 */
    private String exceptionHandler;

    /** 令牌对象 */
    private class Token {
        /** 令牌编号 */
        private final long no;
        /** 生效时间 */
        private long millis;
        public Token(long no) {
            this.no = no;
        }
    }

    // 数组访问性能高于ArrayQuene
    private final Token[] tokenQueues;
    // 当前位置
    private int currentIndex = 0;

    public ThrottleTokenBucket(long duration, int maxCount) {
        this(duration, maxCount, 1);
    }

    public ThrottleTokenBucket(long duration, int maxCount, int strategy) {
        this(duration, maxCount, strategy, 30);
    }

    public ThrottleTokenBucket(long duration, int maxCount, int strategy, long maxWait) {
        this.duration = duration;
        this.maxCount = maxCount;
        this.strategy = strategy;
        this.maxWait = maxWait;
        this.tokenQueues = new Token[maxCount];
        /** 初始化maxCount个令牌 */
        for(int i = 0 ; i < maxCount; ++i) {
            tokenQueues[i] = new Token(i);
        }
    }

    public boolean useToken() {
        return duration > 0 && maxCount > 0;
    }

    public synchronized boolean acquire() {
        long currentTimestamp = System.currentTimeMillis();
        boolean flag = false;
        int index = 0;
        Token token;
        long earliestTime = currentTimestamp;
        Token earliestToken = null;
        while (index++ < maxCount) {
            // 取出第一个并删除
            token = tokenQueues[currentIndex++ % maxCount];
            // 直到满足条件： 第一次使用或者令牌已经过期
            if(token.millis == 0 || currentTimestamp - token.millis >= duration * 1000) {
                // 第一次使用或者令牌已经过期（可回收复用）
                token.millis = currentTimestamp;
                flag = true;
                break;
            } else {
                if(strategy == 2) {
                    if(earliestTime > token.millis) {
                        earliestTime = token.millis;
                        earliestToken = token;
                    }
                }
            }
        }
        if(earliestToken != null) {
            try {
                long sleepMillis = currentTimestamp - earliestToken.millis;
                if(sleepMillis >= maxWait) {
                    return false;
                }
                Thread.sleep(sleepMillis);
                // 更新令牌时间
                earliestToken.millis = currentTimestamp;
                return true;
            } catch (InterruptedException e) {
            }
        }

        // 如果是拒绝策略直接返回
        return flag;
    }

    public long getMaxWait() {
        return maxWait;
    }

    public String getExceptionHandler() {
        return exceptionHandler;
    }

    public void setExceptionHandler(String exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }
}
