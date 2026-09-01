package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.extension.HttpServerInterceptor;
import io.github.wycst.wastnet.http.extension.HttpServerObserver;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * Internal dispatcher wrapping the HTTP request/connection lifecycle, combining the
 * active interceptor and the passive observer. Assembled once at {@code prepare()}
 * (immutable, no runtime hot-swap); callers guard with a single {@code delegate != null}
 * check and the per-SPI null-checks live here.
 */
final class HttpRequestLifecycleDelegate {

    static final Log log = LogFactory.getLog(HttpRequestLifecycleDelegate.class);

    private final HttpServerInterceptor interceptor;
    private final HttpServerObserver observer;

    /**
     * Capture the (possibly null) interceptor and observer at {@code prepare()} time.
     */
    HttpRequestLifecycleDelegate(HttpServerInterceptor interceptor, HttpServerObserver observer) {
        this.interceptor = interceptor;
        this.observer = observer;
    }

    /**
     * Notify the observer that a request started, then ask the interceptor whether to proceed.
     *
     * @return {@code true} to continue into the business handler; {@code false} to
     *         short-circuit (the interceptor wrote the response itself). An exception
     *         thrown by the interceptor is swallowed, logged and treated as {@code false}.
     */
    boolean onRequestStart(HttpRequest request, HttpResponse response, ChannelContext ctx) {
        if (observer != null) {
            observer.onRequestStart(request);
        }
        try {
            return interceptor == null || interceptor.beforeHandle(request, response, ctx);
        } catch (Exception e) {
            // interceptor failure => short-circuit, skip the business handler
            log.error("Interceptor beforeHandle failed:", e);
            response.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Interceptor execution error");
            return false;
        }
    }

    /**
     * Notify the observer that a request completed. Called exactly once from the caller's
     * outer {@code finally}; {@code error} is null on a normal or short-circuited completion.
     */
    void onRequestComplete(HttpRequest request, HttpResponse response, long durationNanos, Throwable error) {
        if (observer != null) {
            observer.onRequestComplete(request, response, durationNanos, error);
        }
    }

    /** Notify the observer that a connection opened (after accept / handshake). */
    void onConnectionOpen(ChannelContext ctx) {
        if (observer != null) {
            observer.onConnectionOpen(ctx);
        }
    }

    /** Notify the observer that a connection closed. */
    void onConnectionClose(ChannelContext ctx) {
        if (observer != null) {
            observer.onConnectionClose(ctx);
        }
    }

    /** Clear the interceptor / observer if either implements {@link ClearableHandler} (e.g. on server stop). */
    void clear() {
        if (interceptor instanceof ClearableHandler) {
            ((ClearableHandler) interceptor).clear();
        }
        if (observer instanceof ClearableHandler) {
            ((ClearableHandler) observer).clear();
        }
    }
}
