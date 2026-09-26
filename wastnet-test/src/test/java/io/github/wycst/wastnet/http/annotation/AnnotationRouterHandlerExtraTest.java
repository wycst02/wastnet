package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;
import io.github.wycst.wastnet.routerfixtures.RouterFixtures.*;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extra tests for {@link AnnotationRouterHandler} gaps:
 * builder setters, path-variable / request-param / file-param routing,
 * scan-time exceptions, named-inject resolution and private static helpers.
 * Fixtures live in the {@code extratest} subpackage so the existing
 * full-package scan in {@code AnnotationPackageTest} is not disturbed.
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

            public String resolveValueExpression(Annotation a) {
                return def.resolveValueExpression(a);
            }

            public String resolveInjectName(Annotation a) {
                if (a instanceof MyInject) return ((MyInject) a).value();
                return def.resolveInjectName(a);
            }

            public String resolveBeanName(Annotation a) {
                if (a instanceof MyBean) return ((MyBean) a).value();
                return def.resolveBeanName(a);
            }
        };
    }

    private static HttpMessageConverter mockConverter() {
        return new HttpMessageConverter() {
            @Override
            public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) {
                return null;
            }

            @Override
            public void write(Object v, ConverterConfig cfg, HttpResponse resp) {
            }
        };
    }

    // read() returns a non-null value so assertBodyAssignable takes the value != null branch (L78).
    private static HttpMessageConverter nonNullConverter() {
        return new HttpMessageConverter() {
            @Override
            public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) {
                return "hello";
            }

            @Override
            public void write(Object v, ConverterConfig cfg, HttpResponse resp) {
            }
        };
    }

    private static HttpRoute getExactRoute(AnnotationRouterHandler handler, String path) {
        return handler.exactRoutes().get(path);
    }

    // ==================== builder setters (, ) ====================

    @Test
    public void testBuilderChainsAll() {
        assertNotNull(new AnnotationRouterHandler()
                .requestBodyBy(RequestBody.class)
                .responseBodyBy(ResponseBody.class, RestController.class)
                .pathParamBy(PathParam.class)
                .requestParamBy(RequestParam.class)
                .valueBy(Value.class)
                .injectBy(Inject.class)
                .beanBy(Bean.class)
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
            // SSE handler that throws -> 
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

    // ==================== general-path @RequestBody without converter ( false) ====================

    @Test
    public void testGeneralBodyNoConverter() throws Throwable {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(GeneralBodyNoConvController.class), true, false, false))
                .scanPackages(PKG);
        handler.scan();
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
                .scanPackages(PKG)
                .scan());
    }

    @Test
    public void testMultipleInjectConstructorsThrows() {
        assertThrows(RuntimeException.class, () -> new AnnotationRouterHandler()
                .injectBy(MyInject.class)
                .annotationResolver(acceptResolver(set(MultiInjectCtorComponent.class), false, true, false))
                .scanPackages(PKG)
                .scan());
    }

    @Test
    public void testConfigCtorFailureThrows() {
        assertThrows(RuntimeException.class, () -> new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(FailingConfig.class), false, false, true))
                .scanPackages(PKG)
                .scan());
    }

    @Test
    public void testNamedInjectSuccess() {
        assertDoesNotThrow(() -> new AnnotationRouterHandler()
                .injectBy(Inject.class)
                .annotationResolver(acceptResolver(set(NamedInjectConfig.class), false, false, true))
                .scanPackages(PKG));
    }

    // ==================== private static helper methods ====================

    // ==================== request-param binding via the PUBLIC API ====================
    // Drives the full scan -> bind -> invoke path through a real HTTP server + client instead of
    // calling internal converters directly. Covers #5 (optional primitive binds to 0, not 500) and
    // #6 (BigDecimal / enum conversion), plus defaultValue and optional boxed -> null.

    @Test
    public void testRequestParamConversionViaPublicApi() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(ConvertController.class), true, false, false))
                .scanPackages(PKG);
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        HTTPServer server = HTTPServer.of(51109).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            // #5: optional primitive missing -> binds to 0 (no 500)
            assertEquals("int=0", bodyOf(client, "http://127.0.0.1:51109", "/conv/int"));
            assertEquals("int=5", bodyOf(client, "http://127.0.0.1:51109", "/conv/int?v=5"));
            // optional boxed missing -> null
            assertEquals("intbox=null", bodyOf(client, "http://127.0.0.1:51109", "/conv/intbox"));
            // #6 BigDecimal conversion
            assertEquals("bd=12.3", bodyOf(client, "http://127.0.0.1:51109", "/conv/bd?v=12.3"));
            // #6 enum conversion
            assertEquals("enum=ACTIVE", bodyOf(client, "http://127.0.0.1:51109", "/conv/enum?v=ACTIVE"));
            // defaultValue applied
            assertEquals("def=9", bodyOf(client, "http://127.0.0.1:51109", "/conv/def"));
        } finally {
            server.shutdown();
        }
    }

    private static String bodyOf(OkHttpClient client, String base, String path) throws Exception {
        Response r = client.newCall(new Request.Builder().url(base + path).get().build()).execute();
        try {
            assertEquals(200, r.code(), "unexpected status for " + path);
            return r.body().string();
        } finally {
            r.close();
        }
    }

    private static int codeOf(OkHttpClient client, String base, String path) throws Exception {
        Response r = client.newCall(new Request.Builder().url(base + path).get().build()).execute();
        try {
            return r.code();
        } finally {
            r.close();
        }
    }

    @Test
    public void testResolveParamElementType() throws Exception {
        Method arrM = TypeHolder.class.getMethod("arrayMethod", String[].class);
        assertEquals(String.class, AnnotationRouteUtils.resolveParamElementType(arrM.getParameters()[0], String[].class));
        Method listM = TypeHolder.class.getMethod("listMethod", List.class);
        assertEquals(String.class, AnnotationRouteUtils.resolveParamElementType(listM.getParameters()[0], List.class));
        Method rawM = TypeHolder.class.getMethod("rawListMethod", List.class);
        assertEquals(String.class, AnnotationRouteUtils.resolveParamElementType(rawM.getParameters()[0], List.class));
        Method strM = TypeHolder.class.getMethod("stringMethod", String.class);
        assertEquals(String.class, AnnotationRouteUtils.resolveParamElementType(strM.getParameters()[0], String.class));
    }

    @Test
    public void testReadStringAttr() throws Exception {
        Method pm2 = AttrHolder.class.getMethod("params", String.class, String.class);
        RequestParam rpAnn = pm2.getParameters()[0].getAnnotation(RequestParam.class);
        assertEquals("name", AnnotationRouteUtils.readStringAttr(rpAnn, "value", "def"));
        Annotation nv = pm2.getParameters()[1].getAnnotation(NoValue.class);
        assertEquals("def", AnnotationRouteUtils.readStringAttr(nv, "value", "def"));
    }

    @Test
    public void testReadBooleanAttr() throws Exception {
        Method pm2 = AttrHolder.class.getMethod("params", String.class, String.class);
        RequestParam rpAnn = pm2.getParameters()[0].getAnnotation(RequestParam.class);
        assertEquals(true, AnnotationRouteUtils.readBooleanAttr(rpAnn, "required", true));
        Annotation nv = pm2.getParameters()[1].getAnnotation(NoValue.class);
        assertEquals(false, AnnotationRouteUtils.readBooleanAttr(nv, "required", false));
    }

    @Test
    public void testSegmentAt() {
        assertEquals("a", AnnotationRouteUtils.segmentAt("/a/b/c", 1));
        assertEquals("b", AnnotationRouteUtils.segmentAt("/a/b/c", 2));
        assertEquals("c", AnnotationRouteUtils.segmentAt("/a/b/c", 3));
        assertEquals("", AnnotationRouteUtils.segmentAt("/a", 5));
        assertEquals("x", AnnotationRouteUtils.segmentAt("/x", 1));
        // no leading slash -> start stays -1 -> "" (L224 start<0 branch)
        assertEquals("", AnnotationRouteUtils.segmentAt("", 1));
        assertEquals("", AnnotationRouteUtils.segmentAt("abc", 2));
    }

    @Test
    public void testParsePathVar() {
        assertArrayEquals(new String[]{"id", null}, AnnotationRouteUtils.parsePathVar("${id}"));
        assertArrayEquals(new String[]{"id", null}, AnnotationRouteUtils.parsePathVar("{id}"));
        assertArrayEquals(new String[]{"id", "regex"}, AnnotationRouteUtils.parsePathVar("{id:regex}"));
        assertNull(AnnotationRouteUtils.parsePathVar("{}"));
        assertNull(AnnotationRouteUtils.parsePathVar("plain"));
        assertNull(AnnotationRouteUtils.parsePathVar("x{y}"));
        assertNull(AnnotationRouteUtils.parsePathVar("${}"));
        // "${id" -> dbl startsWith("${") true but endsWith("}") false (L231 T/F); "{id" -> sgl startsWith("{") true but endsWith("}") false (L232)
        assertNull(AnnotationRouteUtils.parsePathVar("${id"));
        assertNull(AnnotationRouteUtils.parsePathVar("{id"));
    }

    @Test
    public void testToCollection() {
        Object[] arr = {"a", "b"};
        assertTrue(AnnotationRouteUtils.toCollection(Set.class, arr) instanceof Set);
        assertTrue(AnnotationRouteUtils.toCollection(SortedSet.class, arr) instanceof Set);
        assertTrue(AnnotationRouteUtils.toCollection(List.class, arr) instanceof List);
    }



    @Test
    public void testRequestParamRequiredViaPublicApi() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(ConvertController.class), true, false, false))
                .scanPackages(PKG);
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        HTTPServer server = HTTPServer.of(51110).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            // required scalar missing -> 400 (caught by the binding try/catch)
            assertEquals(400, codeOf(client, "http://127.0.0.1:51110", "/conv/req"));
            assertEquals("req=x", bodyOf(client, "http://127.0.0.1:51110", "/conv/req?v=x"));
            // required multi missing -> 400
            assertEquals(400, codeOf(client, "http://127.0.0.1:51110", "/conv/reqmulti"));
            assertEquals(200, codeOf(client, "http://127.0.0.1:51110", "/conv/reqmulti?t=a&t=b"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testResolveFileParam() {
        HttpRequest req = mock(HttpRequest.class);
        MultipartField f = mock(MultipartField.class);
        // single present
        when(req.getMultipartField("f")).thenReturn(f);
        assertSame(f, AnnotationRouteUtils.resolveFileParam(req, "f", MultipartField.class, false));
        // single present AND required -> returns field without throwing (L197 field!=null && required branch)
        assertSame(f, AnnotationRouteUtils.resolveFileParam(req, "f", MultipartField.class, true));
        // single missing required -> throw
        when(req.getMultipartField("f")).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> AnnotationRouteUtils.resolveFileParam(req, "f", MultipartField.class, true));
        // multi present
        when(req.getMultipartFields("fs")).thenReturn(Arrays.asList(f));
        assertNotNull(AnnotationRouteUtils.resolveFileParam(req, "fs", MultipartField[].class, false));
        // multi missing but not required -> return null 
        when(req.getMultipartFields("fs")).thenReturn(null);
        assertNull(AnnotationRouteUtils.resolveFileParam(req, "fs", MultipartField[].class, false));
        // multi missing required -> throw
        when(req.getMultipartFields("fs")).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> AnnotationRouteUtils.resolveFileParam(req, "fs", MultipartField[].class, true));
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

    private static BeanContainer beanContainerOf(AnnotationRouterHandler h) {
        return h.beanContainer;
    }

    private static List<RouterInterceptor> parentInterceptorsOf(AnnotationRouterHandler h) {
        return h.interceptors();
    }

    @Test
    public void testRegisterInterceptorsFiltersAndOrders() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        bc.register("preA", new PreRouteA(), false);
        bc.register("preB", new PreRouteB(), false);
        bc.register("plain", new NotAnInterceptor(), false);
        bc.register("endC", new EndpointC(), false);
        bc.register("disD", new DisabledD(), false);

        h.registerInterceptors();
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
        bc.register("preA", new PreRouteA(), false);
        h.registerInterceptors();
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    @Test
    public void testRegisterInterceptorsNoPreRouteKeepsEmptyChain() throws Exception {
        // only non-PRE_ROUTE beans -> chain stays null -> the "if (chain == null) return" branch
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        bc.register("endC", new EndpointC(), false);
        bc.register("disD", new DisabledD(), false);
        bc.register("plain", new NotAnInterceptor(), false);
        h.registerInterceptors();
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
        bc.register("noAnn", new NoAnnotationInterceptor(), false);
        h.registerInterceptors();
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    @Test
    public void testRegisterInterceptorsEmptyBeans() throws Exception {
        // no beans registered -> beans.isEmpty() branch in registerInterceptors
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.registerInterceptors();
        assertTrue(parentInterceptorsOf(h).isEmpty());
    }

    @Test
    public void testResolveEndpointInterceptorsOrdersByAnnotation() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        // register ENDPOINT interceptors with different orders
        bc.register("endE", new EndpointE(), false); // order 1
        bc.register("endF", new EndpointF(), false); // order 3
        bc.register("preA", new PreRouteA(), false); // PRE_ROUTE, must be skipped here

        MethodRouteInfo info = new MethodRouteInfo("/x", new HttpMethod[]{HttpMethod.GET},
                AnnotationRouterHandlerExtraTest.class.getDeclaredMethod("testResolveEndpointInterceptorsOrdersByAnnotation"));
        info.setInterceptorNames(new String[]{"endF", "endE"});
        List<RouterInterceptor> resolved = h.resolveEndpointInterceptors(info);

        // deduplicated & ordered by @Interceptor.order(): E(1) before F(3)
        assertEquals(2, resolved.size());
        assertSame(resolved.get(0), bc.getBean(EndpointE.class));
        assertSame(resolved.get(1), bc.getBean(EndpointF.class));

        // PRE_ROUTE bean referenced by name is skipped at endpoint scope
        info.setInterceptorNames(new String[]{"preA"});
        List<RouterInterceptor> none = h.resolveEndpointInterceptors(info);
        assertTrue(none.isEmpty());
    }

    @Test
    public void testResolveEndpointInterceptorsEmptyBranches() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        // switch disabled -> empty
        h.interceptorsDisabled(true);
        MethodRouteInfo info = new MethodRouteInfo("/x", new HttpMethod[]{HttpMethod.GET},
                AnnotationRouterHandlerExtraTest.class.getDeclaredMethod("testResolveEndpointInterceptorsEmptyBranches"));
        assertTrue(h.resolveEndpointInterceptors(info).isEmpty());

        // names == null -> empty
        h.interceptorsDisabled(false);
        info.setInterceptorNames(null);
        assertTrue(h.resolveEndpointInterceptors(info).isEmpty());
    }

    // ==================== beanBy (third-party @Bean annotation bridging) ====================

    // beanBy bridges a third-party @Bean-equivalent annotation (MyBean, see RouterFixtures)
    // into a real bean through the public scanPackages/scan entry point.

    @Test
    public void testBeanByBridgesThirdPartyAnnotation() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.beanBy(MyBean.class);
        h.annotationResolver(acceptResolver(set(BeanByConfig.class), false, false, true));
        h.scanPackages(PKG);
        h.scan();

        // the bridged @MyBean method is registered as a real bean
        assertEquals("bridged", h.beanContainer.getBean(String.class));
        // default @Bean is no longer recognized after beanBy override
        assertNull(h.beanContainer.getBean(Integer.class));
    }

    @Test
    public void testBeanByEmptyClearsDefaultAnnotation() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.beanBy(); // no args -> clears default {Bean.class}, mirroring injectBy/valueBy
        h.annotationResolver(acceptResolver(set(BeanByDefaultConfig.class), false, false, true));
        h.scanPackages(PKG);
        h.scan();
        // default @Bean annotation is no longer recognized -> not registered
        assertNull(h.beanContainer.getBean(String.class));
    }

    // ==================== annotationValue via third-party bridge (public entry) ====================

    // ThirdBean/ThirdInject/ThirdValue are NOT special-cased by the test resolver, so resolution falls
    // through to DefaultAnnotationResolver.annotationValue(), covering resolveBeanName/resolveInjectName/
    // resolveValueExpression and the no-value() catch branch. Driven through the public scanPackages/scan.
    @Test
    public void testThirdPartyBridgeExercisesAnnotationValue() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.valueBy(ThirdValue.class)
                .injectBy(ThirdInject.class, NoValueMethod.class)
                .beanBy(ThirdBean.class)
                .annotationResolver(acceptResolver(set(ThirdPartyBridgeConfig.class,
                        ThirdInjectHolder.class, ThirdValueHolder.class, NoValueHolder.class),
                        false, true, true))
                .scanPackages(PKG);
        h.scan();

        // resolveBeanName -> annotationValue(): ThirdBean.value() = "namedBean"
        assertEquals("bridged-third", h.beanContainer.getBean("namedBean"));
        // resolveInjectName -> annotationValue(): ThirdInject.value() = "namedBean"
        assertEquals("bridged-third", h.beanContainer.getBean(ThirdInjectHolder.class).getDep());
        // resolveValueExpression -> annotationValue(): ThirdValue.value() = "lit-value" (literal, no placeholder)
        assertEquals("lit-value", h.beanContainer.getBean(ThirdValueHolder.class).getV());
        // annotationValue catch branch: no value() method -> "" -> by-type lookup
        assertNotNull(h.beanContainer.getBean(NoValueHolder.class).getNv());
    }

    // ==================== DefaultUpgradeHandler.removeResources coverage ====================

    // Scan a package containing only a @WebSocket endpoint, then scan again. The second scan's
    // clearScanResources() calls removeResources(scannedUpgradePaths) over a non-empty set, exercising
    // the removal + disconnect loop. Driven purely through public scanPackages()/scan(); no reflection.
    @Test
    public void testScanWebSocketEndpointThenRescanClearsResources() {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.routerfixtures.wsextra");
        handler.scan();   // registers the scanned @WebSocket endpoint into scannedUpgradePaths
        handler.scan();   // re-scan -> clearScanResources -> removeResources(loop body)
    }

    private static class MyParamType {
        final String v;
        MyParamType(String v) { this.v = v; }
    }

    @Test
    public void testConfigFilesSetter() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertNotNull(h.configFiles("application.properties"));
        assertNotNull(h.configFiles());
    }

    @Test
    public void testConfigFilesAfterPreparedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.prepared = true;
        assertThrows(IllegalStateException.class, () -> h.configFiles("app.properties"));
    }

    @Test
    public void testIgnoreInternalConfigSetter() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertNotNull(h.ignoreInternalConfig(true));
        assertNotNull(h.ignoreInternalConfig(false));
    }

    @Test
    public void testIgnoreInternalConfigAfterPreparedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.prepared = true;
        assertThrows(IllegalStateException.class, () -> h.ignoreInternalConfig(true));
    }

    @Test
    public void testEnablesSetter() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertNotNull(h.enables(Component.class));
        assertNotNull(h.enables());
    }

    @Test
    public void testHeaderParamBySetter() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertNotNull(h.headerParamBy(RequestHeader.class));
        assertNotNull(h.headerParamBy());
    }

    @Test
    public void testComponentEnhancerNonProxy() {
        final boolean[] called = {false};
        ComponentEnhancer enhancer = new ComponentEnhancer() {
            public boolean requiresProxy(Class<?> c) { return false; }
            public Class<? extends Annotation>[] proxyAnnotations() { return null; }
            public Object enhance(Class<?> c, Constructor<?> ctor, Object[] args) {
                called[0] = true;
                try {
                    return ctor.newInstance(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .componentEnhancer(enhancer)
                .annotationResolver(acceptResolver(set(ThirdValueHolder.class), false, true, false))
                .scanPackages(PKG);
        h.scan();
        assertNotNull(h.beanContainer.getBean(ThirdValueHolder.class));
        assertFalse(called[0]);
    }

    @Test
    public void testComponentEnhancerProxyApplied() {
        final boolean[] called = {false};
        ComponentEnhancer enhancer = new ComponentEnhancer() {
            public boolean requiresProxy(Class<?> c) { return true; }
            public Class<? extends Annotation>[] proxyAnnotations() { return null; }
            public Object enhance(Class<?> c, Constructor<?> ctor, Object[] args) {
                called[0] = true;
                try {
                    return ctor.newInstance(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .componentEnhancer(enhancer)
                .annotationResolver(acceptResolver(set(ThirdValueHolder.class), false, true, false))
                .scanPackages(PKG);
        h.scan();
        assertNotNull(h.beanContainer.getBean(ThirdValueHolder.class));
        assertTrue(called[0]);
    }

    @Test
    public void testComponentEnhancerTriggersProxy() {
        final boolean[] called = {false};
        ComponentEnhancer enhancer = new ComponentEnhancer() {
            public boolean requiresProxy(Class<?> c) { return false; }
            public Class<? extends Annotation>[] proxyAnnotations() {
                return (Class<? extends Annotation>[]) new Class[]{Component.class};
            }
            public Object enhance(Class<?> c, Constructor<?> ctor, Object[] args) {
                called[0] = true;
                try {
                    return ctor.newInstance(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .componentEnhancer(enhancer)
                .annotationResolver(acceptResolver(set(ThirdValueHolder.class), false, true, false))
                .scanPackages(PKG);
        h.scan();
        assertNotNull(h.beanContainer.getBean(ThirdValueHolder.class));
        assertTrue(called[0]);
    }

    @Test
    public void testRegisterParamConverterLockedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertThrows(IllegalArgumentException.class, () -> h.registerParamConverter(Integer.class, s -> 0));
    }

    @Test
    public void testRegisterParamConverterCustomType() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertNotNull(h.registerParamConverter(MyParamType.class, s -> new MyParamType(s)));
    }

    @Test
    public void testScanAfterPreparedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.prepared = true;
        assertThrows(IllegalStateException.class, () -> h.scan());
    }

    @Test
    public void testScanWithIgnoreInternalConfig() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .ignoreInternalConfig(true)
                .annotationResolver(acceptResolver(set(PathVarController.class), true, false, false))
                .scanPackages(PKG);
        h.scan();
        assertNotNull(h.beanContainer.getBean(PathVarController.class.getName()));
    }

    @Test
    public void testComponentEnhancerTriggersProxyViaMethodAnnotation() {
        final boolean[] called = {false};
        ComponentEnhancer enhancer = new ComponentEnhancer() {
            public boolean requiresProxy(Class<?> c) { return false; }
            public Class<? extends Annotation>[] proxyAnnotations() {
                return (Class<? extends Annotation>[]) new Class[]{Endpoint.class};
            }
            public Object enhance(Class<?> c, Constructor<?> ctor, Object[] args) {
                called[0] = true;
                try {
                    return ctor.newInstance(args);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .componentEnhancer(enhancer)
                .annotationResolver(acceptResolver(set(PathVarController.class), true, false, false))
                .scanPackages(PKG);
        h.scan();
        assertNotNull(h.beanContainer.getBean(PathVarController.class.getName()));
        assertTrue(called[0]);
    }

    @Test
    public void testComponentEnhancerReturnsNullFailsRegistration() {
        ComponentEnhancer enhancer = new ComponentEnhancer() {
            public boolean requiresProxy(Class<?> c) { return true; }
            public Class<? extends Annotation>[] proxyAnnotations() { return null; }
            public Object enhance(Class<?> c, Constructor<?> ctor, Object[] args) { return null; }
        };
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .componentEnhancer(enhancer)
                .annotationResolver(acceptResolver(set(ThirdValueHolder.class), false, true, false))
                .scanPackages(PKG);
        assertThrows(RuntimeException.class, h::scan);
    }

    @Test
    public void testPrepareIdempotent() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .hotReload(false)
                .annotationResolver(acceptResolver(set(ThirdValueHolder.class), false, true, false))
                .scanPackages(PKG);
        h.prepare();
        h.prepare();
        assertTrue(h.prepared);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface EnableFeatureA {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface EnableFeatureB {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface EnableFeatureC {}

    @Registration(EnableFeatureA.class)
    public static class FeatureARegistrar implements BeanRegistrationHandler {
        public FeatureARegistrar() {}
        public void onRegister(RegistrarContext ctx) {
            ctx.registerBean("featureABean", "A");
        }
        public void onAllReady(RegistrarContext ctx) {
            ctx.registerBean("featureAReady", "ready");
        }
    }

    @Registration(EnableFeatureB.class)
    public static class FeatureBRegistrar implements BeanRegistrationHandler {
        private final RegistrarContext ctx;
        public FeatureBRegistrar(RegistrarContext ctx) { this.ctx = ctx; }
        public void onRegister(RegistrarContext ctx) {
            ctx.registerBean("featureBBean", "B");
        }
    }

    @Registration(EnableFeatureC.class)
    public static class FeatureCOtherRegistrar implements BeanRegistrationHandler {
        public FeatureCOtherRegistrar() {}
        public void onRegister(RegistrarContext ctx) {
            ctx.registerBean("featureCBean", "C");
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface EnableFeatureD {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface EnableFeatureE {}

    @Registration(EnableFeatureD.class)
    public static class FeatureDRegistrar implements BeanRegistrationHandler {
        public FeatureDRegistrar() {}
        public void onRegister(RegistrarContext ctx) {}
        public void onAllReady(RegistrarContext ctx) { throw new RuntimeException("all-ready-boom"); }
    }

    @Registration(EnableFeatureE.class)
    public static class FeatureERegistrar implements BeanRegistrationHandler {
        public FeatureERegistrar() {}
        public void onRegister(RegistrarContext ctx) { throw new RuntimeException("register-boom"); }
    }

    @Test
    public void testEnablesRegistrarDiscoveryAndActivation() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .enables(EnableFeatureA.class, EnableFeatureB.class)
                .annotationResolver(acceptResolver(set(PathVarController.class), true, false, false))
                .scanPackages(PKG);
        h.scan();
        assertEquals("A", h.beanContainer.getBean("featureABean"));
        assertEquals("B", h.beanContainer.getBean("featureBBean"));
        assertEquals("ready", h.beanContainer.getBean("featureAReady"));
    }

    @Test
    public void testRegistrarOnRegisterFailurePropagates() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .enables(EnableFeatureE.class)
                .annotationResolver(acceptResolver(set(PathVarController.class), true, false, false))
                .scanPackages(PKG);
        assertThrows(RuntimeException.class, h::scan);
    }

    @Test
    public void testRegistrarOnAllReadyFailurePropagates() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .enables(EnableFeatureD.class)
                .annotationResolver(acceptResolver(set(PathVarController.class), true, false, false))
                .scanPackages(PKG);
        assertThrows(RuntimeException.class, h::scan);
    }

    // ==================== AnnotationRouteUtils remaining branch coverage ====================

    // ── fixtures ──

    // Only a public arg ctor, no public no-arg ctor -> findPublicNoArgConstructor returns null (L25).
    public static class OnlyArgCtor {
        public OnlyArgCtor(String s) {}
    }

    // A public 1-arg ctor whose type is not RegistrarContext -> findRegistrarConstructor returns null (L34).
    public static class OneArgOtherCtor {
        public OneArgOtherCtor(String s) {}
    }

    // Implements BeanRegistrationHandler but lacks @Registration -> registrarOrder() -> MAX_VALUE (L44)
    // and isRegistrar() -> false (L59).
    public static class NoRegRegistrar implements BeanRegistrationHandler {
        @Override public void onRegister(RegistrarContext ctx) {}
        @Override public void onAllReady(RegistrarContext ctx) {}
    }

    // Single @Inject constructor -> findConstructor returns it (L257 injectCtor != null branch).
    public static class InjectCtorComp {
        public final String v;
        @MyInject public InjectCtorComp(String s) { this.v = s; }
        public InjectCtorComp() { this.v = "def"; }
    }

    // @Inject("name") parameter holder -> resolveParameters by-name branch (L279).
    public static class InjectNameHolder {
        public void m(@Inject("depBean") String dep) {}
    }

    // List<?> parameter -> generic arg is a WildcardType, not a Class -> String.class fallback (L133).
    public static class WildcardHolder {
        public void m(List<?> x) {}
    }

    // ── tests ──

    @Test
    public void testRequestBodyNullConverterDrivesAssertBodyAssignable() throws Exception {
        // messageConverter.read() returns null (mockConverter) -> KIND_BODY calls
        // assertBodyAssignable(null, ...) covering its early-return branch (L78).
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(mockConverter())
                .annotationResolver(acceptResolver(set(GeneralBodyNoConvController.class), true, false, false))
                .scanPackages(PKG);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
        HTTPServer server = HTTPServer.of(51131).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            assertEquals(200, code(client, "http://127.0.0.1:51131/gb/up"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testRequestBodyNonNullConverterDrivesAssertBodyAssignable() throws Exception {
        // messageConverter.read() returns a non-null String -> assertBodyAssignable(value, String.class)
        // takes the value != null branch (L78), complementing the null-converter test for full coverage.
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(nonNullConverter())
                .annotationResolver(acceptResolver(set(GeneralBodyNoConvController.class), true, false, false))
                .scanPackages(PKG);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
        HTTPServer server = HTTPServer.of(51132).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            assertEquals(200, code(client, "http://127.0.0.1:51132/gb/up"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testHeaderParamBinding() throws Exception {
        // @RequestHeader params -> KIND_HEADER (L980-984). Required-present, optional-absent,
        // required-absent (throw -> 500) and multi-absent cover resolveSimpleParam's header branches.
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(HeaderParamController.class), true, false, false))
                .scanPackages(PKG);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
        HTTPServer server = HTTPServer.of(51133).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            assertEquals(200, codeWithHeader(client, "http://127.0.0.1:51133/hdr/req", "X-Req", "foo"));
            assertEquals(200, code(client, "http://127.0.0.1:51133/hdr/opt"));
            assertEquals(400, code(client, "http://127.0.0.1:51133/hdr/missing"));
            assertEquals(200, code(client, "http://127.0.0.1:51133/hdr/multi"));
        } finally {
            server.shutdown();
        }
    }

    private static int codeWithHeader(OkHttpClient client, String url, String name, String value) throws Exception {
        Response r = client.newCall(new Request.Builder().url(url).header(name, value).get().build()).execute();
        int c = r.code();
        r.close();
        return c;
    }

    @Test
    public void testUnannotatedParamBinding() throws Exception {
        // params with no recognized annotation -> argKinds=0 -> default case.
        // int x (primitive) covers L986-987; String s (non-primitive) covers the false branch of L986.
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(PrimitiveParamController.class), true, false, false))
                .scanPackages(PKG);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build();
        HTTPServer server = HTTPServer.of(51134).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            assertEquals(200, code(client, "http://127.0.0.1:51134/pp/p"));
            assertEquals(200, code(client, "http://127.0.0.1:51134/pp/ps"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testFindPublicNoArgConstructorArgOnly() {
        assertNull(AnnotationRouteUtils.findPublicNoArgConstructor(OnlyArgCtor.class));
    }

    @Test
    public void testFindRegistrarConstructorNonRegistrarArg() {
        assertNull(AnnotationRouteUtils.findRegistrarConstructor(OneArgOtherCtor.class));
    }

    @Test
    public void testRegistrarOrderAndIsRegistrarWithoutAnnotation() {
        assertEquals(Integer.MAX_VALUE, AnnotationRouteUtils.registrarOrder(new NoRegRegistrar()));
        assertFalse(AnnotationRouteUtils.isRegistrar(NoRegRegistrar.class));
        assertTrue(AnnotationRouteUtils.isRegistrar(FeatureARegistrar.class));
    }

    @Test
    public void testFindConstructorPrefersInject() {
        AnnotationRouterHandler h = new AnnotationRouterHandler().injectBy(MyInject.class);
        BeanContainer bc = beanContainerOf(h);
        Constructor<?>[] ctors = InjectCtorComp.class.getConstructors();
        Constructor<?> injectCtor = null;
        for (Constructor<?> c : ctors) {
            if (bc.findInjectAnnotation(c) != null) injectCtor = c;
        }
        assertNotNull(injectCtor);
        assertSame(injectCtor, AnnotationRouteUtils.findConstructor(ctors, bc));
    }

    @Test
    public void testResolveParametersInjectByName() throws Exception {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        BeanContainer bc = beanContainerOf(h);
        bc.register("depBean", "DEP", false);
        Method m = InjectNameHolder.class.getMethod("m", String.class);
        Parameter p = m.getParameters()[0];
        Object[] args = AnnotationRouteUtils.resolveParameters(new Parameter[]{p}, "ctx", bc, new DefaultAnnotationResolver());
        assertEquals("DEP", args[0]);
    }

    @Test
    public void testResolveParamElementTypeWildcard() throws Exception {
        Method m = WildcardHolder.class.getMethod("m", List.class);
        assertEquals(String.class, AnnotationRouteUtils.resolveParamElementType(m.getParameters()[0], List.class));
    }
}
