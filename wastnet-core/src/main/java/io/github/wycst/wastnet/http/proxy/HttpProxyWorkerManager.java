package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Proxy worker manager, owns workers for each target address (host:port).
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
    private final Map<String, HttpProxyWorker> workers = new ConcurrentHashMap<String, HttpProxyWorker>();

    public HttpProxyWorkerManager() {
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proxy-read-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Get or create worker for the specified target address.
     *
     * @param hostPort target host:port
     * @return HttpProxyWorker for the target
     */
    public HttpProxyWorker get(String hostPort) throws IOException {
        HttpProxyWorker worker = workers.get(hostPort);
        if (worker == null) {
            synchronized (this) {
                worker = workers.get(hostPort);
                if (worker == null) {
                    worker = new HttpProxyWorker(this);
                    worker.setName("proxy-worker-" + hostPort);
                    workers.put(hostPort, worker);
                    worker.start();
                }
            }
        }
        return worker;
    }

    /**
     * Shutdown all workers and the timeout scheduler managed by this instance.
     */
    public void shutdown() {
        for (Map.Entry<String, HttpProxyWorker> entry : workers.entrySet()) {
            HttpProxyWorker worker = entry.getValue();
            try {
                worker.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down proxy worker {}: {}", entry.getKey(), e.getMessage());
            }
        }
        workers.clear();
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