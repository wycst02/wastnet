package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

/**
 * Route handler.
 *
 * @author wangyc
 */
public interface HttpRoute {

    /**
     * Handle HTTP request.
     *
     * @param path     matched subPath
     * @param request  HTTP request
     * @param response HTTP response
     * @throws Throwable if handling fails
     */
    void handle(String path, HttpRequest request, HttpResponse response) throws Throwable;

    /**
     * Return self.
     */
    default HttpRoute self() {
        return this;
    }
}