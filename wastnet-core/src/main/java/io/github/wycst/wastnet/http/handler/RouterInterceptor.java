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

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

/**
 * Route-level interceptor for {@link HttpRouterHandler}.
 * <p>
 * Independent of the server-wide {@code HttpServerInterceptor}. Registered on the router via
 * {@link HttpRouterHandler#interceptor(RouterInterceptor)}; multiple interceptors form an ordered
 * chain and run before route dispatch. Any interceptor returning {@code false} short-circuits the
 * request (the response must already be written), skipping the remaining interceptors and the
 * matched route handler.
 *
 * @author wangyc
 */
public interface RouterInterceptor {

    /**
     * Invoked before route dispatch.
     *
     * @param path     matched subPath (decoded, query-stripped and contextPath-stripped), same as the
     *                 {@code path} passed to {@link HttpRoute#handle(String, HttpRequest, HttpResponse)}
     * @param request  the HTTP request
     * @param response the HTTP response
     * @return {@code true} to continue; {@code false} to short-circuit (response must be written)
     * @throws Throwable if the interceptor fails
     */
    boolean beforeHandle(String path, HttpRequest request, HttpResponse response) throws Throwable;
}
