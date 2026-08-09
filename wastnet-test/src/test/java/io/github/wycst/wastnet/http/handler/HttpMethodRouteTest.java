package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpMethodRoute;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpMethodRoute}.
 *
 * @author wangyc
 */
public class HttpMethodRouteTest {

    // ==================== Handler dispatch ====================

    @Test
    public void testQuickFilterDispatchesToDelegate() throws Throwable {
        final boolean[] called = {false};
        HttpRoute delegate = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                called[0] = true;
            }
        };
        HttpMethodRoute route = new HttpMethodRoute(delegate, HttpMethod.GET);
        route.handle("/test", MockHttpTestBase.mockRequest(HttpMethod.GET), null);
        Assertions.assertTrue(called[0]);
    }

    @Test
    public void testDispatcherDispatchesToCorrectHandler() throws Throwable {
        final boolean[] getCalled = {false};
        final boolean[] postCalled = {false};
        HttpMethodRoute route = new HttpMethodRoute()
                .get(new HttpRoute() {
                    @Override
                    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                        getCalled[0] = true;
                    }
                })
                .post(new HttpRoute() {
                    @Override
                    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                        postCalled[0] = true;
                    }
                });
        route.handle("/test", MockHttpTestBase.mockRequest(HttpMethod.POST), null);
        Assertions.assertFalse(getCalled[0], "GET handler should not be called for POST");
        Assertions.assertTrue(postCalled[0], "POST handler should be called for POST");
    }

    @Test
    public void testDispatcherReturns405ForUnregisteredMethod() throws Throwable {
        final HttpStatus[] capturedStatus = {null};
        final String[] capturedAllowHeader = {null};
        final byte[][] capturedBody = {null};
        HttpResponse mockResponse = MockHttpTestBase.mockResponse(capturedStatus, capturedAllowHeader, capturedBody, true);
        HttpMethodRoute route = new HttpMethodRoute().get(MockHttpTestBase.noopRoute());
        route.handle("/test", MockHttpTestBase.mockRequest(HttpMethod.DELETE), mockResponse);
        Assertions.assertEquals(HttpStatus.METHOD_NOT_ALLOWED, capturedStatus[0]);
        Assertions.assertNotNull(capturedAllowHeader[0]);
        Assertions.assertTrue(capturedAllowHeader[0].contains("GET"), capturedAllowHeader[0]);
    }

    @Test
    public void testAllowHeaderForSingleMethod() throws Throwable {
        HttpMethodRoute route = new HttpMethodRoute(createNoopRoute(), HttpMethod.GET);
        // Verify through 405 response
        final HttpStatus[] capturedStatus = {null};
        final String[] capturedHeader = {null};
        final byte[][] capturedBody = {null};
        route.handle("/", MockHttpTestBase.mockRequest(HttpMethod.POST), MockHttpTestBase.mockResponse(capturedStatus, capturedHeader, capturedBody, true));
        Assertions.assertEquals("GET", capturedHeader[0]);
    }

    @Test
    public void testAllowHeaderForMultipleMethods() throws Throwable {
        HttpMethodRoute route = new HttpMethodRoute(createNoopRoute(), HttpMethod.GET, HttpMethod.POST);
        final HttpStatus[] capturedStatus = {null};
        final String[] capturedHeader = {null};
        final byte[][] capturedBody = {null};
        route.handle("/", MockHttpTestBase.mockRequest(HttpMethod.DELETE), MockHttpTestBase.mockResponse(capturedStatus, capturedHeader, capturedBody, true));
        Assertions.assertNotNull(capturedHeader[0]);
        Assertions.assertTrue(capturedHeader[0].contains("GET"), capturedHeader[0]);
        Assertions.assertTrue(capturedHeader[0].contains("POST"), capturedHeader[0]);
    }

    @Test
    public void testAllowHeaderAfterBuilder() throws Throwable {
        HttpMethodRoute route = new HttpMethodRoute()
                .get(createNoopRoute())
                .post(createNoopRoute())
                .delete(createNoopRoute());
        final HttpStatus[] capturedStatus = {null};
        final String[] capturedHeader = {null};
        final byte[][] capturedBody = {null};
        route.handle("/", MockHttpTestBase.mockRequest(HttpMethod.PUT), MockHttpTestBase.mockResponse(capturedStatus, capturedHeader, capturedBody, true));
        Assertions.assertNotNull(capturedHeader[0]);
        Assertions.assertTrue(capturedHeader[0].contains("GET"), capturedHeader[0]);
        Assertions.assertTrue(capturedHeader[0].contains("POST"), capturedHeader[0]);
        Assertions.assertTrue(capturedHeader[0].contains("DELETE"), capturedHeader[0]);
        Assertions.assertFalse(capturedHeader[0].contains("PUT"), capturedHeader[0]);
    }

    @Test
    public void testQuickFilterDispatchesAllConfiguredMethods() throws Throwable {
        final java.util.Set<HttpMethod> calledMethods = new java.util.HashSet<HttpMethod>();
        HttpRoute delegate = new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                calledMethods.add(request.getMethod());
            }
        };
        HttpMethodRoute route = new HttpMethodRoute(delegate, HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);
        route.handle("/", MockHttpTestBase.mockRequest(HttpMethod.GET), null);
        route.handle("/", MockHttpTestBase.mockRequest(HttpMethod.POST), null);
        route.handle("/", MockHttpTestBase.mockRequest(HttpMethod.PUT), null);
        Assertions.assertEquals(3, calledMethods.size());
        Assertions.assertTrue(calledMethods.contains(HttpMethod.GET));
        Assertions.assertTrue(calledMethods.contains(HttpMethod.POST));
        Assertions.assertTrue(calledMethods.contains(HttpMethod.PUT));
    }

    @Test
    public void testHandleDispatchesToBuilderMethod() throws Throwable {
        final boolean[] handlerCalled = {false};
        HttpMethodRoute route = new HttpMethodRoute()
                .post(new HttpRoute() {
                    @Override
                    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                        handlerCalled[0] = true;
                    }
                });
        route.handle("/test", MockHttpTestBase.mockRequest(HttpMethod.POST), null);
        Assertions.assertTrue(handlerCalled[0]);
    }

        // ==================== Helpers ====================

    /** @deprecated Use {@link MockHttpTestBase#noopRoute()} instead */
    @Deprecated
    private static HttpRoute createNoopRoute() {
        return MockHttpTestBase.noopRoute();
    }

    /** @deprecated Use {@link MockHttpTestBase#mockRequest(HttpMethod)} instead */
    @Deprecated
    private static HttpRequest createMockRequest(final HttpMethod method) {
        return MockHttpTestBase.mockRequest(method);
    }

    /** @deprecated Use {@link MockHttpTestBase#mockResponse(HttpStatus[], String[], byte[], boolean)} instead */
    @Deprecated
    private static HttpResponse createMockResponse(final HttpStatus[] statusHolder,
                                                   final String[] allowHeaderHolder,
                                                   final byte[][] bodyHolder) {
        return MockHttpTestBase.mockResponse(statusHolder, allowHeaderHolder, bodyHolder, true);
    }
}
