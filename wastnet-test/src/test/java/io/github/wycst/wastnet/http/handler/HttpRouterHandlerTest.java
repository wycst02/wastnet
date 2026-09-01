package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.withSettings;

/**
 * Coverage tests for HttpRouterHandler including RouteEntry.
 */
public class HttpRouterHandlerTest {

    @Test
    public void testDefaultConstructor() {
        HttpRouterHandler h = new HttpRouterHandler();
        assertNotNull(h);
    }

    @Test
    public void testPutDeletePatch() {
        HttpRouterHandler h = new HttpRouterHandler("/api");
        HttpRoute dummy = new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        h.post("/post", dummy);
        h.put("/put", dummy);
        h.delete("/delete", dummy);
        h.patch("/patch", dummy);
        assertNotNull(h);
    }

    @Test
    public void testProxy() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.proxy("/api", "http://backend");
        h.proxy("/v2", "http://backend2", true);
        h.proxy("/v3", HttpProxyConfig.target("http://backend3"));
        assertNotNull(h);
    }

    @Test
    public void testNotFoundHandler() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.notFoundHandler(new HttpRequestHandler() {
            public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.NOT_FOUND).body("custom".getBytes());
            }
        });
        assertNotNull(h);
    }

    @Test
    public void testAutoRedirectDisabled() {
        HttpRouterHandler h = new HttpRouterHandler("/app");
        h.autoRedirect(false);
        assertNotNull(h);
    }

    @Test
    public void testHealthRouteDisabled() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.healthRoute(null);
        h.healthRoute("");
        h.healthRoute("   ");
        assertNotNull(h);
    }

    @Test
    public void testHealthRouteWithoutSlash() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.healthRoute("healthz");
        assertNotNull(h);
    }

    @Test
    public void testResource() {
        HttpRouterHandler h = new HttpRouterHandler("/static");
        HttpResourceRoute resource = new HttpResourceRoute("/files", new File("."));
        h.resource(resource);
        assertNotNull(h);
    }

    @Test
    public void testRouteWithMethods() {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute dummy = new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        h.route("/secured", dummy, HttpMethod.GET, HttpMethod.POST);
        assertNotNull(h);
    }

    @Test
    public void testClear() {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute dummy = new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        h.get("/test", dummy);
        h.route("/prefix", dummy);
        h.clear();
        assertNotNull(h);
    }

    @Test
    public void testRouteEntryRegex() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("^/user/\\d+$", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        });
        assertNotNull(h);
    }

    @Test
    public void testExactRoute() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.exactRoute("/exact", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        });
        assertNotNull(h);
    }

    @Test
    public void testRouteWithHealthPath() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.healthRoute("/alive");
        assertNotNull(h);
    }

    @Test
    public void testContextPathWithTrailingSlash() {
        HttpRouterHandler h = new HttpRouterHandler("/app///");
        assertNotNull(h);
    }

    @Test
    public void testContextPathNull() {
        HttpRouterHandler h = new HttpRouterHandler(null);
        assertNotNull(h);
    }

    @Test
    public void testContextPathEmpty() {
        HttpRouterHandler h = new HttpRouterHandler("");
        assertNotNull(h);
    }

    @Test
    public void testContextPathNoLeadingSlash() {
        HttpRouterHandler h = new HttpRouterHandler("api");
        assertNotNull(h);
    }

    @Test
    public void testGetContextPath() {
        HttpRouterHandler h = new HttpRouterHandler("/myapp");
        assertEquals("/myapp", h.getContextPath());
    }

    @Test
    public void testGetContextPathNull() {
        HttpRouterHandler h = new HttpRouterHandler(null);
        assertEquals("/", h.getContextPath());
    }

    @Test
    public void testSseRegistration() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.sse("/events", emitter -> {});
        assertNotNull(h);
    }

    // ==================== post / ws / h2c ====================

    @Test
    public void testWsRegistration() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.ws("/chat", new io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource());
        assertNotNull(h);
    }

    @Test
    public void testH2cRegistration() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.h2c("/h2c-test");
        assertNotNull(h);
    }

    // ==================== exactRoute with methods (L117) ====================

    @Test
    public void testExactRouteWithMethods() {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute dummy = new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        // non-empty methods → new HttpMethodRoute path
        h.exactRoute("/admin", dummy, HttpMethod.GET, HttpMethod.POST);
        assertNotNull(h);
    }

    @Test
    public void testExactRouteWithEmptyMethods() {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute dummy = new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) {}
        };
        // empty methods → route.self() path
        h.exactRoute("/open", dummy);
        // Also test the 3-arg overload with empty array (L117 empty-branch)
        h.exactRoute("/open2", dummy, new HttpMethod[0]);
        assertNotNull(h);
    }

    // ==================== handle() dispatch tests ====================

    @Test
    public void testHandleExactMatch() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute route = mock(HttpRoute.class);
        when(route.self()).thenReturn(route);
        h.exactRoute("/test", route);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/test");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));

        h.handle(req, resp);
        verify(route).handle(eq("/test"), same(req), same(resp));
    }

    @Test
    public void testHandlePrefixMatch() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute route = mock(HttpRoute.class);
        when(route.self()).thenReturn(route);
        h.route("/api", route);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/api/users");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));

        h.handle(req, resp);
        verify(route).handle(eq("/api/users"), same(req), same(resp));
    }

    @Test
    public void testHandleRegexMatch() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRoute route = mock(HttpRoute.class);
        when(route.self()).thenReturn(route);
        h.route("^/user/\\d+$", route);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/user/42");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));

        h.handle(req, resp);
        verify(route).handle(eq("/user/42"), same(req), same(resp));
    }

    /** contextPath non-root, subPath not empty */
    @Test
    public void testHandleContextPathSubPathNotEmpty() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler("/app");
        HttpRoute route = mock(HttpRoute.class);
        when(route.self()).thenReturn(route);
        h.exactRoute("/hello", route);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/app/hello");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));

        h.handle(req, resp);
        verify(route).handle(eq("/hello"), same(req), same(resp));
    }

    /** contextPath non-root, subPath empty → L336-337: subPath = "/" */
    @Test
    public void testHandleContextPathSubPathEmpty() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler("/app");
        HttpRoute route = mock(HttpRoute.class);
        when(route.self()).thenReturn(route);
        h.exactRoute("/", route);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/app");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));

        h.handle(req, resp);
        verify(route).handle(eq("/"), same(req), same(resp));
    }

    /** contextPath non-root, path doesn't match → L323 false → 404 */
    @Test
    public void testHandleContextPathMismatch() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler("/app");
        HttpRoute route = mock(HttpRoute.class);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/other");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(resp.status(any())).thenReturn(resp);

        h.handle(req, resp);
        verify(resp).status(HttpStatus.NOT_FOUND);
    }

    /** contextPath non-root with autoRedirect enabled → L325-329 redirect */
    @Test
    public void testHandleAutoRedirect() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler("/app");
        // autoRedirect is true by default

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(resp.status(any())).thenReturn(resp);
        when(resp.header(anyString(), any())).thenReturn(resp);

        h.handle(req, resp);
        verify(resp).status(HttpStatus.MOVED_PERMANENTLY);
        verify(resp).header(eq("Location"), eq("/app/"));
        verify(resp).commit();
    }

    /** contextPath non-root with autoRedirect disabled → L325 false → 404 */
    @Test
    public void testHandleAutoRedirectDisabled() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler("/app");
        h.autoRedirect(false);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(resp.status(any())).thenReturn(resp);

        h.handle(req, resp);
        verify(resp).status(HttpStatus.NOT_FOUND);
    }

    // ==================== handle() health route ====================

    @Test
    public void testHandleHealthRoute() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/health");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(resp.status(any())).thenReturn(resp);

        h.handle(req, resp);
        verify(resp).contentType(HttpHeaderValues.APPLICATION_JSON_UTF8);
        verify(resp).status(HttpStatus.OK);
        verify(resp).body(any(String.class));
    }

    // ==================== handle() 404 fallback ====================

    @Test
    public void testHandleNotFoundDefault() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/no-match");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(resp.status(any())).thenReturn(resp);

        h.handle(req, resp);
        verify(resp).status(HttpStatus.NOT_FOUND);
        verify(resp).body(any(byte[].class));
    }

    @Test
    public void testHandleNotFoundCustomHandler() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRequestHandler custom404 = mock(HttpRequestHandler.class);
        h.notFoundHandler(custom404);

        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/no-match");
        HttpResponse resp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));

        h.handle(req, resp);
        verify(custom404).handle(same(req), same(resp));
    }

    // ==================== buildFullPath ====================

    @Test
    public void testBuildFullPathContextPathRootWithSlash() throws Exception {
        // contextPathLen==1, path starts with "/" → path unchanged
        HttpRouterHandler h = new HttpRouterHandler();
        java.lang.reflect.Method m = HttpRouterHandler.class.getDeclaredMethod("buildFullPath", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(h, "/info");
        assertEquals("/info", result);
    }

    @Test
    public void testBuildFullPathContextPathRootWithoutSlash() throws Exception {
        // contextPathLen==1, path doesn't start with "/" → prepend "/"
        HttpRouterHandler h = new HttpRouterHandler();
        java.lang.reflect.Method m = HttpRouterHandler.class.getDeclaredMethod("buildFullPath", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(h, "info");
        assertEquals("/info", result);
    }

    @Test
    public void testBuildFullPathWithContextPath() throws Exception {
        // contextPathLen>1, path starts with "/"
        HttpRouterHandler h = new HttpRouterHandler("/app");
        java.lang.reflect.Method m = HttpRouterHandler.class.getDeclaredMethod("buildFullPath", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(h, "/info");
        assertEquals("/app/info", result);
    }

    @Test
    public void testBuildFullPathWithContextPathNoSlash() throws Exception {
        // contextPathLen>1, path doesn't start with "/" → prepend "/"
        HttpRouterHandler h = new HttpRouterHandler("/app");
        java.lang.reflect.Method m = HttpRouterHandler.class.getDeclaredMethod("buildFullPath", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(h, "info");
        assertEquals("/app/info", result);
    }

    // ==================== RouteEntry regex ====================

    @Test
    public void testRouteEntryRegexNoDollar() {
        // pattern starting with ^ but no $ → Pattern.compile with ".*"
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("^/user/\\d+", mock(HttpRoute.class));
        assertNotNull(h);
    }

    // ==================== applyInterceptors ====================

    private static final class CallCount implements RouterInterceptor {
        int calls = 0;
        final boolean ret;
        CallCount(boolean ret) { this.ret = ret; }
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) {
            calls++;
            return ret;
        }
    }

    private boolean apply(HttpRouterHandler h, List<RouterInterceptor> chain) throws Throwable {
        java.lang.reflect.Method m = HttpRouterHandler.class.getDeclaredMethod(
                "applyInterceptors", List.class, String.class, HttpRequest.class, HttpResponse.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(h, chain, "/p", mock(HttpRequest.class), mock(HttpResponse.class));
    }

    @Test
    public void testApplyInterceptorsEmptyList() throws Throwable {
        // empty chain → true regardless of switch
        HttpRouterHandler h = new HttpRouterHandler();
        assertTrue(apply(h, new ArrayList<RouterInterceptor>()));
    }

    @Test
    public void testApplyInterceptorsDisabledShortCircuit() throws Throwable {
        // disabled switch → true, beforeHandle not invoked
        HttpRouterHandler h = new HttpRouterHandler();
        h.interceptorsDisabled(true);
        CallCount c = new CallCount(true);
        List<RouterInterceptor> chain = new ArrayList<RouterInterceptor>();
        chain.add(c);
        assertTrue(apply(h, chain));
        assertEquals(0, c.calls);
    }

    @Test
    public void testApplyInterceptorsAllPass() throws Throwable {
        // both interceptors return true → true, both invoked in order
        HttpRouterHandler h = new HttpRouterHandler();
        CallCount a = new CallCount(true);
        CallCount b = new CallCount(true);
        List<RouterInterceptor> chain = new ArrayList<RouterInterceptor>();
        chain.add(a);
        chain.add(b);
        assertTrue(apply(h, chain));
        assertEquals(1, a.calls);
        assertEquals(1, b.calls);
    }

    @Test
    public void testApplyInterceptorsShortCircuitOnFalse() throws Throwable {
        // first returns false → false, second not invoked
        HttpRouterHandler h = new HttpRouterHandler();
        CallCount a = new CallCount(false);
        CallCount b = new CallCount(true);
        List<RouterInterceptor> chain = new ArrayList<RouterInterceptor>();
        chain.add(a);
        chain.add(b);
        assertFalse(apply(h, chain));
        assertEquals(1, a.calls);
        assertEquals(0, b.calls);
    }

    // ==================== prepare() route sorting ====================
    // RouteEntry treats a path starting with '^' as a regex route (prefix=false),
    // anything else as a prefix route. Mixing both kinds is what drives the comparator.

    @Test
    public void testPrepareSortsRootPrefixLast() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("/", MockHttpTestBase.noopRoute());
        h.route("/a", MockHttpTestBase.noopRoute());
        h.route("/aaa", MockHttpTestBase.noopRoute());
        h.prepare();
        // non-root prefixes by descending length, then the "/" catch-all last
        assertEquals(Arrays.asList("/aaa", "/a", "/"), patterns(h));
    }

    @Test
    public void testPrepareMovesRootToEndWhenRootRegisteredLater() {
        // Registering "/" later makes it the comparator's LEFT operand during insertion sort,
        // which is the only way to hit "aRoot -> return 1" (the root-sinks-last direction).
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("/a", MockHttpTestBase.noopRoute());
        h.route("/", MockHttpTestBase.noopRoute());
        h.route("/aaa", MockHttpTestBase.noopRoute());
        h.prepare();
        assertEquals(Arrays.asList("/aaa", "/a", "/"), patterns(h));
    }

    @Test
    public void testPrepareSortsPrefixBeforeRegex() {
        // regex registered first -> comparator sees (prefix, regex) and returns -1
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("^/api/\\d+", MockHttpTestBase.noopRoute());
        h.route("/api", MockHttpTestBase.noopRoute());
        h.prepare();
        List<String> p = patterns(h);
        assertTrue(p.indexOf("/api") < p.indexOf("^/api/\\d+"), "prefix routes must sort before regex");
    }

    @Test
    public void testPrepareSortsPrefixBeforeRegexReversed() {
        // prefix registered first -> comparator sees (regex, prefix) and returns 1
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("/api", MockHttpTestBase.noopRoute());
        h.route("^/api/\\d+", MockHttpTestBase.noopRoute());
        h.prepare();
        List<String> p = patterns(h);
        assertTrue(p.indexOf("/api") < p.indexOf("^/api/\\d+"), "prefix routes must sort before regex");
    }

    @Test
    public void testPrepareKeepsRegexRegistrationOrder() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("^/x.*", MockHttpTestBase.noopRoute());
        h.route("^/y.*", MockHttpTestBase.noopRoute());
        h.route("^/z.*", MockHttpTestBase.noopRoute());
        h.prepare();
        // comparator returns 0 for two regex routes -> stable sort preserves registration order
        assertEquals(Arrays.asList("^/x.*", "^/y.*", "^/z.*"), patterns(h));
    }

    @Test
    public void testPrepareSortsLongerPrefixFirst() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("/user", MockHttpTestBase.noopRoute());
        h.route("/user/aaa", MockHttpTestBase.noopRoute());
        h.prepare();
        List<String> p = patterns(h);
        assertTrue(p.indexOf("/user/aaa") < p.indexOf("/user"), "longer prefix must sort first");
    }

    @Test
    public void testPrepareIsNoopForZeroOrOneRoute() {
        HttpRouterHandler empty = new HttpRouterHandler();
        empty.prepare();
        assertEquals(0, patterns(empty).size());

        HttpRouterHandler one = new HttpRouterHandler();
        one.route("/only", MockHttpTestBase.noopRoute());
        one.prepare();
        assertEquals(Arrays.asList("/only"), patterns(one));
    }

    @Test
    public void testDisableAutoSortKeepsRegistrationOrder() {
        HttpRouterHandler h = new HttpRouterHandler();
        h.route("/", MockHttpTestBase.noopRoute());
        h.route("/aaa", MockHttpTestBase.noopRoute());
        assertSame(h, h.disableAutoSort());
        // sorting would reorder this to ["/aaa", "/"]; disabled auto-sort must keep it as-is
        h.prepare();
        assertEquals(Arrays.asList("/", "/aaa"), patterns(h));
    }

    @Test
    public void testSetDefaultHealthResponse() throws Throwable {
        Field ct = HttpRouterHandler.class.getDeclaredField("defaultHealthContentType");
        Field bd = HttpRouterHandler.class.getDeclaredField("defaultHealthResponseBody");
        ct.setAccessible(true);
        bd.setAccessible(true);
        String origCt = (String) ct.get(null);
        String origBody = (String) bd.get(null);
        try {
            HttpRouterHandler.setDefaultHealthResponse("text/plain", "OK");
            assertEquals("text/plain", ct.get(null));
            assertEquals("OK", bd.get(null));

            // the built-in /health route must actually serve the new defaults
            HttpRouterHandler h = new HttpRouterHandler();
            HttpResponse res = mock(HttpResponse.class, RETURNS_SELF);
            h.handle(MockHttpTestBase.mockRequest(HttpMethod.GET, "/health"), res);
            verify(res).contentType("text/plain");
            verify(res).body("OK");
        } finally {
            // static state: always restore so other tests keep the shipped defaults
            HttpRouterHandler.setDefaultHealthResponse(origCt, origBody);
        }
    }

    /** Reads the (private) pattern of every registered route, in current order. */
    private static List<String> patterns(HttpRouterHandler h) {
        List<String> out = new ArrayList<String>();
        try {
            Field f = HttpRouterHandler.RouteEntry.class.getDeclaredField("pattern");
            f.setAccessible(true);
            for (HttpRouterHandler.RouteEntry e : h.routes) {
                out.add((String) f.get(e));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
