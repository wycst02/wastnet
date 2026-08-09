/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;

/**
 * Method-filtered or method-dispatch route.
 * <p>
 * Supports two usage patterns:
 * <pre>
 * // 1. Quick filter: single handler for multiple methods
 * new HttpMethodRoute(handler, HttpMethod.GET)
 * new HttpMethodRoute(handler, HttpMethod.GET, HttpMethod.POST)
 *
 * // 2. Builder dispatch: different handlers per method
 * new HttpMethodRoute()
 *     .get(listHandler)
 *     .post(createHandler)
 * </pre>
 *
 * @author wangyc
 */
public class HttpMethodRoute implements HttpRoute {

    private final HttpRoute[] handlers = new HttpRoute[HttpMethod.values().length];
    private String allowHeader;

    /** No-arg constructor for builder pattern (#2). */
    public HttpMethodRoute() {
    }

    /** Quick-filter constructor: one delegate for one or more methods (#1). */
    public HttpMethodRoute(HttpRoute delegate, HttpMethod... allowMethods) {
        if (delegate == null || allowMethods == null || allowMethods.length == 0) {
            throw new IllegalArgumentException("delegate and at least one method must be specified");
        }
        for (HttpMethod m : allowMethods) {
            handlers[m.ordinal()] = delegate;
        }
        buildAllowHeader();
    }

    // ==================== Builder methods ====================

    /**
     * Bind a handler for GET requests.
     *
     * @param handler the route handler for GET
     * @return this builder for chaining
     */
    public HttpMethodRoute get(HttpRoute handler) {
        handlers[HttpMethod.GET.ordinal()] = handler;
        buildAllowHeader();
        return this;
    }

    /**
     * Bind a handler for HEAD requests.
     *
     * @param handler the route handler for HEAD
     * @return this builder for chaining
     */
    public HttpMethodRoute head(HttpRoute handler) {
        handlers[HttpMethod.HEAD.ordinal()] = handler;
        buildAllowHeader();
        return this;
    }

    /**
     * Bind a handler for POST requests.
     *
     * @param handler the route handler for POST
     * @return this builder for chaining
     */
    public HttpMethodRoute post(HttpRoute handler) {
        handlers[HttpMethod.POST.ordinal()] = handler;
        buildAllowHeader();
        return this;
    }

    /**
     * Bind a handler for PUT requests.
     *
     * @param handler the route handler for PUT
     * @return this builder for chaining
     */
    public HttpMethodRoute put(HttpRoute handler) {
        handlers[HttpMethod.PUT.ordinal()] = handler;
        buildAllowHeader();
        return this;
    }

    /**
     * Bind a handler for DELETE requests.
     *
     * @param handler the route handler for DELETE
     * @return this builder for chaining
     */
    public HttpMethodRoute delete(HttpRoute handler) {
        handlers[HttpMethod.DELETE.ordinal()] = handler;
        buildAllowHeader();
        return this;
    }

    /**
     * Bind a handler for PATCH requests.
     *
     * @param handler the route handler for PATCH
     * @return this builder for chaining
     */
    public HttpMethodRoute patch(HttpRoute handler) {
        handlers[HttpMethod.PATCH.ordinal()] = handler;
        buildAllowHeader();
        return this;
    }

    // ==================== Dispatch ====================

    @Override
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        HttpMethod method = request.getMethod();
        if (method != null) {
            HttpRoute handler = handlers[method.ordinal()];
            if (handler != null) {
                handler.handle(path, request, response);
                return;
            }
        }
        response.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaderNormalized.getAllow(), allowHeader)
                .body(HttpStatus.METHOD_NOT_ALLOWED.text.getBytes());
    }

    private void buildAllowHeader() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < handlers.length; ++i) {
            if (handlers[i] != null) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(HttpMethod.values()[i].name());
            }
        }
        this.allowHeader = sb.toString();
    }
}
