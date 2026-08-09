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
}