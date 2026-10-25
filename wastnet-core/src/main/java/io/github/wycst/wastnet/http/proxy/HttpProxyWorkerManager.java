package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/**
 * Proxy worker manager, owns a fixed pool of workers shared by all target addresses.
 * Lifecycle is bound to the owning {@link io.github.wycst.wastnet.http.handler.HttpRouterHandler}.
 *
 * @author wangyc
 */
public class HttpProxyWorkerManager {

    static final Log log = LogFactory.getLog(HttpProxyWorkerManager.class);

    /**
     * Global fallback manager for users who construct {@link HttpProxyRoute} directly.
     * Note: this manager is never shut down; it relies on JVM exit for cleanup.
     */
    public static final HttpProxyWorkerManager GLOBAL_WORKER_MANAGER = new HttpProxyWorkerManager();

    static final ByteBuffer GATEWAY_TIMEOUT_RESPONSE = ByteBuffer.wrap("HTTP/1.1 504 Gateway Timeout\r\nContent-Length: 0\r\n\r\n".getBytes());

    private final ScheduledExecutorService timeoutScheduler;
    private final HttpProxyWorker[] workers;
    private volatile int cursor;

    public HttpProxyWorkerManager() {
        this(Runtime.getRuntime().availableProcessors());
    }

    public HttpProxyWorkerManager(int workerCount) {
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-read-timeout");
            t.setDaemon(true);
            return t;
        });
        workers = new HttpProxyWorker[Utils.powerOfWorkerNum(workerCount)];
        for (int i = 0; i < workers.length; i++) {
            try {
                workers[i] = new HttpProxyWorker(this);
                workers[i].setName("proxy-worker-" + i);
                workers[i].start();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create proxy worker", e);
            }
        }
    }

    /**
     * Pick the next worker in round-robin order.
     *
     * @return the next HttpProxyWorker in the pool
     */
    public HttpProxyWorker next() {
        return workers[(cursor++) & (workers.length - 1)];
    }

    /**
     * Shutdown all workers and the timeout scheduler managed by this instance.
     */
    public void shutdown() {
        for (HttpProxyWorker worker : workers) {
            try {
                worker.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down proxy worker {}: {}", worker.getName(), e.getMessage());
            }
        }
        Utils.shutdownExecutorService(timeoutScheduler);
    }

    /**
     * Schedule a read timeout task.
     *
     * @param task  timeout task to execute
     * @param delay delay in milliseconds
     * @return ScheduledFuture for cancellation
     */
    ScheduledFuture<?> scheduleTimeout(Runnable task, long delay) {
        return timeoutScheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
    }
}
