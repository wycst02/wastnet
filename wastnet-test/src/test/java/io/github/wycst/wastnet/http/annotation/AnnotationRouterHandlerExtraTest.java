package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;
import io.github.wycst.wastnet.routerfixtures.RouterFixtures.*;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extra coverage tests for {@link AnnotationRouterHandler} gaps:
 * builder setters, path-variable / request-param / file-param routing,
 * scan-time exceptions, named-inject resolution and private static helpers.
 * Fixtures live in the {@code extratest} subpackage so the existing
 * full-package scan in {@code AnnotationPackageCoverageTest} is not disturbed.
 */
public class AnnotationRouterHandlerExtraTest {

    private static final String PKG = "io.github.wycst.wastnet.routerfixtures";

    // ==================== helpers ====================

    private static Set<Class<?>> set(Class<?>... classes) {
        return new HashSet<Class<?>>(Arrays.asList(classes));
    }

    private static AnnotationResolver acceptResolver(final Set<Class<?>> accept,
                                                     final boolean asController,
                                                     final boolean asComponent,
                                                     final boolean asConfig) {
        final DefaultAnnotationResolver def = new DefaultAnnotationResolver();
        return new AnnotationResolver() {
            public boolean accept(Class<?> c) {
                return accept.contains(c);
            }

            public boolean isController(Class<?> c) {
                return asController && def.isController(c);
            }

            public String resolveControllerPath(Class<?> c) {
                return def.resolveControllerPath(c);
            }

            public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) {
                return def.resolveEndpointRoutes(c);
            }

            public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) {
                return def.resolveSseEndpoints(c);
            }

            public boolean isWebSocketEndpoint(Class<?> c) {
                return asController && def.isWebSocketEndpoint(c);
            }

            public String resolveWebSocketPath(Class<?> c) {
                return def.resolveWebSocketPath(c);
            }

            public boolean isComponent(Class<?> c) {
                return asComponent && def.isComponent(c);
            }

            public String resolveComponentName(Class<?> c) {
                return def.resolveComponentName(c);
            }

            public boolean isConfiguration(Class<?> c) {
                return asConfig && def.isConfiguration(c);
            }

            public String resolveValueExpression(java.lang.annotation.Annotation a) {
                return def.resolveValueExpression(a);
            }

            public String resolveInjectName(java.lang.annotation.Annotation a) {
                if (a instanceof MyInject) return ((MyInject) a).value();
                return def.resolveInjectName(a);
            }

            public String resolveBeanName(java.lang.annotation.Annotation a) {
                return def.resolveBeanName(a);
            }
        };
    }

    private static HttpMessageConverter mockConverter() {
        return new HttpMessageConverter() {
            @Override
            public <T> T read(HttpRequest req, ConverterConfig cfg, Class<T> type) {
                return null;
            }

            @Override
            public void write(Object v, ConverterConfig cfg, HttpResponse resp) {
            }
        };
    }

    private static HttpRoute getExactRoute(HttpRouterHandler handler, String path) throws Exception {
        Field f = HttpRouterHandler.class.getDeclaredField("exactRoutes");
        f.setAccessible(true);
        Map<String, HttpRoute> map = (Map<String, HttpRoute>) f.get(handler);
        return map.get(path);
    }

    private static Method pm(String name, Class<?>... params) throws Exception {
        Method m = AnnotationRouterHandler.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    // ==================== builder setters (L90-91, L103-104) ====================

    @Test
    public void testBuilderChainsAll() {
        assertNotNull(new AnnotationRouterHandler()
                .requestBodyBy(RequestBody.class)
                .responseBodyBy(ResponseBody.class, RestController.class)
                .pathParamBy(PathParam.class)
                .requestParamBy(RequestParam.class)
                .valueBy(Value.class)
                .injectBy(Inject.class)
                .postConstructBy(PostConstruct.class)
                .preDestroyBy(PreDestroy.class)
                .property("k", "v"));
    }

    // ==================== runtime dispatch via real server ====================

    @Test
    public void testRuntimeDispatch() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(mockConverter())
                .annotationResolver(acceptResolver(set(PathVarController.class, RequestParamController.class,
                        FileParamController.class, SseThrowController.class), true, false, false))
                .scanPackages(PKG);

        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
        HTTPServer server = HTTPServer.of(51120).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            assertEquals(200, code(client, "http://127.0.0.1:51120/pv/items/123"));
            assertEquals(200, code(client, "http://127.0.0.1:51120/pv/user/42"));
            assertEquals(200, code(client, "http://127.0.0.1:51120/pv/name/alice"));
            // scalar (required) + multi array + multi List + defaults (x / "")
            assertEquals(200, code(client,
                    "http://127.0.0.1:51120/rp/search?q=hi&tags=a&tags=b&names=a&names=b"));
            // only q present -> tags/names/extra missing(not required), opt/e defaults covered
            assertEquals(200, code(client, "http://127.0.0.1:51120/rp/search?q=hi"));
            // file params (multipart)
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("f", "a.txt", okhttp3.RequestBody.create(MediaType.parse("text/plain"), "hello"))
                    .addFormDataPart("fs", "b.txt", okhttp3.RequestBody.create(MediaType.parse("text/plain"), "world"))
                    .build();
            Response fr = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51120/rp2/upload").post(body).build()).execute();
            fr.close();
            // SSE handler that throws -> L506-507
            Response sr = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51120/sse-throw/stream").get().build()).execute();
            sr.close();
        } finally {
            server.shutdown();
        }
    }

    private static int code(OkHttpClient client, String url) throws Exception {
        Response r = client.newCall(new Request.Builder().url(url).get().build()).execute();
        int c = r.code();
        r.close();
        return c;
    }

    // ==================== general-path @RequestBody without converter (L469 false) ====================

    @Test
    public void testGeneralBodyNoConverter() throws Throwable {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(GeneralBodyNoConvController.class), true, false, false))
                .scanPackages(PKG);
        HttpRoute route = getExactRoute(handler, "/gb/up");
        assertNotNull(route);
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        route.handle("/gb/up", req, resp);
    }

    // ==================== scan-time exceptions ====================

    @Test
    public void testPathVarMismatchThrows() {
        assertThrows(RuntimeException.class, () -> new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(PathVarMismatchController.class), true, false, false))
                .scanPackages(PKG));
    }

    @Test
    public void testMultipleInjectConstructorsThrows() {
        assertThrows(RuntimeException.class, () -> new AnnotationRouterHandler()
                .injectBy(MyInject.class)
                .annotationResolver(acceptResolver(set(MultiInjectCtorComponent.class), false, true, false))
                .scanPackages(PKG));
    }

    @Test
    public void testConfigCtorFailureThrows() {
        assertThrows(RuntimeException.class, () -> new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(FailingConfig.class), false, false, true))
                .scanPackages(PKG));
    }

    @Test
    public void testNamedInjectSuccess() {
        assertDoesNotThrow(() -> new AnnotationRouterHandler()
                .injectBy(Inject.class)
                .annotationResolver(acceptResolver(set(NamedInjectConfig.class), false, false, true))
                .scanPackages(PKG));
    }

    // ==================== private static helper methods ====================

    @Test
    public void testConvertParamValueAllTypes() throws Exception {
        Method m = pm("convertParamValue", String.class, Class.class);
        assertEquals("", m.invoke(null, null, String.class));
        assertEquals("abc", m.invoke(null, "abc", String.class));
        assertEquals(123, (Integer) m.invoke(null, "123", int.class));
        assertEquals(123, (Integer) m.invoke(null, "123", Integer.class));
        assertEquals(123L, (Long) m.invoke(null, "123", long.class));
        assertEquals(123L, (Long) m.invoke(null, "123", Long.class));
        assertEquals((short) 5, (Short) m.invoke(null, "5", short.class));
        assertEquals((short) 5, (Short) m.invoke(null, "5", Short.class));
        assertEquals((byte) 7, (Byte) m.invoke(null, "7", byte.class));
        assertEquals((byte) 7, (Byte) m.invoke(null, "7", Byte.class));
        assertEquals(true, (Boolean) m.invoke(null, "true", boolean.class));
        assertEquals(true, (Boolean) m.invoke(null, "true", Boolean.class));
        assertEquals(1.5d, (Double) m.invoke(null, "1.5", double.class), 0.001);
        assertEquals(1.5d, (Double) m.invoke(null, "1.5", Double.class), 0.001);
        assertEquals(2.5f, (Float) m.invoke(null, "2.5", float.class), 0.001f);
        assertEquals(2.5f, (Float) m.invoke(null, "2.5", Float.class), 0.001f);
        // unknown type -> returns value as-is
        assertEquals("foo", m.invoke(null, "foo", Object.class));
    }

    @Test
    public void testResolveParamElementType() throws Exception {
        Method m = pm("resolveParamElementType", java.lang.reflect.Parameter.class, Class.class);
        Method arrM = TypeHolder.class.getMethod("arrayMethod", String[].class);
        assertEquals(String.class, m.invoke(null, arrM.getParameters()[0], String[].class));
        Method listM = TypeHolder.class.getMethod("listMethod", List.class);
        assertEquals(String.class, m.invoke(null, listM.getParameters()[0], List.class));
        Method rawM = TypeHolder.class.getMethod("rawListMethod", List.class);
        assertEquals(String.class, m.invoke(null, rawM.getParameters()[0], List.class));
        Method strM = TypeHolder.class.getMethod("stringMethod", String.class);
        assertEquals(String.class, m.invoke(null, strM.getParameters()[0], String.class));
    }

    @Test
    public void testReadStringAttr() throws Exception {
        Method m = pm("readStringAttr", java.lang.annotation.Annotation.class, String.class, String.class);
        Method pm2 = AttrHolder.class.getMethod("params", String.class, String.class);
        RequestParam rpAnn = pm2.getParameters()[0].getAnnotation(RequestParam.class);
        assertEquals("name", m.invoke(null, rpAnn, "value", "def"));
        java.lang.annotation.Annotation nv = pm2.getParameters()[1].getAnnotation(NoValue.class);
        assertEquals("def", m.invoke(null, nv, "value", "def"));
    }

    @Test
    public void testReadBooleanAttr() throws Exception {
        Method m = pm("readBooleanAttr", java.lang.annotation.Annotation.class, String.class, boolean.class);
        Method pm2 = AttrHolder.class.getMethod("params", String.class, String.class);
        RequestParam rpAnn = pm2.getParameters()[0].getAnnotation(RequestParam.class);
        assertEquals(true, (Boolean) m.invoke(null, rpAnn, "required", true));
        java.lang.annotation.Annotation nv = pm2.getParameters()[1].getAnnotation(NoValue.class);
        assertEquals(false, (Boolean) m.invoke(null, nv, "required", false));
    }

    @Test
    public void testSegmentAt() throws Exception {
        Method m = pm("segmentAt", String.class, int.class);
        assertEquals("a", m.invoke(null, "/a/b/c", 1));
        assertEquals("b", m.invoke(null, "/a/b/c", 2));
        assertEquals("c", m.invoke(null, "/a/b/c", 3));
        assertEquals("", m.invoke(null, "/a", 5));
        assertEquals("x", m.invoke(null, "/x", 1));
    }

    @Test
    public void testParsePathVar() throws Exception {
        Method m = pm("parsePathVar", String.class);
        assertArrayEquals(new String[]{"id", null}, (String[]) m.invoke(null, "${id}"));
        assertArrayEquals(new String[]{"id", null}, (String[]) m.invoke(null, "{id}"));
        assertArrayEquals(new String[]{"id", "regex"}, (String[]) m.invoke(null, "{id:regex}"));
        assertNull(m.invoke(null, "{}"));
        assertNull(m.invoke(null, "plain"));
        assertNull(m.invoke(null, "x{y}"));
        assertNull(m.invoke(null, "${}"));
    }

    @Test
    public void testToCollection() throws Exception {
        Method m = pm("toCollection", Class.class, Object[].class);
        Object[] arr = {"a", "b"};
        assertTrue(((Set) m.invoke(null, Set.class, arr)) instanceof Set);
        assertTrue(((Set) m.invoke(null, SortedSet.class, arr)) instanceof Set);
        assertTrue(((List) m.invoke(null, List.class, arr)) instanceof List);
    }

    private static void assertArgThrow(Method m, Object... args) {
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, args));
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void testResolveRequestParamThrows() throws Exception {
        Method m = pm("resolveRequestParam", HttpRequest.class, String.class, Class.class,
                Class.class, boolean.class, String.class, boolean.class, boolean.class);
        HttpRequest req = mock(HttpRequest.class);
        // scalar required missing -> throw
        when(req.getParameter("x")).thenReturn(null);
        assertArgThrow(m, req, "x", String.class, String.class, true, "", false, false);
        // multi required missing -> throw
        when(req.getParameterValues("y")).thenReturn(null);
        assertArgThrow(m, req, "y", String.class, String.class, true, "", true, false);
        // scalar with default
        when(req.getParameter("z")).thenReturn(null);
        assertEquals("d", m.invoke(null, req, "z", String.class, String.class, false, "d", false, false));
        // multi missing but not required -> return null (L673)
        when(req.getParameterValues("m")).thenReturn(null);
        assertNull(m.invoke(null, req, "m", String[].class, String.class, false, "", false, true));
    }

    @Test
    public void testResolveFileParam() throws Exception {
        Method m = pm("resolveFileParam", HttpRequest.class, String.class, Class.class, boolean.class);
        HttpRequest req = mock(HttpRequest.class);
        MultipartField f = mock(MultipartField.class);
        // single present
        when(req.getMultipartField("f")).thenReturn(f);
        assertSame(f, m.invoke(null, req, "f", MultipartField.class, false));
        // single missing required -> throw
        when(req.getMultipartField("f")).thenReturn(null);
        assertArgThrow(m, req, "f", MultipartField.class, true);
        // multi present
        when(req.getMultipartFields("fs")).thenReturn(Arrays.asList(f));
        assertNotNull(m.invoke(null, req, "fs", MultipartField[].class, false));
        // multi missing but not required -> return null (L697)
        when(req.getMultipartFields("fs")).thenReturn(null);
        assertNull(m.invoke(null, req, "fs", MultipartField[].class, false));
        // multi missing required -> throw
        when(req.getMultipartFields("fs")).thenReturn(null);
        assertArgThrow(m, req, "fs", MultipartField[].class, true);
    }

    // ==================== interceptor registration / resolution ====================

    @Interceptor(order = 2)
    public static class PreRouteA implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    @Interceptor(order = 1)
    public static class PreRouteB implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    public static class NotAnInterceptor {
    }

    @Interceptor(type = InterceptorType.ENDPOINT, order = 5)
    public static class EndpointC implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    @Interceptor(disabled = true)
    public static class DisabledD implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    @Interceptor(type = InterceptorType.ENDPOINT, order = 1)
    public static class EndpointE implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    @Interceptor(type = InterceptorType.ENDPOINT, order = 3)
    public static class EndpointF implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    private static BeanContainer beanContainerOf(AnnotationRouterHandler h) throws Exception {
        Field f = AnnotationRouterHandler.class.getDeclaredField("beanContainer");
        f.setAccessible(true);
        return (BeanContainer) f.get(h);
    }

    private static List<RouterInterceptor> parentInterceptorsOf(AnnotationRouterHandler h) throws Exception {
        Field f = HttpRouterHandler.class.getDeclaredField("interceptors");
        f.setAccessible(true);
        return (List<RouterInterceptor>) f.get(h);
    }

    private static Method mOf(Class<?> clazz, String name, Class<?>... params) throws Exception {
        Method m = clazz.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    @Test
    public void testRegisterInterceptorsFiltersAndOrders() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        bc.register("preA", new PreRouteA());
        bc.register("preB", new PreRouteB());
        bc.register("plain", new NotAnInterceptor());
        bc.register("endC", new EndpointC());
        bc.register("disD", new DisabledD());

        mOf(AnnotationRouterHandler.class, "registerInterceptors").invoke(h);
        List<RouterInterceptor> chain = parentInterceptorsOf(h);

        // only PRE_ROUTE, non-disabled @Interceptor beans are registered
        assertEquals(2, chain.size());
        // ordered by @Interceptor.order(): B(1) before A(2)
        assertSame(chain.get(0), bc.getBean(PreRouteB.class));
        assertSame(chain.get(1), bc.getBean(PreRouteA.class));
    }

    @Test
    public void testRegisterInterceptorsDisabledShortCircuit() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.interceptorsDisabled(true);
        BeanContainer bc = beanContainerOf(h);
        bc.register("preA", new PreRouteA());
        mOf(AnnotationRouterHandler.class, "registerInterceptors").invoke(h);
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    @Test
    public void testRegisterInterceptorsNoPreRouteKeepsEmptyChain() throws Exception {
        // only non-PRE_ROUTE beans -> chain stays null -> the "if (chain == null) return" branch
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        bc.register("endC", new EndpointC());
        bc.register("disD", new DisabledD());
        bc.register("plain", new NotAnInterceptor());
        mOf(AnnotationRouterHandler.class, "registerInterceptors").invoke(h);
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    // a RouterInterceptor without @Interceptor annotation -> skipped (ann == null branch)
    public static class NoAnnotationInterceptor implements RouterInterceptor {
        @Override
        public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) { return true; }
    }

    @Test
    public void testRegisterInterceptorsSkipsNoAnnotationBean() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        bc.register("noAnn", new NoAnnotationInterceptor());
        mOf(AnnotationRouterHandler.class, "registerInterceptors").invoke(h);
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    @Test
    public void testRegisterInterceptorsEmptyBeans() throws Exception {
        // no beans registered -> beans.isEmpty() branch in registerInterceptors
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        mOf(AnnotationRouterHandler.class, "registerInterceptors").invoke(h);
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    @Test
    public void testResolveEndpointInterceptorsOrdersByAnnotation() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        // register ENDPOINT interceptors with different orders
        bc.register("endE", new EndpointE()); // order 1
        bc.register("endF", new EndpointF()); // order 3
        bc.register("preA", new PreRouteA()); // PRE_ROUTE, must be skipped here

        Method m = mOf(AnnotationRouterHandler.class, "resolveEndpointInterceptors", MethodRouteInfo.class);
        MethodRouteInfo info = new MethodRouteInfo("/x", new HttpMethod[]{HttpMethod.GET},
                AnnotationRouterHandlerExtraTest.class.getDeclaredMethod("testResolveEndpointInterceptorsOrdersByAnnotation"));
        info.setInterceptorNames(new String[]{"endF", "endE"});
        List<RouterInterceptor> resolved = (List<RouterInterceptor>) m.invoke(h, info);

        // deduplicated & ordered by @Interceptor.order(): E(1) before F(3)
        assertEquals(2, resolved.size());
        assertSame(resolved.get(0), bc.getBean(EndpointE.class));
        assertSame(resolved.get(1), bc.getBean(EndpointF.class));

        // PRE_ROUTE bean referenced by name is skipped at endpoint scope
        info.setInterceptorNames(new String[]{"preA"});
        List<RouterInterceptor> none = (List<RouterInterceptor>) m.invoke(h, info);
        assertTrue(none.isEmpty());
    }

    @Test
    public void testResolveEndpointInterceptorsEmptyBranches() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        Method m = mOf(AnnotationRouterHandler.class, "resolveEndpointInterceptors", MethodRouteInfo.class);

        // switch disabled -> empty
        h.interceptorsDisabled(true);
        MethodRouteInfo info = new MethodRouteInfo("/x", new HttpMethod[]{HttpMethod.GET},
                AnnotationRouterHandlerExtraTest.class.getDeclaredMethod("testResolveEndpointInterceptorsEmptyBranches"));
        assertTrue(((List<?>) m.invoke(h, info)).isEmpty());

        // names == null -> empty
        h.interceptorsDisabled(false);
        info.setInterceptorNames(null);
        assertTrue(((List<?>) m.invoke(h, info)).isEmpty());
    }

    @Test
    public void testSortByOrder() throws Exception {
        List<RouterInterceptor> chain = new ArrayList<RouterInterceptor>();
        chain.add(new PreRouteA()); // order 2
        chain.add(new PreRouteB()); // order 1
        mOf(AnnotationRouterHandler.class, "sortByOrder", List.class).invoke(null, chain);
        // order: B(1) then A(2)
        assertEquals(2, chain.size());
        assertTrue(chain.get(0) instanceof PreRouteB);
        assertTrue(chain.get(1) instanceof PreRouteA);
    }
}
