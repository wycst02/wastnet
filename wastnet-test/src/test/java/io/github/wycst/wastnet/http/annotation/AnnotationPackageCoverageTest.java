package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.SseEmitter;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.*;

import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive coverage for annotation package core classes.
 */
public class AnnotationPackageCoverageTest {

    // ==================== Annotated test model classes ====================

    @Controller("/api")
    public static class BasicController {
        @Endpoint("/hello")
        public String hello() { return "hello"; }
        @Sse("/events")
        public void events(SseEmitter emitter) {}
    }

    @Controller("/fast")
    public static class FastPathController {
        @Endpoint("/route")
        public void fastRoute(HttpRequest req, HttpResponse resp) {}
    }

    @Controller("/body")
    public static class BodyController {
        @Endpoint("/read")
        @ResponseBody
        public String readBody(@RequestBody String body) { return body; }
    }

    @Controller("/di")
    public static class DiController {
        public DiController(TestDependency dep) {}
        @Endpoint("/get")
        public void get(HttpRequest req, HttpResponse resp) {}
    }

    @Controller("/empty")
    public static class EmptyController {}

    // Exercises resolveInterceptorNames: class-level only, plus class+method merge with
    // deduplication and empty-string filtering. Deliberately NOT annotated @Controller so the
    // package router-scan tests don't try to register it (its @WithInterceptor names are unbound).
    @WithInterceptor({"a", "b", ""})
    public static class InterceptorController {
        @Endpoint("/m1")
        public void m1(HttpRequest req, HttpResponse resp) {}

        @Endpoint("/m2")
        @WithInterceptor({"b", "c", ""})
        public void m2(HttpRequest req, HttpResponse resp) {}
    }

    // Class carries no @WithInterceptor, method carries one -> method-level-only path.
    public static class InterceptorMethodOnlyController {
        @Endpoint("/m")
        @WithInterceptor({"x"})
        public void m(HttpRequest req, HttpResponse resp) {}
    }

    // SSE endpoint with @WithInterceptor to exercise the resolveSseEndpoints entry point.
    @WithInterceptor({"svc"})
    public static class InterceptorSseController {
        @Sse("/stream")
        @WithInterceptor({"audit", "svc"})
        public void stream(SseEmitter emitter) {}
    }

    // Class and method share a name -> method-level duplicate hits !names.contains(name)==false
    // (the "already added" skip branch inside the class-level loop).
    @WithInterceptor("dup")
    public static class InterceptorDupController {
        @Endpoint("/d")
        @WithInterceptor({"dup"})
        public void d(HttpRequest req, HttpResponse resp) {}
    }

    // Class-level value itself contains a duplicate ("dup" twice) -> the class-level loop's
    // !names.contains(name)==false branch (L68) is exercised directly.
    @WithInterceptor({"dup", "dup"})
    public static class InterceptorClassDupController {
        @Endpoint("/cd")
        public void cd(HttpRequest req, HttpResponse resp) {}
    }

    // Class-level absent and method-level value is only the empty string -> every entry is
    // filtered out, names stays empty, and resolveInterceptorNames returns null
    // (the names.isEmpty() arm of the ternary at L76).
    public static class InterceptorAllEmptyController {
        @Endpoint("/z")
        @WithInterceptor({""})
        public void z(HttpRequest req, HttpResponse resp) {}
    }

    @Controller("/sse-only")
    public static class SseOnlyController {
        @Sse("/stream")
        public void stream(SseEmitter emitter) {}
    }

    // Controllers that exercise combinePath edge cases
    @Controller("/")
    public static class RootBaseController {
        @Endpoint("/root")
        public void root(HttpRequest req, HttpResponse resp) {}
    }

    @Controller("/other")
    public static class NoSlashController {
        // Endpoint path without leading slash -> combinePath branch 8
        @Endpoint("noslash")
        public void noSlash(HttpRequest req, HttpResponse resp) {}
    }

    @Component("myService")
    public static class TestComponent {
        @Value("${app.name:default}")
        private String appName;
        @PostConstruct public void init() {}
        @PreDestroy public void destroy() {}
    }

    @Component
    public static class TestDependency {}

    // Bean for @Bean method testing
    @Component
    public static class BeanProducer {
        @Bean
        public String produce() { return "produced"; }
    }

    // Bean for @Bean with dependencies
    @Component
    public static class BeanWithDepProducer {
        @Bean("customBean")
        public String produceWithDep(TestDependency dep) { return "withDep"; }
    }

    // Test beans for different @Value types
    @Component
    public static class ValueTypesBean {
        @Value("${int.val:42}") private int intVal;
        @Value("${long.val:99}") private long longVal;
        @Value("${bool.val:true}") private boolean boolVal;
        @Value("${double.val:3.14}") private double doubleVal;
        @Value("${float.val:2.5}") private float floatVal;
        @Value("${null.val}") private String nullVal;
        @Value("plaintext") private String plainVal;
        @Value("${unclosed") private String unclosedVal;
        @Value("${first:1},${second:2}") private String multiVal;
        @Value("${exists}") private String existsVal;
    }

    @WebSocket("/chat")
    public static class TestWebSocket extends WebSocketResource {
        public TestWebSocket() { super(0); }
    }

    @WebSocket("")
    public static class EmptyWebSocket extends WebSocketResource {
        public EmptyWebSocket() { super(0); }
    }

    // ==================== AnnotationFilter ====================

    @Test public void testAnnotationFilter() {
        assertTrue(((AnnotationFilter) c -> true).accept(String.class));
        assertFalse(((AnnotationFilter) c -> false).accept(String.class));
    }

    // ==================== AnnotationResolver interface ====================

    @Test public void testAnnotationResolverFull() {
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == String.class; }
            @Override public boolean isController(Class<?> c) { return c == String.class; }
            @Override public String resolveControllerPath(Class<?> c) { return "/"; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public boolean isComponent(Class<?> c) { return true; }
            @Override public String resolveComponentName(Class<?> c) { return "c"; }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertTrue(r.accept(String.class));
        assertTrue(r.isController(String.class));
        assertEquals("/", r.resolveControllerPath(String.class));
        assertTrue(r.resolveEndpointRoutes(String.class).isEmpty());
        assertFalse(r.isWebSocketEndpoint(String.class));
        assertTrue(r.isComponent(String.class));
        assertEquals("c", r.resolveComponentName(String.class));
        assertFalse(r.isConfiguration(String.class));
        assertNull(r.resolveValueExpression(null));
        assertNull(r.resolveInjectName(null));
    }

    // ==================== DefaultAnnotationResolver ====================

    @Test public void testAccept() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertTrue(r.accept(BasicController.class));
        assertTrue(r.accept(TestComponent.class));
        assertTrue(r.accept(TestWebSocket.class));
        assertFalse(r.accept(String.class));
    }

    @Test public void testIsController() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertTrue(r.isController(BasicController.class));
        assertFalse(r.isController(String.class));
    }

    @Test public void testResolveControllerPath() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertEquals("/api", r.resolveControllerPath(BasicController.class));
        assertEquals("", r.resolveControllerPath(String.class));
    }

    @Test public void testResolveEndpointRoutes() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(BasicController.class);
        assertEquals(1, routes.size());
        assertEquals("/hello", routes.get(0).getPath());
        assertEquals(Endpoint.class, routes.get(0).getAnnotationType());
        assertTrue(r.resolveEndpointRoutes(String.class).isEmpty());
    }

    @Test public void testResolveSseEndpoints() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveSseEndpoints(BasicController.class);
        assertEquals(1, routes.size());
        assertEquals("/events", routes.get(0).getPath());
        assertEquals(Sse.class, routes.get(0).getAnnotationType());
        assertTrue(r.resolveSseEndpoints(String.class).isEmpty());
        // Static method should be skipped
        assertTrue(r.resolveSseEndpoints(EmptyController.class).isEmpty());
    }

    // ==================== resolveInterceptorNames (private, reached via Endpoint/SSE) ====================

    @Test public void testResolveInterceptorNames_classLevelOnly() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(InterceptorController.class);
        // m1: class-level {"a","b",""} -> empty string filtered -> [a, b]
        MethodRouteInfo m1 = findRoute(routes, "/m1");
        assertArrayEquals(new String[]{"a", "b"}, m1.getInterceptorNames());
        // m2: class {a,b} merged with method {b,c} -> deduped -> [a, b, c]
        MethodRouteInfo m2 = findRoute(routes, "/m2");
        assertArrayEquals(new String[]{"a", "b", "c"}, m2.getInterceptorNames());
    }

    @Test public void testResolveInterceptorNames_methodLevelOnly() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(InterceptorMethodOnlyController.class);
        MethodRouteInfo m = findRoute(routes, "/m");
        assertArrayEquals(new String[]{"x"}, m.getInterceptorNames());
    }

    @Test public void testResolveInterceptorNames_viaSse() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveSseEndpoints(InterceptorSseController.class);
        MethodRouteInfo m = findRoute(routes, "/stream");
        // class {svc} merged with method {audit, svc} -> [svc, audit]
        assertArrayEquals(new String[]{"svc", "audit"}, m.getInterceptorNames());
    }

    @Test public void testResolveInterceptorNames_none() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        // Endpoints but no @WithInterceptor anywhere -> names stay null
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(BasicController.class);
        for (MethodRouteInfo info : routes) {
            assertNull(info.getInterceptorNames());
        }
    }

    @Test public void testResolveInterceptorNames_duplicateSkipped() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(InterceptorDupController.class);
        MethodRouteInfo d = findRoute(routes, "/d");
        // class {dup} merged with method {dup} -> duplicate skipped -> single "dup"
        assertArrayEquals(new String[]{"dup"}, d.getInterceptorNames());
    }

    @Test public void testResolveInterceptorNames_classLevelDuplicate() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(InterceptorClassDupController.class);
        MethodRouteInfo cd = findRoute(routes, "/cd");
        // class-level value {"dup","dup"} -> deduplicated inside the class-level loop -> ["dup"]
        assertArrayEquals(new String[]{"dup"}, cd.getInterceptorNames());
    }

    @Test public void testResolveInterceptorNames_allEmptyReturnsNull() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        List<MethodRouteInfo> routes = r.resolveEndpointRoutes(InterceptorAllEmptyController.class);
        MethodRouteInfo z = findRoute(routes, "/z");
        // method-level value is only "" -> all filtered out -> names empty -> returns null
        assertNull(z.getInterceptorNames());
    }

    private static MethodRouteInfo findRoute(List<MethodRouteInfo> routes, String path) {
        for (MethodRouteInfo info : routes) {
            if (path.equals(info.getPath())) return info;
        }
        throw new AssertionError("route not found: " + path);
    }

    @Test public void testIsWebSocketEndpoint() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertTrue(r.isWebSocketEndpoint(TestWebSocket.class));
        assertFalse(r.isWebSocketEndpoint(String.class));
    }

    @Test public void testResolveWebSocketPath() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertEquals("/chat", r.resolveWebSocketPath(TestWebSocket.class));
        assertEquals("", r.resolveWebSocketPath(EmptyWebSocket.class));
        assertEquals("", r.resolveWebSocketPath(String.class));
    }

    @Test public void testIsComponent() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertTrue(r.isComponent(TestComponent.class));
        assertFalse(r.isComponent(String.class));
    }

    @Test public void testResolveComponentName() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertEquals("myService", r.resolveComponentName(TestComponent.class));
        assertEquals("testDependency", r.resolveComponentName(TestDependency.class));
    }

    @Test public void testIsConfiguration() {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        assertTrue(r.isConfiguration(SimpleConfig.class));
        assertFalse(r.isConfiguration(String.class));
    }

    @Test public void testResolveValueExpression() throws Exception {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        java.lang.reflect.Field intField = ValueTypesBean.class.getDeclaredField("intVal");
        assertEquals("${int.val:42}", r.resolveValueExpression(intField.getAnnotation(Value.class)));
        java.lang.reflect.Field nullField = ValueTypesBean.class.getDeclaredField("nullVal");
        assertEquals("${null.val}", r.resolveValueExpression(nullField.getAnnotation(Value.class)));
    }

    public static class NamedInjectBean {
        @Inject("myBean") private String named;
    }

    @Test public void testResolveInjectName() throws Exception {
        DefaultAnnotationResolver r = new DefaultAnnotationResolver();
        java.lang.reflect.Field namedField = NamedInjectBean.class.getDeclaredField("named");
        assertEquals("myBean", r.resolveInjectName(namedField.getAnnotation(Inject.class)));
        java.lang.reflect.Field byTypeField = DependentBean.class.getDeclaredField("dep");
        assertEquals("", r.resolveInjectName(byTypeField.getAnnotation(Inject.class)));
    }

    // ==================== MethodRouteInfo ====================

    @Test public void testMethodRouteInfo() throws Exception {
        Method m = BasicController.class.getMethod("hello");
        MethodRouteInfo i1 = new MethodRouteInfo("/p", new HttpMethod[]{HttpMethod.GET}, m);
        assertEquals("/p", i1.getPath()); assertSame(m, i1.getMethod());
        assertArrayEquals(new HttpMethod[]{HttpMethod.GET}, i1.getHttpMethods());
        assertNull(i1.getAnnotationType());
        MethodRouteInfo i2 = new MethodRouteInfo("/p", new HttpMethod[]{}, m, Endpoint.class);
        assertEquals(Endpoint.class, i2.getAnnotationType());
    }

    // ==================== BeanContainer - Core ====================

    @Test public void testRegisterGetClear() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setPostConstructAnnotations(new Class<?>[]{PostConstruct.class});
        c.setPreDestroyAnnotations(new Class<?>[]{PreDestroy.class});
        c.register("test", "hello");
        assertEquals("hello", c.getBean(String.class));
        assertNull(c.getBean(Integer.class));
        assertNotNull(c.getBeans());
        c.clear();
        assertNull(c.getBean(String.class));
    }

    @Test public void testPropertyAndConfig() {
        BeanContainer c = new BeanContainer();
        c.setProperty("k1", "v1");
        Map<String, String> props = new HashMap<String, String>();
        props.put("k2", "v2");
        c.setProperties(props);
        c.loadConfig(config -> config.put("k3", "v3"));
    }

    // ==================== BeanContainer - @Value injection ====================

    @Test public void testValueInjection_String() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setInjectAnnotations(new Class<?>[]{Inject.class});
        c.setPostConstructAnnotations(new Class<?>[]{PostConstruct.class});
        c.register("comp", new TestComponent());
        assertNotNull(c.getBean(TestComponent.class));
    }

    @Test public void testValueInjection_AllTypes() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setProperty("exists", "configuredValue");
        c.register("vt", new ValueTypesBean());
        ValueTypesBean vt = c.getBean(ValueTypesBean.class);
        assertNotNull(vt);
    }

    // ==================== BeanContainer - @Inject injection ====================

    public static class DependentBean {
        @Inject private TestDependency dep;
    }

    @Test public void testInjectDependency_success() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setResolver(new DefaultAnnotationResolver());
        c.setInjectAnnotations(new Class<?>[]{Inject.class});
        c.register("dep", new TestDependency());
        c.register("dependent", new DependentBean());
        c.injectAllFields();
        assertNotNull(c.getBean(DependentBean.class));
    }

    @Test public void testInjectDependency_missing() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setResolver(new DefaultAnnotationResolver());
        c.setInjectAnnotations(new Class<?>[]{Inject.class});
        c.register("dependent", new DependentBean());
        assertThrows(RuntimeException.class, c::injectAllFields);
    }

    // ==================== BeanContainer - findAnnotation ====================

    @Test public void testFindAnnotation_matches() throws Exception {
        BeanContainer c = new BeanContainer();
        java.lang.reflect.Field valueField = ValueTypesBean.class.getDeclaredField("intVal");
        assertNotNull(c.findValueAnnotation(valueField));
        assertNull(c.findInjectAnnotation(valueField));
    }

    @Test public void testFindAnnotation_noMatch() throws Exception {
        BeanContainer c = new BeanContainer();
        assertNull(c.findValueAnnotation(String.class.getDeclaredField("CASE_INSENSITIVE_ORDER")));
    }

    @Test public void testFindValueAnnotation() throws Exception {
        BeanContainer c = new BeanContainer();
        java.lang.reflect.Field field = ValueTypesBean.class.getDeclaredField("intVal");
        assertNotNull(c.findValueAnnotation(field));
    }

    @Test public void testFindInjectAnnotation() throws Exception {
        BeanContainer c = new BeanContainer();
        java.lang.reflect.Field field = DependentBean.class.getDeclaredField("dep");
        assertNotNull(c.findInjectAnnotation(field));
    }

    @Test public void testSetValueAnnotations() {
        BeanContainer c = new BeanContainer();
        c.setValueAnnotations(Value.class);
        assertNull(c.findValueAnnotation(String.class.getDeclaredFields()[0])); // no @Value on String
    }

    // ==================== BeanContainer - loadProperties ====================

    @Test public void testLoadProperties_FromStream() {
        BeanContainer c = new BeanContainer();
        // The test-config.properties file is on the test classpath
        // This exercises: getResourceAsStream, props.load, config.put, finally close
        c.loadProperties("test-config.properties");
        // Call it again to verify multiple calls work
        c.loadProperties("test-config.properties");
    }

    // ==================== BeanContainer - hasAnyAnnotation edge cases ====================

    @Test public void testHasAnyAnnotation_nullAnn() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setResolver(new DefaultAnnotationResolver());
        c.setInjectAnnotations(new Class<?>[]{null, Inject.class});
        c.register("dep", new TestDependency());
        c.register("dependent", new DependentBean());
        c.injectAllFields();
        assertNotNull(c.getBean(DependentBean.class));
    }

    // ==================== BeanContainer - resolvePlaceholder via reflection ====================

    @Test public void testResolvePlaceholder() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setProperty("existing.key", "resolvedValue");

        java.lang.reflect.Method resolvePlaceholder =
                BeanContainer.class.getDeclaredMethod("resolvePlaceholder", String.class);
        resolvePlaceholder.setAccessible(true);

        // null raw -> null
        assertNull(resolvePlaceholder.invoke(c, new Object[]{null}));

        // no placeholder -> same string
        assertEquals("plaintext", resolvePlaceholder.invoke(c, "plaintext"));

        // unclosed ${ -> rest of string as-is
        assertEquals("${unclosed", resolvePlaceholder.invoke(c, "${unclosed"));

        // key:default -> uses config when key exists
        assertEquals("resolvedValue", resolvePlaceholder.invoke(c, "${existing.key:fallback}"));

        // key:default -> uses default when key missing
        assertEquals("fallbackVal", resolvePlaceholder.invoke(c, "${missing.key:fallbackVal}"));

        // key without default and missing -> keeps original
        assertEquals("${noDefault}", resolvePlaceholder.invoke(c, "${noDefault}"));

        // multiple placeholders
        assertEquals("a-resolvedValue-b-fallback-c",
                resolvePlaceholder.invoke(c, "a-${existing.key}-b-${x:fallback}-c"));
    }

    // ==================== BeanContainer - convertValue via reflection ====================

    @Test public void testConvertValue() throws Exception {
        java.lang.reflect.Method convertValue =
                BeanContainer.class.getDeclaredMethod("convertValue", String.class, Class.class);
        convertValue.setAccessible(true);

        // null -> null
        assertNull(convertValue.invoke(null, null, String.class));

        // String
        assertEquals("abc", convertValue.invoke(null, "abc", String.class));

        // int/Integer
        assertEquals(42, convertValue.invoke(null, "42", int.class));
        assertEquals(42, convertValue.invoke(null, "42", Integer.class));

        // long/Long
        assertEquals(99L, convertValue.invoke(null, "99", long.class));
        assertEquals(99L, convertValue.invoke(null, "99", Long.class));

        // boolean/Boolean
        assertEquals(true, convertValue.invoke(null, "true", boolean.class));
        assertEquals(true, convertValue.invoke(null, "true", Boolean.class));

        // double/Double
        assertEquals(3.14, (Double) convertValue.invoke(null, "3.14", double.class), 0.001);
        assertEquals(3.14, (Double) convertValue.invoke(null, "3.14", Double.class), 0.001);

        // float/Float
        assertEquals(2.5f, (Float) convertValue.invoke(null, "2.5", float.class), 0.001f);
        assertEquals(2.5f, (Float) convertValue.invoke(null, "2.5", Float.class), 0.001f);

        // short/Short
        assertEquals((short) 10, convertValue.invoke(null, "10", short.class));
        assertEquals((short) 10, convertValue.invoke(null, "10", Short.class));

        // byte/Byte
        assertEquals((byte) 7, convertValue.invoke(null, "7", byte.class));
        assertEquals((byte) 7, convertValue.invoke(null, "7", Byte.class));

        // Unsupported type -> null (no ClassCastException)
        assertNull(convertValue.invoke(null, "any", Object.class));
        assertNull(convertValue.invoke(null, "42", StringBuilder.class));
    }

    // ==================== BeanContainer - invokeLifecycle exception ====================

    public static class LifecycleExceptionBean {
        @PostConstruct
        public void init() { throw new RuntimeException("init failed"); }
    }

    @Test public void testInvokeLifecycleException() {
        BeanContainer c = new BeanContainer();
        c.setPostConstructAnnotations(new Class<?>[]{PostConstruct.class});
        c.register("fail", new LifecycleExceptionBean());
        assertThrows(RuntimeException.class, c::invokeAllPostConstruct);
    }

    // ==================== AnnotationRouterHandler ====================

    @Test public void testRouterBuilderChains() {
        assertNotNull(new AnnotationRouterHandler()
                .requestBodyBy(RequestBody.class)
                .responseBodyBy(ResponseBody.class)
                .valueBy(Value.class)
                .injectBy(Inject.class)
                .postConstructBy(PostConstruct.class)
                .preDestroyBy(PreDestroy.class)
                .property("k", "v"));
    }

    @Test public void testRouterAnnotationResolver() {
        assertNotNull(new AnnotationRouterHandler()
                .annotationResolver(new DefaultAnnotationResolver()));
    }

    @Test public void testRouterMessageConverter() {
        HttpMessageConverter conv = new HttpMessageConverter() {
            @Override public <T> T read(HttpRequest req, ConverterConfig cfg, Class<T> type) { return null; }
            @Override public void write(Object v, ConverterConfig cfg, HttpResponse resp) {}
        };
        assertNotNull(new AnnotationRouterHandler().messageConverter(conv));
    }

    @Test public void testRouterPropertiesAndConfig() {
        Map<String, String> m = new HashMap<String, String>(); m.put("p1", "v1");
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.properties(m);
        h.loadConfig(config -> config.put("cfg.key", "cfg.val"));
        h.loadProperties();
        h.clear();
    }

    // Controllers for real request branch coverage
    @Controller("/fastbody")
    public static class FastBodyController {
        @Endpoint("/get")
        @ResponseBody
        public String handle(HttpRequest req, HttpResponse resp) { return "ok"; }
    }

    @Controller("/mixed")
    public static class MixedController {
        @Endpoint("/echo")
        public void echo(HttpRequest req, @RequestBody String body, HttpResponse resp) {}
    }

    @Controller("/single")
    public static class SingleParamController {
        @Endpoint("/req")
        public void single(HttpRequest req) {}
    }

    @Controller("/plainparam")
    public static class PlainParamController {
        @Endpoint("/greet")
        public void greet(String name) {}
    }

    // Controller with empty base path -> combinePath base.isEmpty()
    @Controller("")
    public static class EmptyBaseController {
        @Endpoint("/test")
        public void test(HttpRequest req, HttpResponse resp) {}
        @Endpoint("noslash")
        public void noSlash(HttpRequest req, HttpResponse resp) {}
    }

    // Controller with param having non-@RequestBody annotation -> isRequestBodyParam mismatch branch
    @Controller("/annotated")
    public static class AnnotatedParamController {
        @Endpoint("/hello")
        public void hello(@Deprecated String name) {}
    }

    // Controller with single HttpResponse param -> L302 false branch
    @Controller("/responly")
    public static class RespOnlyController {
        @Endpoint("/handle")
        public void handle(HttpResponse resp) {}
    }

    // Controller with missing constructor dependency (no @Controller -> custom resolver only)
    public static class NoDepController {
        public NoDepController(Integer missing) {}
        @Endpoint("/fail")
        public void fail(HttpRequest req, HttpResponse resp) {}
    }

    // Exception test beans (no annotation on the class itself - avoids default scanner)
    public static class BadWebSocket {} // NOT extending WebSocketResource
    public static class NonPublicClass {} // not public -> isEligibleClass=false

    @Component
    public abstract static class AbstractComponent {} // abstract -> isEligibleClass=false

    public static class FailingComponent {
        public FailingComponent() { throw new RuntimeException("ctor fail"); }
    }

    @Component
    @Configuration
    public static class SimpleConfig {
        @Bean
        public String myBean() { return "configured"; }
    }

    // Pure @Configuration (no @Component) -> triggers @Configuration branch in scanPackages
    @Configuration
    public static class PureConfig {
        @Bean
        public String pureBean() { return "pure"; }
    }

    @Component
    @Configuration
    public static class CrossConfig {
        @Bean
        public String first() { return "first"; }
        @Bean
        public String second(@Inject("first") String f) { return f + "+second"; }
    }

    public static class ExceptionBeanProducer {
        @Bean
        public String failBean() { throw new RuntimeException("bean fail"); }
    }

    public static class MissingDepBeanProducer {
        @Bean
        public String needDep(Integer missing) { return String.valueOf(missing); }
    }

    // For registerControllerBean catch - no @Controller to avoid default scan
    public static class FailingController {
        @PostConstruct
        public void init() { throw new RuntimeException("init fail"); }
        @Endpoint("/fail")
        public void handle() {}
    }

    // ==================== AnnotationRouterHandler - Full scan ====================

    private static HttpMessageConverter mockConverter() {
        return new HttpMessageConverter() {
            @Override public <T> T read(HttpRequest req, ConverterConfig cfg, Class<T> type) { return null; }
            @Override public void write(Object v, ConverterConfig cfg, HttpResponse resp) {}
        };
    }

    @Test
    public void testRouterScanFull() {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(mockConverter())
                .scanPackages("io.github.wycst.wastnet.http.annotation");
        assertNotNull(handler);
    }

    @Test
    public void testRouterScanThenClear() {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(mockConverter())
                .scanPackages("io.github.wycst.wastnet.http.annotation");
        handler.clear();
    }

    @Test
    public void testRouterScanWithoutConverter() {
        // BodyController has @ResponseBody but no messageConverter -> throws
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .scanPackages("io.github.wycst.wastnet.http.annotation"));
    }

    @Test
    public void testRouterScanWithComponentCtorFail() {
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == FailingComponent.class; }
            @Override public boolean isComponent(Class<?> c) { return c == FailingComponent.class; }
            @Override public String resolveComponentName(Class<?> c) { return "fail"; }
            @Override public boolean isController(Class<?> c) { return false; }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveControllerPath(Class<?> c) { return ""; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .annotationResolver(r)
                        .scanPackages("io.github.wycst.wastnet.http.annotation"));
    }

    @Test
    public void testRouterScanProcessBeanException() throws Exception {
        // Directly test tryProcessBeanMethod via reflection
        Method tryProcess = AnnotationRouterHandler.class.getDeclaredMethod("tryProcessBeanMethod",
                Object.class, Method.class, String.class);
        tryProcess.setAccessible(true);
        ExceptionBeanProducer bean = new ExceptionBeanProducer();
        Method failMethod = ExceptionBeanProducer.class.getMethod("failBean");
        assertThrows(RuntimeException.class, () -> {
            try {
                tryProcess.invoke(new AnnotationRouterHandler(), bean, failMethod, "");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    public void testRouterScanMissingBeanDep() throws Exception {
        // Directly test resolveParameters via reflection
        Method resolveParams = AnnotationRouterHandler.class.getDeclaredMethod("resolveParameters",
                java.lang.reflect.Parameter[].class, String.class);
        resolveParams.setAccessible(true);
        Method needDep = MissingDepBeanProducer.class.getMethod("needDep", Integer.class);
        AnnotationRouterHandler handler = new AnnotationRouterHandler();
        assertThrows(RuntimeException.class, () -> {
            try {
                resolveParams.invoke(handler, needDep.getParameters(), "test");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    public void testRouterScanBeanDeferredThenSuccess() {
        // Only scan DeferredConfig — secondBean depends on firstBean via @Inject("firstBean")
        // When method order causes secondBean to be attempted first, it gets deferred,
        // then resolved on retry. Exercises the deferred->retry->success path (lines 200-209).
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.DeferredConfig.class; }
            @Override public boolean isComponent(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.DeferredConfig.class; }
            @Override public String resolveComponentName(Class<?> c) { return "deferredConfig"; }
            @Override public boolean isController(Class<?> c) { return false; }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveControllerPath(Class<?> c) { return ""; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public boolean isConfiguration(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.DeferredConfig.class; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation ann) { return ((Value) ann).value(); }
            @Override public String resolveInjectName(java.lang.annotation.Annotation ann) { return ((Inject) ann).value(); }
            @Override public String resolveBeanName(java.lang.annotation.Annotation ann) { return ((Bean) ann).value(); }
        };
        assertDoesNotThrow(() ->
                new AnnotationRouterHandler()
                        .injectBy(Inject.class)
                        .annotationResolver(r)
                        .scanPackages("testonly.retrybeans"));
    }

    @Test
    public void testRouterScanBeanRetryExhausted() {
        // Only scan ExhaustRetryConfig — neverResolved depends on @Inject("nonExistent")
        // which never gets registered — after 4 retries the exception is thrown (lines 211-214).
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.ExhaustRetryConfig.class; }
            @Override public boolean isComponent(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.ExhaustRetryConfig.class; }
            @Override public String resolveComponentName(Class<?> c) { return "exhaustConfig"; }
            @Override public boolean isController(Class<?> c) { return false; }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveControllerPath(Class<?> c) { return ""; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public boolean isConfiguration(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.ExhaustRetryConfig.class; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation ann) { return ((Value) ann).value(); }
            @Override public String resolveInjectName(java.lang.annotation.Annotation ann) { return ((Inject) ann).value(); }
            @Override public String resolveBeanName(java.lang.annotation.Annotation ann) { return ((Bean) ann).value(); }
        };
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .injectBy(Inject.class)
                        .annotationResolver(r)
                        .scanPackages("testonly.retrybeans"));
        assertTrue(ex.getMessage().contains("Cannot resolve dependencies after 4 retries"),
                "Unexpected message: " + ex.getMessage());
    }

    @Test
    public void testRouterScanMissingCtorDep() throws Exception {
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == NoDepController.class || c == TestDependency.class; }
            @Override public boolean isController(Class<?> c) { return c == NoDepController.class; }
            @Override public boolean isComponent(Class<?> c) { return c == TestDependency.class; }
            @Override public String resolveComponentName(Class<?> c) { return "dep"; }
            @Override public String resolveControllerPath(Class<?> c) { return "/nodep"; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) {
                try {
                    Method m = NoDepController.class.getMethod("fail", HttpRequest.class, HttpResponse.class);
                    return java.util.Collections.singletonList(
                            new MethodRouteInfo("/fail", new HttpMethod[]{}, m));
                } catch (Exception e) { return java.util.Collections.emptyList(); }
            }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .annotationResolver(r)
                        .scanPackages("io.github.wycst.wastnet.http.annotation"));
    }

    @Test
    public void testRouterScanControllerBeanCatch() throws Exception {
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == FailingController.class || c == TestDependency.class; }
            @Override public boolean isController(Class<?> c) { return c == FailingController.class; }
            @Override public boolean isComponent(Class<?> c) { return c == TestDependency.class; }
            @Override public String resolveComponentName(Class<?> c) { return "dep"; }
            @Override public String resolveControllerPath(Class<?> c) { return "/fail"; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) {
                try {
                    Method m = FailingController.class.getMethod("handle");
                    return java.util.Collections.singletonList(
                            new MethodRouteInfo("/test", new HttpMethod[]{HttpMethod.GET}, m));
                } catch (Exception e) { return java.util.Collections.emptyList(); }
            }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .annotationResolver(r)
                        .scanPackages("io.github.wycst.wastnet.http.annotation"));
    }

    @Test
    public void testRouterScanWithBadWebSocket() {
        // BadWebSocket doesn't extend WebSocketResource -> ClassCastException caught
        // Use custom resolver that only accepts BadWebSocket
        AnnotationResolver resolver = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == BadWebSocket.class; }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return c == BadWebSocket.class; }
            @Override public String resolveWebSocketPath(Class<?> c) { return "/bad"; }
            @Override public boolean isController(Class<?> c) { return false; }
            @Override public boolean isComponent(Class<?> c) { return false; }
            @Override public String resolveControllerPath(Class<?> c) { return ""; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return java.util.Collections.emptyList(); }
            @Override public String resolveComponentName(Class<?> c) { return ""; }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .annotationResolver(resolver)
                        .scanPackages("io.github.wycst.wastnet.http.annotation"));
    }

    /** Real HTTP server request to exercise anonymous class handle() methods */
    @Test
    public void testRouterRealRequests() throws Exception {
        HttpMessageConverter conv = new HttpMessageConverter() {
            @Override public <T> T read(HttpRequest req, ConverterConfig cfg, Class<T> type) { return null; }
            @Override public void write(Object v, ConverterConfig cfg, HttpResponse resp) {}
        };
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(conv)
                .scanPackages("io.github.wycst.wastnet.http.annotation");
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        HTTPServer server = HTTPServer.of(51008).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            // Fast path: FastPathController.fastRoute(HttpRequest, HttpResponse)
            Response r1 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/fast/route").get().build()).execute();
            assertEquals(200, r1.code());
            r1.close();

            // General path: BasicController.hello() - no params
            Response r2 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/api/hello").get().build()).execute();
            assertEquals(200, r2.code());
            r2.close();

            // General path with body: BodyController.readBody(@RequestBody String)
            okhttp3.MediaType mt = okhttp3.MediaType.parse("text/plain");
            Response r3 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/body/read")
                    .post(okhttp3.RequestBody.create(mt, "test"))
                    .build()).execute();
            assertEquals(200, r3.code());
            r3.close();

            // Fast path with @ResponseBody: FastBodyController.handle(req,resp) -> L288-289
            Response r4 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/fastbody/get").get().build()).execute();
            assertEquals(200, r4.code());
            r4.close();

            // Mixed params: HttpRequest + @RequestBody -> L308-310 switch cases
            Response r5 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/mixed/echo")
                    .post(okhttp3.RequestBody.create(mt, "body"))
                    .build()).execute();
            assertEquals(200, r5.code());
            r5.close();

            // SSE endpoint -> L339-345 (uses SseOnlyController with SseEmitter param)
            Response r7 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/sse-only/stream").get().build()).execute();
            assertEquals(200, r7.code());
            r7.close();

            // Empty base path -> combinePath base.isEmpty() + path with /
            Response r8 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/test").get().build()).execute();
            assertEquals(200, r8.code());
            r8.close();

            // Empty base + path without / -> L398 false branch
            Response r8b = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/noslash").get().build()).execute();
            assertEquals(200, r8b.code());
            r8b.close();

            // Single HttpResponse param -> L302 false branch
            Response r9 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/responly/handle").get().build()).execute();
            assertEquals(200, r9.code());
            r9.close();
        } finally {
            server.shutdown();
        }
    }

    /** Invoke registered routes to exercise anonymous class handler code */
    @Test
    public void testRouterInvokeRoutes() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(mockConverter())
                .scanPackages("io.github.wycst.wastnet.http.annotation");

        // Get exactRoutes from parent class via reflection
        java.lang.reflect.Field exactRoutesField =
                io.github.wycst.wastnet.http.handler.HttpRouterHandler.class.getDeclaredField("exactRoutes");
        exactRoutesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, io.github.wycst.wastnet.http.handler.HttpRoute> routes =
                (java.util.Map<String, io.github.wycst.wastnet.http.handler.HttpRoute>)
                        exactRoutesField.get(handler);

        // Create mock request/response
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);

        // Invoke each registered route to cover anonymous class handle() methods
        for (java.util.Map.Entry<String, io.github.wycst.wastnet.http.handler.HttpRoute> entry : routes.entrySet()) {
            try {
                entry.getValue().handle(entry.getKey(), req, resp);
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== combinePath direct test via reflection ====================

    @Test
    public void testCombinePathReflection() throws Exception {
        Method combine = AnnotationRouterHandler.class.getDeclaredMethod("combinePath", String.class, String.class);
        combine.setAccessible(true);

        // Create a dummy handler to call combinePath
        AnnotationRouterHandler h = new AnnotationRouterHandler();

        // base=null + path="/abc" -> first if, path starts with /
        assertEquals("/abc", combine.invoke(h, (String) null, "/abc"));

        // base="/" + path="abc" -> first if, no leading /
        assertEquals("/abc", combine.invoke(h, "/", "abc"));

        // base="/api" + path=null -> second if, returns base
        assertEquals("/api", combine.invoke(h, "/api", (String) null));

        // base="/api" + path="" -> second if, returns base
        assertEquals("/api", combine.invoke(h, "/api", ""));

        // base="/api" + path="/" -> second if, returns base
        assertEquals("/api", combine.invoke(h, "/api", "/"));
    }

    // ==================== PackageScanner ====================

    @Test public void testPackageScannerScan() {
        List<Class<?>> classes = PackageScanner.scan("io.github.wycst.wastnet.http.annotation");
        assertTrue(classes.size() >= 22);
        assertTrue(classes.contains(AnnotationFilter.class));
    }

    @Test public void testPackageScannerWithFilter() {
        Set<Class<?>> result = PackageScanner.scan("io.github.wycst.wastnet.http.annotation",
                clazz -> AnnotationFilter.class.isAssignableFrom(clazz));
        assertTrue(result.contains(AnnotationFilter.class));
        assertTrue(result.contains(AnnotationResolver.class));
    }

    @Test public void testPackageScannerInvalid() {
        assertTrue(PackageScanner.scan("nonexistent.pkg", c -> true).isEmpty());
    }

    // ==================== ConfigLoader ====================

    @Test public void testConfigLoader() {
        Map<String, String> cfg = new HashMap<String, String>();
        ((ConfigLoader) config -> config.put("k", "v")).load(cfg);
        assertEquals("v", cfg.get("k"));
    }

    // ==================== HttpMessageConverter ====================

    @Test public void testHttpMessageConverter() throws Exception {
        HttpMessageConverter c = new HttpMessageConverter() {
            @Override public <T> T read(HttpRequest req, ConverterConfig cfg, Class<T> type) { return null; }
            @Override public void write(Object v, ConverterConfig cfg, HttpResponse resp) {}
        };
        assertNull(c.read(null, null, String.class));
    }

    // ==================== ConverterConfig ====================

    @Test public void testConverterConfig() {
        ConverterConfig cfg = new ConverterConfig();
        // Default values
        assertFalse(cfg.isPretty());
        assertFalse(cfg.isSkipNull());
        assertNull(cfg.getDateFormat());
        assertNotNull(cfg.getProperties());
        assertTrue(cfg.getProperties().isEmpty());
        // Chained setters (exercises return this)
        ConverterConfig c2 = cfg.pretty(true).skipNull(true).dateFormat("yyyy-MM-dd");
        assertSame(cfg, c2);
        assertTrue(cfg.isPretty());
        assertTrue(cfg.isSkipNull());
        assertEquals("yyyy-MM-dd", cfg.getDateFormat());
        // Individual properties
        ConverterConfig c3 = cfg.property("k1", "v1");
        assertSame(cfg, c3);
        ConverterConfig c4 = cfg.property("k2", "v2");
        assertSame(cfg, c4);
        assertEquals("v1", cfg.getProperty("k1"));
        assertEquals("v2", cfg.getProperty("k2"));
        assertNull(cfg.getProperty("missing"));
        assertEquals("default", cfg.getProperty("missing", "default"));
        // Bulk properties
        Map<String, String> props = new HashMap<String, String>();
        props.put("k3", "v3");
        ConverterConfig c5 = cfg.properties(props);
        assertSame(cfg, c5);
        assertEquals("v3", cfg.getProperty("k3"));
        assertEquals(3, cfg.getProperties().size());
        // two-arg overload with a present key -> covers the "v != null" branch
        assertEquals("v1", cfg.getProperty("k1", "default"));

        // ===== responseType (ContentType) =====
        // default -> JSON
        assertEquals(ContentType.JSON, cfg.getResponseType());
        assertEquals("application/json;charset=utf-8", cfg.getContentType());
        assertTrue(cfg.isJson());
        assertFalse(cfg.isTextual());
        assertFalse(cfg.isXml());
        assertFalse(cfg.isText());
        assertFalse(cfg.isHtml());
        assertFalse(cfg.isCustom());
        // TEXT -> textual
        ConverterConfig c6 = cfg.responseType(ContentType.TEXT);
        assertSame(cfg, c6);
        assertTrue(cfg.isText());
        assertTrue(cfg.isTextual());
        assertFalse(cfg.isJson());
        assertFalse(cfg.isXml());
        assertFalse(cfg.isHtml());
        assertFalse(cfg.isCustom());
        assertEquals("text/plain;charset=utf-8", cfg.getContentType());
        // XML -> textual
        cfg.responseType(ContentType.XML);
        assertTrue(cfg.isXml());
        assertTrue(cfg.isTextual());
        assertFalse(cfg.isText());
        // HTML -> textual
        cfg.responseType(ContentType.HTML);
        assertTrue(cfg.isHtml());
        assertTrue(cfg.isTextual());
        assertFalse(cfg.isXml());
        // CUSTOM -> no default content-type
        cfg.responseType(ContentType.CUSTOM);
        assertTrue(cfg.isCustom());
        assertFalse(cfg.isTextual());
        assertFalse(cfg.isJson());
        assertNull(cfg.getContentType());
        // null -> fall back to JSON (covers the responseType(null) branch)
        cfg.responseType(null);
        assertEquals(ContentType.JSON, cfg.getResponseType());
        assertTrue(cfg.isJson());
    }

    // ==================== All annotation types ====================

    @Test public void testAllAnnotations() {
        assertTrue(Controller.class.isAnnotation());
        assertTrue(Endpoint.class.isAnnotation());
        assertTrue(Component.class.isAnnotation());
        assertTrue(Sse.class.isAnnotation());
        assertTrue(WebSocket.class.isAnnotation());
        assertTrue(Bean.class.isAnnotation());
        assertTrue(Inject.class.isAnnotation());
        assertTrue(Value.class.isAnnotation());
        assertTrue(PreDestroy.class.isAnnotation());
        assertTrue(PostConstruct.class.isAnnotation());
        assertTrue(RequestBody.class.isAnnotation());
        assertTrue(ResponseBody.class.isAnnotation());
    }
}
