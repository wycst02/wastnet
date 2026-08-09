package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.SseEmitter;

/**
 * Handler for Server-Sent Events routes registered via {@link HttpRouterHandler#sse(String, SseHandler)}.
 * <p>
 * The handler receives a ready-to-use {@link SseEmitter} with headers already sent.
 * Events can be pushed from any thread, and the connection stays open until
 * {@link SseEmitter#close()} is called or the timeout expires.
 *
 * @author wangyc
 * @see HttpRouterHandler#sse(String, SseHandler)
 */
public interface SseHandler {

    /**
     * Handle an SSE connection.
     *
     * @param emitter the SSE emitter for pushing events
     * @throws Exception if an error occurs
     */
    void handle(SseEmitter emitter) throws Throwable;
}
