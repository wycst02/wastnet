package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.proxy.HttpProxyConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        HttpResponse resp = mock(HttpResponse.class);

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
        HttpResponse resp = mock(HttpResponse.class);

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
        HttpResponse resp = mock(HttpResponse.class);

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
        HttpResponse resp = mock(HttpResponse.class);

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
        HttpResponse resp = mock(HttpResponse.class);

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
        HttpResponse resp = mock(HttpResponse.class);
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
        HttpResponse resp = mock(HttpResponse.class);
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
        HttpResponse resp = mock(HttpResponse.class);
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
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.status(any())).thenReturn(resp);

        h.handle(req, resp);
        verify(resp).setHeader(eq("Content-Type"), eq("application/json; charset=utf-8"));
        verify(resp).status(HttpStatus.OK);
        verify(resp).body(any(byte[].class));
    }

    // ==================== handle() 404 fallback ====================

    @Test
    public void testHandleNotFoundDefault() throws Throwable {
        HttpRouterHandler h = new HttpRouterHandler();
        HttpRequest req = mock(HttpRequest.class);
        when(req.getRequestUri()).thenReturn("/no-match");
        HttpResponse resp = mock(HttpResponse.class);
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
        HttpResponse resp = mock(HttpResponse.class);

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
}
