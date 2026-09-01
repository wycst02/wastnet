package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

/**
 * Request handler interface for HTTP request processing.
 *
 * @author wangyc
 */
public interface HttpRequestHandler {

    /**
     * Handle HTTP request.
     *
     * @param request  HTTP request
     * @param response HTTP response
     * @throws Throwable if handling fails
     */
    void handle(HttpRequest request, HttpResponse response) throws Throwable;

    /**
     * Prepare this handler before the server starts serving (called once at startup).
     * <p>Default is a no-op; subclasses may override to do one-time setup such as
     * route pre-processing (e.g. sorting).</p>
     */
    default void prepare() {
    }
}