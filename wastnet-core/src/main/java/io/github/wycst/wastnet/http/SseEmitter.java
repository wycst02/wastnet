package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.handler.SseHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events emitter for use with {@link HttpRouterHandler#sse(String, SseHandler)}.
 * <p>
 * Thread-safe and supports multi-threaded push. The handler may return immediately
 * after creating an emitter; push events and close from any thread.
 * <p>
 * Lifecycle: {@link HttpRouterHandler#sse(String, SseHandler)} → {@link #emit(String)} → {@link #close()}
 * <p>
 * Usage:
 * <pre>{@code
 * router.sse("/events", emitter -> {
 *     // Simple data-only event
 *     emitter.emit("{\"msg\":\"hello\"}");
 *     // Full control: event type, data, id, retry
 *     emitter.emit("chat", "hi", "msg-001", 3000);
 *     emitter.close();
 * });
 * }</pre>
 *
 * @author wangyc
 * @see HttpRouterHandler#sse(String, SseHandler)
 */
public class SseEmitter {

    private final HttpResponse response;
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private volatile boolean closed;
    private Runnable onClose;

    SseEmitter(HttpResponse response, ChannelContext ctx) {
        this.response = response;
        if (ctx != null) {
            ctx.addCloseListener(() -> {
                closed = true;
                closeLatch.countDown();
                if (onClose != null) onClose.run();
            });
        }
    }

    /**
     * Send an SSE event with the given data ({@code data: <data>\n\n}).
     *
     * @param data event data
     */
    public void emit(String data) throws IOException {
        synchronized (this) {
            checkClosed();
            response.sse(data);
        }
    }

    /**
     * Send an SSE event with full field control.
     * <p>
     * Fields with null/zero values are omitted.
     *
     * @param event event type (null to omit)
     * @param data  event data
     * @param id    event ID for reconnection (null to omit)
     * @param retry reconnection interval in ms (<=0 to omit)
     */
    public void emit(String event, String data, String id, long retry) throws IOException {
        synchronized (this) {
            checkClosed();
            response.sse(event, data, id, retry);
        }
    }

    /**
     * Close the SSE connection and release resources.
     * <p>
     * Signals the framework to commit the HTTP response (sends the chunked end marker
     * for HTTP/1.1 or END_STREAM for HTTP/2) and unblocks the request thread.
     * Safe to call multiple times; subsequent calls are no-ops.
     * <p>
     * The emitter must be closed explicitly; there is no automatic cleanup.
     */
    public void close() {
        if (closed) return;
        closed = true;
        closeLatch.countDown();
        if (onClose != null) onClose.run();
    }

    public void onClose(Runnable callback) {
        this.onClose = callback;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Block the current thread until the emitter is closed or the timeout expires.
     * Used internally by the framework for {@code router.sse()}.
     *
     * @param timeoutMs timeout in milliseconds
     * @return true if the emitter was closed before the timeout
     */
    public boolean awaitClose(long timeoutMs) throws InterruptedException {
        if (closed) return true;
        return closeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void checkClosed() {
        if (closed) throw new IllegalStateException("SSE connection is closed");
    }
}
