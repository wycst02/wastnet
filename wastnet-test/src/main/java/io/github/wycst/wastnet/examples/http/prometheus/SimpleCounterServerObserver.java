package io.github.wycst.wastnet.examples.http.prometheus;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.extension.HttpServerObserver;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Demo {@link HttpServerObserver} implementation using atomic counters (no external
 * dependencies). It is provided as an example of how to implement the observer SPI
 * at the application layer &mdash; the framework does not ship a built-in metrics
 * collector.
 * <p>
 * Tracks request counts, status-code distribution, durations, and connection counts.
 * Call {@link #getMetrics()} to obtain a snapshot (e.g. expose it via a {@code /metrics} endpoint).
 */
public class SimpleCounterServerObserver implements HttpServerObserver, ClearableHandler {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder activeRequests = new LongAdder();
    private final LongAdder successRequests = new LongAdder();      // 2xx
    private final LongAdder redirectRequests = new LongAdder();     // 3xx
    private final LongAdder clientErrorRequests = new LongAdder();  // 4xx
    private final LongAdder serverErrorRequests = new LongAdder();  // 5xx
    private final LongAdder errorRequests = new LongAdder();        // with throwable

    private final LongAdder totalConnections = new LongAdder();
    private final LongAdder activeConnections = new LongAdder();

    private final LongAdder totalDurationNanos = new LongAdder();
    private final AtomicLong maxDurationNanos = new AtomicLong(0L);

    @Override
    public void onRequestStart(HttpRequest request) {
        totalRequests.increment();
        activeRequests.increment();
    }

    @Override
    public void onRequestComplete(HttpRequest request, HttpResponse response, long durationNanos, Throwable error) {
        activeRequests.decrement();
        totalDurationNanos.add(durationNanos);
        long oldMax;
        while (durationNanos > (oldMax = maxDurationNanos.get())
                && !maxDurationNanos.compareAndSet(oldMax, durationNanos)) {
            // retry CAS until success or a larger value appears
        }
        HttpStatus status = response.getStatus();
        int code = status.code;
        if (code >= 200 && code < 300) {
            successRequests.increment();
        } else if (code >= 300 && code < 400) {
            redirectRequests.increment();
        } else if (code >= 400 && code < 500) {
            clientErrorRequests.increment();
        } else if (code >= 500) {
            serverErrorRequests.increment();
        }
        if (error != null) {
            errorRequests.increment();
        }
    }

    @Override
    public void onConnectionOpen(ChannelContext ctx) {
        totalConnections.increment();
        activeConnections.increment();
    }

    @Override
    public void onConnectionClose(ChannelContext ctx) {
        activeConnections.decrement();
    }

    /**
     * Snapshot of current metrics. Suitable for exposing via an HTTP endpoint or logs.
     *
     * @return metrics map
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<String, Object>();
        long total = totalRequests.sum();
        metrics.put("totalRequests", total);
        metrics.put("activeRequests", activeRequests.sum());
        metrics.put("success2xx", successRequests.sum());
        metrics.put("redirect3xx", redirectRequests.sum());
        metrics.put("clientError4xx", clientErrorRequests.sum());
        metrics.put("serverError5xx", serverErrorRequests.sum());
        metrics.put("errorRequests", errorRequests.sum());
        metrics.put("totalConnections", totalConnections.sum());
        metrics.put("activeConnections", activeConnections.sum());
        metrics.put("totalDurationNanos", totalDurationNanos.sum());
        metrics.put("avgDurationMs", total > 0 ? (double) totalDurationNanos.sum() / total / 1_000_000.0 : 0.0);
        metrics.put("maxDurationMs", (double) maxDurationNanos.get() / 1_000_000.0);
        return metrics;
    }

    /**
     * Reset all accumulated counters. Invoked by the framework on server stop
     * (via {@link ClearableHandler}) so that metrics do not accumulate across
     * restarts. Implementations that wish to preserve historical totals across
     * restarts simply should not implement {@link ClearableHandler}.
     */
    @Override
    public void clear() {
        totalRequests.reset();
        activeRequests.reset();
        successRequests.reset();
        redirectRequests.reset();
        clientErrorRequests.reset();
        serverErrorRequests.reset();
        errorRequests.reset();
        totalConnections.reset();
        activeConnections.reset();
        totalDurationNanos.reset();
        maxDurationNanos.set(0L);
    }
}
