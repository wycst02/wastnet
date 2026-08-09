package io.github.wycst.wastnet.http.extension;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * Observer SPI for HTTP server request/connection lifecycle events.
 * <p>
 * The framework fires these callbacks at request/connection lifecycle points
 * without depending on any specific library. Implementations may use it for
 * metrics, distributed tracing, audit logging, slow-request sampling, etc.
 * The framework itself ships no concrete implementation; application code (or
 * optional modules) is expected to provide one.
 * <p>
 * All callbacks are invoked on the I/O worker thread; implementations must be
 * non-blocking and cheap (e.g. atomic counters). Observers must NOT alter the
 * request/response or interrupt the processing flow &mdash; that is the role of
 * {@link HttpServerInterceptor}.
 * <p>
 * Pass {@code null} to {@code setObserver} to disable observation
 * entirely (zero overhead on the hot path).
 *
 * @author wangyc
 */
public interface HttpServerObserver {

    /**
     * Called when an HTTP request starts being processed.
     *
     * @param request the HTTP request
     */
    void onRequestStart(HttpRequest request);

    /**
     * Called when an HTTP request completes (after the response is committed).
     *
     * @param request       the HTTP request
     * @param response      the HTTP response (status is final)
     * @param durationNanos processing duration in nanoseconds
     * @param error         non-null if the request failed with an exception (e.g. 5xx), null otherwise
     */
    void onRequestComplete(HttpRequest request, HttpResponse response, long durationNanos, Throwable error);

    /**
     * Called when a connection is opened (after accept / handshake).
     *
     * @param ctx the channel context
     */
    void onConnectionOpen(ChannelContext ctx);

    /**
     * Called when a connection is closed.
     *
     * @param ctx the channel context
     */
    void onConnectionClose(ChannelContext ctx);
}
