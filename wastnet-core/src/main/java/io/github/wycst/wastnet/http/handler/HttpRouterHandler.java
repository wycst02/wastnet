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
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import io.github.wycst.wastnet.http.proxy.HttpProxyRoute;
import io.github.wycst.wastnet.http.proxy.HttpProxyWorkerManager;
import io.github.wycst.wastnet.http.upgrade.DefaultUpgradeHandler;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Router Request Handler with context path support and handler dispatching.
 * <p>
 * Extends {@link DefaultUpgradeHandler} to support WebSocket and h2c upgrade registration
 * with automatic context path prefix.
 *
 * <p>contextPath: default is "/"; if configured, must start with "/" and trailing "/" will be removed.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>exactRoute: exact match (no regex)</li>
 *   <li>route: prefix match by default, e.g. "/api" matches "/api", "/api/xxx"</li>
 *   <li>route with "^" prefix: treated as regex</li>
 *   <li>route with "$" suffix: exact regex match</li>
 *   <li>route without "^" or "$": prefix match (automatically appends ".*")</li>
 *   <li>ws(): register WebSocket with context path prefix</li>
 *   <li>h2c(): register h2c with context path prefix</li>
 * </ul>
 *
 * @author wangyc
 */
public class HttpRouterHandler extends DefaultUpgradeHandler 
        implements HttpRequestHandler {

    static final Log log = LogFactory.getLog(HttpRouterHandler.class);
    /**
     * Default health check route path
     */
    public static final String DEFAULT_HEALTH_ROUTE = "/health";
    static String defaultHealthContentType = HttpHeaderValues.APPLICATION_JSON_UTF8;
    static String defaultHealthResponseBody = "{\"status\":\"UP\"}";

    private final String contextPath;
    private String healthRoute = DEFAULT_HEALTH_ROUTE;
    private final int contextPathLen;
    // exactRoutes is rarely seen
    protected final Map<String, HttpRoute> exactRoutes = new HashMap<>();
    protected final List<RouteEntry> routes = new ArrayList<>();
    private boolean disableAutoSort; // when true, keep registration order in prepare()
    private HttpRequestHandler notFoundHandler;
    private byte[] notFoundBytes;
    private boolean autoRedirect = true;
    // Master switch for route-level interceptors (PRE_ROUTE + ENDPOINT); disabled by default = enabled
    private boolean interceptorsDisabled = false;
    private HttpProxyWorkerManager proxyWorkerManager;
    // Route-level interceptors (ordered chain), independent of server-wide HttpServerInterceptor
    private final List<RouterInterceptor> interceptors = new ArrayList<>();

    /**
     * Create a router handler with root context path ({@code "/"}).
     */
    public HttpRouterHandler() {
        this("/");
    }

    /**
     * Create a router handler with the given context path.
     * <p>
     * The context path must start with {@code "/"}; trailing slashes are removed.
     * All routes registered via {@link #route(String, HttpRoute)} and friends
     * will be prefixed with this context path at match time.
     *
     * @param contextPath the context path (e.g. {@code "/api"}), defaults to {@code "/"}
     */
    public HttpRouterHandler(String contextPath) {
        if (contextPath == null || (contextPath = contextPath.trim()).isEmpty()) {
            contextPath = "/";
        } else {
            if (!contextPath.startsWith("/")) {
                contextPath = "/" + contextPath;
            }
            // Remove all trailing "/"
            int end = contextPath.length();
            while (end > 1 && contextPath.charAt(end - 1) == '/') {
                --end;
            }
            contextPath = contextPath.substring(0, end);
        }
        this.contextPath = contextPath;
        this.contextPathLen = contextPath.length();
    }

    /**
     * Register an exact-matched route (no regex).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler exactRoute(String path, HttpRoute route) {
        exactRoutes.put(path, route.self());
        return this;
    }

    /**
     * Register an exact-matched route restricted to the given HTTP methods.
     *
     * @param path          the exact path
     * @param route         the route handler
     * @param allowMethods  allowed HTTP methods (empty means no restriction, equivalent to {@link #exactRoute(String, HttpRoute)})
     * @return this handler for chaining
     */
    public HttpRouterHandler exactRoute(String path, HttpRoute route, HttpMethod... allowMethods) {
        exactRoutes.put(path, allowMethods.length == 0 ? route.self() : new HttpMethodRoute(route, allowMethods));
        return this;
    }

    /**
     * Register a prefix-matched route.
     * <p>
     * By default, the path is treated as a prefix: {@code "/api"} matches both
     * {@code "/api"} and {@code "/api/xxx"}. Prefix a path with {@code "^"} to
     * treat it as a regex pattern.
     *
     * @param path  the path prefix or regex pattern
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler route(String path, HttpRoute route) {
        routes.add(new RouteEntry(path, route.self()));
        return this;
    }

    /**
     * Register a prefix-matched route with optional method restrictions.
     */
    public HttpRouterHandler route(String path, HttpRoute route, HttpMethod... allowMethods) {
        routes.add(new RouteEntry(path, allowMethods.length == 0 ? route.self() : new HttpMethodRoute(route, allowMethods)));
        return this;
    }

    /**
     * Register a route-level interceptor (appended to the ordered interceptor chain).
     * The interceptor runs after context-path resolution and before route dispatch; returning
     * {@code false} short-circuits the request. May be called multiple times to form a chain.
     *
     * @param interceptor the route interceptor
     * @return this handler for chaining
     */
    public HttpRouterHandler interceptor(RouterInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
        return this;
    }

    /**
     * Register a GET route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler get(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.GET));
        return this;
    }

    /**
     * Register a POST route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler post(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.POST));
        return this;
    }

    /**
     * Register a PUT route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler put(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.PUT));
        return this;
    }

    /**
     * Register a DELETE route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler delete(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.DELETE));
        return this;
    }

    /**
     * Register a PATCH route (exact match).
     *
     * @param path  the exact request path
     * @param route the route handler
     * @return this handler for chaining
     */
    public HttpRouterHandler patch(String path, HttpRoute route) {
        exactRoutes.put(path, new HttpMethodRoute(route, HttpMethod.PATCH));
        return this;
    }

    /**
     * Register a reverse proxy route (default: path rewrite disabled).
     *
     * @param path  the request path to match
     * @param route the target URI
     * @return this handler for chaining
     */
    public HttpRouterHandler proxy(String path, String route) {
        return proxy(path, route, false);
    }

    /**
     * Register a reverse proxy route with optional path rewrite.
     *
     * @param path    the request path to match
     * @param route   the target URI
     * @param rewrite whether to rewrite the request path when proxying
     * @return this handler for chaining
     */
    public HttpRouterHandler proxy(String path, String route, boolean rewrite) {
        return proxy(path, HttpProxyConfig.target(route).rewrite(rewrite));
    }

    /**
     * Register a reverse proxy route with a full {@link HttpProxyConfig}.
     *
     * @param path   the request path to match
     * @param config the proxy configuration
     * @return this handler for chaining
     */
    public HttpRouterHandler proxy(String path, HttpProxyConfig config) {
        if (proxyWorkerManager == null) {
            proxyWorkerManager = new HttpProxyWorkerManager();
        }
        routes.add(new RouteEntry(path, new HttpProxyRoute(config, proxyWorkerManager)));
        return this;
    }

    /**
     * Register a static resources handler.
     *
     * @param resource the resource handler (configured with base path, directory, etc.)
     * @return this handler for chaining
     */
    public HttpRouterHandler resource(HttpResourceRoute resource) {
        routes.add(new RouteEntry(resource.routePath, true, resource));
        resource.basePath(contextPath);
        return this;
    }

    /**
     * Set a custom handler for unmatched requests (404 fallback).
     *
     * @param notFoundHandler the fallback handler; {@code null} to restore default
     * @return this handler for chaining
     */
    public HttpRouterHandler notFoundHandler(HttpRequestHandler notFoundHandler) {
        this.notFoundHandler = notFoundHandler;
        return this;
    }

    /**
     * Set the health check route path.
     * Set to null or empty to disable health endpoint.
     *
     * @param healthRoute the health route path (e.g., "/health")
     * @return this handler for chaining
     */
    public HttpRouterHandler healthRoute(String healthRoute) {
        if (healthRoute == null || healthRoute.trim().isEmpty()) {
            this.healthRoute = null;
        } else {
            if (!healthRoute.startsWith("/")) {
                healthRoute = "/" + healthRoute;
            }
            this.healthRoute = healthRoute;
        }
        return this;
    }

    /**
     * Enable or disable automatic redirect from root path "/" to context path.
     * When enabled (default), accessing "/" will redirect to "contextPath/".
     * When disabled, accessing "/" will result in 404 if no route matches.
     *
     * @param enable true to enable redirect, false to disable
     * @return this handler for chaining
     */
    public HttpRouterHandler autoRedirect(boolean enable) {
        this.autoRedirect = enable;
        return this;
    }

    /**
     * Master switch for route-level interceptors (PRE_ROUTE + ENDPOINT).
     * When disabled (true), no route-level interceptor will be registered or bound.
     * Default is false (interceptors enabled).
     *
     * @param disabled true to disable all route-level interceptors
     * @return this handler for chaining
     */
    public HttpRouterHandler interceptorsDisabled(boolean disabled) {
        this.interceptorsDisabled = disabled;
        return this;
    }

    /**
     * Whether route-level interceptors are globally disabled.
     */
    protected boolean isInterceptorsDisabled() {
        return interceptorsDisabled;
    }

    /**
     * Run the given interceptors in order; returns false once one short-circuits the request.
     *
     * @return true if all passed (or the list is empty), false if one returned false
     */
    protected boolean applyInterceptors(List<RouterInterceptor> interceptors, String path,
                                         HttpRequest request, HttpResponse response) throws Throwable {
        if (isInterceptorsDisabled() || interceptors.isEmpty()) return true;
        for (RouterInterceptor interceptor : interceptors) {
            if (!interceptor.beforeHandle(path, request, response)) return false;
        }
        return true;
    }

    /**
     * Clear all registered routes and release associated resources.
     *
     * <p>Cleanup order and resource release:</p>
     * <ol>
     *   <li>Iterate all handlers in {@code exactRoutes} / {@code routes} and call
     *       {@link ClearableHandler#clear()} on each that implements it.
     *       {@link io.github.wycst.wastnet.http.proxy.HttpProxyRoute#clear()} closes
     *       <b>proxy connections</b> (clientCtx / targetCtx TCP channels) for its route.</li>
     *   <li>Clear route maps ({@code exactRoutes.clear()}, {@code routes.clear()}).</li>
     *   <li>If a custom {@link io.github.wycst.wastnet.http.proxy.HttpProxyWorkerManager} exists,
     *       call {@code shutdown()}:
     *       <ul>
     *         <li>Stops all proxy worker threads.</li>
     *         <li>Shuts down the <b>timeout scheduler</b> via
     *             {@link io.github.wycst.wastnet.util.Utils#shutdownExecutorService}
     *             (shutdown + awaitTermination 5s + shutdownNow).</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>Note: routes registered via {@code new HttpProxyRoute(config)} use the static
     * {@link io.github.wycst.wastnet.http.proxy.HttpProxyWorkerManager#GLOBAL_WORKER_MANAGER},
     * which is never shut down (relies on JVM exit).</p>
     */
    @Override
    public void clear() {
        for (HttpRoute route : exactRoutes.values()) {
            if (route instanceof ClearableHandler) {
                ((ClearableHandler) route).clear();
            }
        }
        for (RouteEntry entry : routes) {
            if (entry.handler instanceof ClearableHandler) {
                ((ClearableHandler) entry.handler).clear();
            }
        }
        exactRoutes.clear();
        routes.clear();
        for (RouterInterceptor interceptor : interceptors) {
            if (interceptor instanceof ClearableHandler) {
                ((ClearableHandler) interceptor).clear();
            }
        }
        interceptors.clear();
        if (proxyWorkerManager != null) {
            proxyWorkerManager.shutdown();
            proxyWorkerManager = null;
        }
        super.clear();
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
        String path = request.getRequestUri();

        // Extract subPath
        String subPath;
        // Context path matching
        if (contextPathLen == 1) {
            subPath = path;
        } else {
            if (!path.startsWith(contextPath) || (path.length() > contextPathLen && path.charAt(contextPathLen) != '/')) {
                // Check if root path "/" and redirect to context path
                if (autoRedirect && "/".equals(path) && contextPathLen > 1) {
                    response.status(HttpStatus.MOVED_PERMANENTLY)
                            .header("Location", contextPath + "/")
                            .commit();
                    return;
                }
                // Not matched or Invalid path format after contextPath
                handleNotFound(request, response);
                return;
            }
            subPath = path.substring(contextPathLen);
            if (subPath.isEmpty()) {
                subPath = "/";
            }
        }
        // Route-level interceptors run after context-path resolution and before route dispatch.
        // Any interceptor returning false short-circuits the request.
        if (!applyInterceptors(interceptors, subPath, request, response)) {
            return;
        }
        // Exact match (health route should have lower priority than exact routes)
        if (!exactRoutes.isEmpty()) {
            // Exact match first
            HttpRoute exactRoute = exactRoutes.get(subPath);
            if (exactRoute != null) {
                exactRoute.handle(subPath, request, response);
                return;
            }
        }

        // Health route check (before prefix/regex match)
        if (subPath.equals(healthRoute)) {
            response.contentType(defaultHealthContentType).status(HttpStatus.OK).body(defaultHealthResponseBody);
            return;
        }

        // Regex/prefix match
        for (RouteEntry entry : routes) {
            if (entry.match(subPath)) {
                entry.handler.handle(subPath, request, response);
                return;
            }
        }

        handleNotFound(request, response);
    }

    private void handleNotFound(HttpRequest request, HttpResponse response) throws Throwable {
        // RFC 7230 asterisk-form: OPTIONS * queries server capabilities. Reply 200 here so it
        // works regardless of context path; if the application layer registered a "*" route it
        // is matched earlier and never reaches this fallback.
        if (request.getMethod() == HttpMethod.OPTIONS && "*".equals(request.getUri())) {
            response.status(HttpStatus.OK);
            return;
        }
        if (notFoundHandler != null) {
            notFoundHandler.handle(request, response);
        } else {
            if (notFoundBytes == null) {
                notFoundBytes = "404 Not Found".getBytes();
            }
            response.status(HttpStatus.NOT_FOUND).body(notFoundBytes);
        }
    }

    /**
     * Return the context path configured for this router.
     *
     * @return the context path, never {@code null} (default is {@code "/"})
     */
    public String getContextPath() {
        return contextPath;
    }


    /**
     * Route entry with pattern matching.
     */
    protected static class RouteEntry {
        private final String pattern;
        private final boolean prefix;
        private final Pattern regex;
        final HttpRoute handler;

        RouteEntry(String pattern, HttpRoute handler) {
            this(pattern, false, handler);
        }

        RouteEntry(String pattern, boolean prefix, HttpRoute handler) {
            prefix = prefix || pattern.charAt(0) != '^';
            this.pattern = pattern;
            this.handler = handler;
            this.regex = prefix ? null : Pattern.compile(pattern.endsWith("$") ? pattern : pattern + ".*");
            this.prefix = prefix;
        }

        boolean match(String path) {
            if (prefix) {
                if("/".equals(pattern)) return true;
                return path.startsWith(pattern) && (path.length() == pattern.length() || path.charAt(pattern.length()) == '/');
            }
            return regex.matcher(path).matches();
        }
    }

    // ==================== Upgrade registration ====================

    /**
     * Build full path by appending path to contextPath.
     */
    private String buildFullPath(String path) {
        if (contextPathLen == 1) {
            return path.startsWith("/") ? path : "/" + path;
        }
        return contextPath + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * Register a WebSocket resource with context path prefix.
     *
     * @param path    WebSocket path (will be prefixed with contextPath)
     * @param resource WebSocket resource
     * @return WebSocketResource
     */
    @Override
    public WebSocketResource ws(String path, WebSocketResource resource) {
        return super.ws(buildFullPath(path), resource);
    }

    /**
     * Register an h2c resource with context path prefix.
     *
     * @param path h2c path (will be prefixed with contextPath)
     */
    @Override
    public void h2c(String path) {
        super.h2c(buildFullPath(path));
    }

    /**
     * Register a Server-Sent Events endpoint with default timeout (30 minutes).
     * <p>
     * The handler receives a ready-to-use {@link SseEmitter} with SSE headers already sent.
     * Framework manages the lifecycle: handler runs asynchronously, the request thread blocks
     * until {@link SseEmitter#close()} is called or the 30-minute timeout elapses.
     * <p>
     * Use {@link #sse(String, long, SseHandler)} to specify a custom timeout.
     * <p>
     * Usage:
     * <pre>{@code
     * router.sse("/events", emitter -> {
     *     executor.submit(() -> {
     *         emitter.emit("hello");
     *         emitter.close();
     *     });
     * });
     * }</pre>
     *
     * @param path    the exact path for SSE endpoint
     * @param handler the SSE handler
     * @return this router handler for chaining
     */
    public HttpRouterHandler sse(String path, SseHandler handler) {
        return sse(path, -1, handler);
    }

    /**
     * Register a Server-Sent Events endpoint with a custom timeout.
     * <p>
     * The handler runs asynchronously; the request thread blocks until
     * {@link SseEmitter#close()} is called or the timeout elapses.
     *
     * @param path      the exact path for SSE endpoint
     * @param timeoutMs maximum duration in milliseconds before the SSE connection is closed;
     *                   if {@code < 0}, the global/instance option {@link HttpOptions#SSE_TIMEOUT_MS} is used instead
     * @param handler   the SSE handler
     * @return this router handler for chaining
     */
    public HttpRouterHandler sse(String path, final long timeoutMs, final SseHandler handler) {
        exactRoutes.put(path, (subPath, request, response) -> {
            final SseEmitter emitter = ((HttpInternalResponse) response).sseEmitter();
            final ChannelContext sseCtx = ((HttpInternalRequest) request).ctx();
            sseCtx.runAsync(() -> {
                try {
                    handler.handle(emitter);
                } catch (Throwable throwable) {
                    log.error("SSE handler error", throwable);
                } finally {
                    emitter.close();
                }
            });
            final long effectiveTimeoutMs = timeoutMs < 0 ? sseCtx.option(HttpOptions.SSE_TIMEOUT_MS) : timeoutMs;
            if (!emitter.awaitClose(effectiveTimeoutMs)) {
                emitter.close(); // timeout: force close
            }
        });
        return this;
    }

    /**
     * Set the default health-check response body and its content type.
     * <p>Affects all {@link HttpRouterHandler} instances' health route unless overridden
     * per-instance via {@link #healthRoute(String)}. Defaults: {@code application/json; charset=utf-8}
     * and {@code {"status":"UP"}}.</p>
     *
     * @param contentType the response Content-Type header value
     * @param body        the response body string
     */
    public static void setDefaultHealthResponse(String contentType, String body) {
        defaultHealthContentType = contentType;
        defaultHealthResponseBody = body;
    }

    /**
     * Disable the automatic route sorting done at startup.
     * <p>By default {@link #prepare()} reorders routes (most specific first). Call this to
     * keep the exact registration order, i.e. first-registered route is matched first.</p>
     *
     * @return this router handler for chaining
     */
    public HttpRouterHandler disableAutoSort() {
        this.disableAutoSort = true;
        return this;
    }

    /**
     * One-time startup preparation: sort routes so the most specific matches first.
     * <p>Order: non-root prefixes by descending length (e.g. {@code /user/aaa} before
     * {@code /user}), then regex routes in registration order, then the root prefix
     * {@code /} last as the catch-all. This mirrors nginx prefix-location priority
     * while keeping regex order and preserving prefix-before-regex semantics. Skipped
     * when {@link #disableAutoSort()} has been called.</p>
     */
    @Override
    public void prepare() {
        if (disableAutoSort || routes.size() <= 1) {
            return;
        }
        routes.sort((a, b) -> {
            boolean aRoot = a.prefix && "/".equals(a.pattern);
            boolean bRoot = b.prefix && "/".equals(b.pattern);
            if (aRoot != bRoot) {
                return aRoot ? 1 : -1; // root prefix last
            }
            if (a.prefix != b.prefix) {
                return a.prefix ? -1 : 1; // prefix routes before regex routes
            }
            if (a.prefix) {
                return b.pattern.length() - a.pattern.length(); // longer prefix first
            }
            return 0; // regex routes: preserve registration order (stable sort)
        });
    }
}