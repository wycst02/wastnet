package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.withSettings;

/**
 * tests for HttpRouterHandler and HttpResourceRoute.
 */
class HttpHandlerTest {

    private HttpRouterHandler router;
    private HttpRequest mockReq;
    private HttpResponse mockResp;

    @BeforeEach
    void setUp() {
        router = new HttpRouterHandler();
        mockReq = mock(HttpRequest.class);
        mockResp = mock(HttpResponse.class, withSettings().defaultAnswer(RETURNS_SELF));
    }

    // ==================== HttpRouterHandler constructor ====================

    @Test
    void testConstructorDefault() {
        assertEquals("/", new HttpRouterHandler().getContextPath());
    }

    @Test
    void testConstructorNullTreatedAsRoot() {
        assertEquals("/", new HttpRouterHandler(null).getContextPath());
    }

    @Test
    void testConstructorEmptyTreatedAsRoot() {
        assertEquals("/", new HttpRouterHandler("  ").getContextPath());
    }

    @Test
    void testConstructorPrependsSlash() {
        assertEquals("/api", new HttpRouterHandler("api").getContextPath());
    }

    @Test
    void testConstructorStripsTrailingSlash() {
        assertEquals("/api", new HttpRouterHandler("/api//").getContextPath());
    }

    // ==================== Route registration ====================

    @Test
    void testExactRoute() {
        HttpRoute route = mock(HttpRoute.class);
        assertSame(router, router.exactRoute("/test", route));
    }

    @Test
    void testRoute() {
        HttpRoute route = mock(HttpRoute.class);
        assertSame(router, router.route("/prefix", route));
    }

    @Test
    void testRouteWithMethods() {
        HttpRoute route = mock(HttpRoute.class);
        assertSame(router, router.route("/api", route, HttpMethod.GET, HttpMethod.POST));
    }

    @Test
    void testGetShortcut() {
        HttpRoute route = mock(HttpRoute.class);
        assertSame(router, router.get("/get", route));
    }

    @Test
    void testPostShortcut() {
        HttpRoute route = mock(HttpRoute.class);
        assertSame(router, router.post("/post", route));
    }

    // ==================== Handle routing ====================

    @Test
    void testHandleExactMatch() throws Throwable {
        HttpRoute route = mock(HttpRoute.class);
        when(route.target()).thenReturn(route);
        router.exactRoute("/test", route);
        when(mockReq.getRequestUri()).thenReturn("/test");
        router.handle(mockReq, mockResp);
        verify(route).handle(eq("/test"), eq(mockReq), eq(mockResp));
    }

    @Test
    void testHandlePrefixMatch() throws Throwable {
        HttpRoute route = mock(HttpRoute.class);
        when(route.target()).thenReturn(route);
        router.route("/api", route);
        when(mockReq.getRequestUri()).thenReturn("/api/users");
        router.handle(mockReq, mockResp);
        verify(route).handle(eq("/api/users"), eq(mockReq), eq(mockResp));
    }

    @Test
    void testHandleHealthRoute() throws Throwable {
        when(mockReq.getRequestUri()).thenReturn("/health");
        when(mockResp.header(anyString(), any())).thenReturn(mockResp);
        when(mockResp.status(any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
        verify(mockResp).status(HttpStatus.OK);
    }

    @Test
    void testHandleCustomHealthRoute() throws Throwable {
        router.healthRoute("/status");
        when(mockReq.getRequestUri()).thenReturn("/status");
        when(mockResp.header(anyString(), any())).thenReturn(mockResp);
        when(mockResp.status(any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
        verify(mockResp).status(HttpStatus.OK);
    }

    @Test
    void testHandleHealthNullDisabled() throws Throwable {
        router.healthRoute((String) null);
        when(mockReq.getRequestUri()).thenReturn("/health");
        when(mockResp.status(any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
    }

    @Test
    void testHandleNotFound() throws Throwable {
        when(mockReq.getRequestUri()).thenReturn("/nonexistent");
        when(mockResp.status(any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
        verify(mockResp).status(HttpStatus.NOT_FOUND);
    }

    @Test
    void testHandleNotFoundWithCustomHandler() throws Throwable {
        HttpRequestHandler nfHandler = mock(HttpRequestHandler.class);
        router.notFoundHandler(nfHandler);
        when(mockReq.getRequestUri()).thenReturn("/nonexistent");
        router.handle(mockReq, mockResp);
        verify(nfHandler).handle(mockReq, mockResp);
    }

    // ==================== Context path routing ====================

    @Test
    void testHandleWithContextPath() throws Throwable {
        router = new HttpRouterHandler("/app");
        HttpRoute route = mock(HttpRoute.class);
        when(route.target()).thenReturn(route);
        router.exactRoute("/test", route);
        when(mockReq.getRequestUri()).thenReturn("/app/test");
        router.handle(mockReq, mockResp);
        verify(route).handle(eq("/test"), eq(mockReq), eq(mockResp));
    }

    @Test
    void testHandleContextPathMismatchReturns404() throws Throwable {
        router = new HttpRouterHandler("/app");
        when(mockReq.getRequestUri()).thenReturn("/other/path");
        when(mockResp.status(any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
        verify(mockResp).status(HttpStatus.NOT_FOUND);
    }

    @Test
    void testHandleContextPathAutoRedirect() throws Throwable {
        router = new HttpRouterHandler("/app");
        when(mockReq.getRequestUri()).thenReturn("/");
        when(mockResp.status(any())).thenReturn(mockResp);
        when(mockResp.header(anyString(), any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
        verify(mockResp).status(HttpStatus.MOVED_PERMANENTLY);
    }

    @Test
    void testHandleContextPathAutoRedirectDisabled() throws Throwable {
        router = new HttpRouterHandler("/app");
        router.autoRedirect(false);
        when(mockReq.getRequestUri()).thenReturn("/");
        when(mockResp.status(any())).thenReturn(mockResp);
        router.handle(mockReq, mockResp);
        verify(mockResp).status(HttpStatus.NOT_FOUND);
    }

    // ==================== WS / H2C with context path ====================

    @Test
    void testWsWithContextPath() {
        router = new HttpRouterHandler("/app");
        WebSocketResource res = new WebSocketResource();
        assertSame(res, router.ws("/chat", res));
    }

    @Test
    void testH2cWithContextPath() {
        router = new HttpRouterHandler("/app");
        router.h2c("/h2c");
    }

    // ==================== HttpResourceRoute ====================

    @Test
    void testResourceHandlerEarlyHints() throws Throwable {
        HttpResourceRoute h = new HttpResourceRoute("/", ".");
        h.earlyHints("</style.css>; rel=preload", "</script.js>; rel=preload");
    }

    @Test
    void testResourceHandlerEarlyHintsNull() {
        HttpResourceRoute h = new HttpResourceRoute("/", ".");
        h.earlyHints((String[]) null);
    }

    @Test
    void testResourceHandlerEarlyHintsEmpty() {
        HttpResourceRoute h = new HttpResourceRoute("/", ".");
        h.earlyHints(new String[0]);
    }

    @Test
    void testResourceHandlerStrictModeRejectsPost() throws Throwable {
        when(mockReq.getMethod()).thenReturn(HttpMethod.POST);
        when(mockResp.status(any())).thenReturn(mockResp);
        when(mockResp.header(anyString(), any())).thenReturn(mockResp);
        HttpResourceRoute h = new HttpResourceRoute("/", ".");
        h.handle("/", mockReq, mockResp);
        verify(mockResp).status(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void testResourceHandlerAllowAllMethods() throws Throwable {
        when(mockReq.getMethod()).thenReturn(HttpMethod.POST);
        when(mockResp.status(any())).thenReturn(mockResp);
        HttpResourceRoute h = new HttpResourceRoute("/", ".");
        h.allowAllMethods();
        h.handle("/", mockReq, mockResp);
        // Not a 405 = strict mode bypassed
    }

    @Test
    void testResourceHandlerSetBasePath() {
        HttpResourceRoute h = new HttpResourceRoute("/static", ".");
        h.earlyHints("<$base_path/style.css>");
        h.basePath("/app");
    }

    @Test
    void testResourceHandlerNotFound() throws Throwable {
        when(mockReq.getMethod()).thenReturn(HttpMethod.GET);
        when(mockResp.status(any())).thenReturn(mockResp);
        HttpResourceRoute h = new HttpResourceRoute("/", "/nonexistent_dir_xyz");
        h.handle("/missing.html", mockReq, mockResp);
        verify(mockResp).status(HttpStatus.NOT_FOUND);
    }

    @Test
    void testResourceHandlerPathTraversalRejected() throws Throwable {
        when(mockReq.getMethod()).thenReturn(HttpMethod.GET);
        when(mockResp.status(any())).thenReturn(mockResp);
        HttpResourceRoute h = new HttpResourceRoute("/", ".");
        h.handle("/../etc/passwd", mockReq, mockResp);
        verify(mockResp).status(HttpStatus.NOT_FOUND);
    }

    // ==================== Proxy registration ====================

    @Test
    void testProxySimple() {
        router.proxy("/api", "http://backend:8080");
    }

    @Test
    void testProxyWithRewrite() {
        router.proxy("/api", "http://backend:8080", true);
    }

    // ==================== Clear ====================

    @Test
    void testClear() {
        router.route("/test", mock(HttpRoute.class));
        router.clear();
    }

}
