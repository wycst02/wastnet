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
 * Comprehensive for annotation package core classes.
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class AnnotationPackageTest {

    // ==================== Annotated test model classes ====================

    @Controller("/api")
    public static class BasicController {
        @Endpoint("/hello")
        public String hello() { return "hello"; }
        @Sse("/events")
        public void events(SseEmitter emitter) {}
    }

    @Controller("/fast")
    public static class PkgFastPathController {
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
    @WithInterceptor({"dup", "dup"})
    public static class InterceptorClassDupController {
        @Endpoint("/cd")
        public void cd(HttpRequest req, HttpResponse resp) {}
    }

    // Class-level absent and method-level value is only the empty string -> every entry is
    // filtered out, names stays empty, and resolveInterceptorNames returns null
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
        c.register("test", "hello", false);
        assertEquals("hello", c.getBean(String.class));
        assertNull(c.getBean(Integer.class));
        assertNotNull(c.getBeans());
        c.clearAll();
        assertNull(c.getBean(String.class));
    }

    // ==================== BeanContainer - @Bean (factory) registration ====================

    public interface BeanIfc {}
    public static class BeanImpl implements BeanIfc {}

    @Test public void testRegisterBean_singleLookup() {
        BeanContainer c = new BeanContainer();
        c.register("b", new BeanImpl(), true);
        assertNotNull(c.getBean(BeanImpl.class));
        assertNotNull(c.getBean(BeanIfc.class));
    }

    @Test public void testRegisterBean_multipleSameType() {
        BeanContainer c = new BeanContainer();
        BeanImpl a = new BeanImpl();
        BeanImpl b = new BeanImpl();
        c.register("a", a, true);
        c.register("b", b, true);
        assertSame(a, c.getBean("a"));
        assertSame(b, c.getBean("b"));
        // same concrete type, more than one name-qualified @Bean -> ambiguous by type
        assertThrows(IllegalStateException.class, () -> { c.getBean(BeanImpl.class); });
    }

    @Test public void testRegisterClassVsBeanSameType() {
        BeanContainer c = new BeanContainer();
        BeanImpl classBean = new BeanImpl();
        BeanImpl beanA = new BeanImpl();
        c.register("classBean", classBean, false);   // @Component: singleton
        c.register("beanA", beanA, true);            // @Bean: name-qualified
        assertSame(classBean, c.getBean(BeanImpl.class));   // class-level singleton wins
        assertSame(beanA, c.getBean("beanA"));
    }

    @Test public void testRegisterDuplicateName() {
        BeanContainer c = new BeanContainer();
        c.register("dup", new BeanImpl(), false);
        assertThrows(IllegalStateException.class, () -> { c.register("dup", new BeanImpl(), true); });
    }

    @Test public void testPropertyAndConfig() {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("k1", "v1");
        Map<String, String> props = new HashMap<String, String>();
        props.put("k2", "v2");
        c.addStaticProperties(props);
    }

    // ==================== BeanContainer - @Value injection ====================

    @Test public void testValueInjection_String() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setInjectAnnotations(new Class<?>[]{Inject.class});
        c.setPostConstructAnnotations(new Class<?>[]{PostConstruct.class});
        c.register("comp", new TestComponent(), false);
        assertNotNull(c.getBean(TestComponent.class));
    }

    @Test public void testValueInjection_AllTypes() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("exists", "configuredValue");
        c.register("vt", new ValueTypesBean(), false);
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
        c.register("dep", new TestDependency(), false);
        c.register("dependent", new DependentBean(), false);
        c.injectAllFields();
        assertNotNull(c.getBean(DependentBean.class));
    }

    @Test public void testInjectDependency_missing() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setResolver(new DefaultAnnotationResolver());
        c.setInjectAnnotations(new Class<?>[]{Inject.class});
        c.register("dependent", new DependentBean(), false);
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

    // ==================== BeanContainer - static vs scan config ====================

    @Test public void testStaticConfigSurvivesClearScan() {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("static.key", "sv");
        c.loadScanProperties("test-config.properties");
        assertEquals("sv", c.getConfig("static.key"));
        assertEquals("wastnet-test", c.getConfig("app.name"));
        // Hot reload clears only scan config; static config persists.
        c.clearScan();
        assertEquals("sv", c.getConfig("static.key"));
        assertNull(c.getConfig("app.name"));
        // Full teardown drops everything.
        c.clearAll();
        assertNull(c.getConfig("static.key"));
    }

    @Test public void testScanConfigOverridesStatic() {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("app.name", "override");
        c.loadScanProperties("test-config.properties");
        // Entries from the scan config file win over programmatic static values.
        assertEquals("wastnet-test", c.getConfig("app.name"));
    }

    @Test public void testSystemPropertyOverridesConfig() {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("sp.key", "staticVal");
        c.loadScanProperties("test-config.properties");
        // No -D set yet: scan config wins.
        assertEquals("wastnet-test", c.getConfig("app.name"));
        // -D system property takes the highest priority.
        String name = "wastnet.test.sysprop." + System.nanoTime();
        System.setProperty(name, "sysVal");
        try {
            c.setStaticProperty(name, "staticVal");
            c.loadScanProperties("test-config.properties");
            assertEquals("sysVal", c.getConfig(name));
        } finally {
            System.clearProperty(name);
        }
    }

    // ==================== BeanContainer - hasAnyAnnotation edge cases ====================

    @Test public void testHasAnyAnnotation_nullAnn() throws Exception {
        BeanContainer c = new BeanContainer();
        c.setResolver(new DefaultAnnotationResolver());
        c.setInjectAnnotations(new Class<?>[]{null, Inject.class});
        c.register("dep", new TestDependency(), false);
        c.register("dependent", new DependentBean(), false);
        c.injectAllFields();
        assertNotNull(c.getBean(DependentBean.class));
    }

    // ==================== BeanContainer - resolvePlaceholder ====================

    @Test public void testResolvePlaceholder() {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("existing.key", "resolvedValue");

        // null raw -> null
        assertNull(c.resolvePlaceholder(null));

        // no placeholder -> same string
        assertEquals("plaintext", c.resolvePlaceholder("plaintext"));

        // unclosed ${ -> rest of string as-is
        assertEquals("${unclosed", c.resolvePlaceholder("${unclosed"));

        // key:default -> uses config when key exists
        assertEquals("resolvedValue", c.resolvePlaceholder("${existing.key:fallback}"));

        // key:default -> uses default when key missing
        assertEquals("fallbackVal", c.resolvePlaceholder("${missing.key:fallbackVal}"));

        // key without default and missing -> throws (fail-fast on missing config)
        assertThrows(IllegalStateException.class, () -> c.resolvePlaceholder("${noDefault}"));

        // multiple placeholders
        assertEquals("a-resolvedValue-b-fallback-c",
                c.resolvePlaceholder("a-${existing.key}-b-${x:fallback}-c"));

        // ── boundary: empty string ──
        assertEquals("", c.resolvePlaceholder(""));

        // ── boundary: no '${' at all (already plain) is covered above; unclosed '${' with prefix but no resolved part ──
        assertEquals("abc${unclosed", c.resolvePlaceholder("abc${unclosed"));

        // ── boundary: unclosed '${' AFTER a resolved placeholder (sb != null branch) ──
        assertEquals("resolvedValue-${unclosed", c.resolvePlaceholder("${existing.key:fb}-${unclosed"));

        // ── boundary: empty inner ${} (no key, no default) -> throws ──
        assertThrows(IllegalStateException.class, () -> c.resolvePlaceholder("${}"));

        // ── boundary: default value is empty ──
        assertEquals("", c.resolvePlaceholder("${missing:}"));

        // ── boundary: default value itself contains ':' ──
        assertEquals("a:b", c.resolvePlaceholder("${missing:a:b}"));

        // ── boundary: trailing extra '}' is kept literally ──
        assertEquals("resolvedValue}", c.resolvePlaceholder("${existing.key}}"));

        // ── boundary: adjacent placeholders ──
        assertEquals("resolvedValuefallback", c.resolvePlaceholder("${existing.key}${x:fallback}"));
    }

    // ==================== BeanContainer - convertValue ====================

    @Test public void testConvertValue() {
        // null -> null
        assertNull(BeanContainer.convertValue(null, String.class));

        // String
        assertEquals("abc", BeanContainer.convertValue("abc", String.class));

        // int/Integer
        assertEquals(42, BeanContainer.convertValue("42", int.class));
        assertEquals(42, BeanContainer.convertValue("42", Integer.class));

        // long/Long
        assertEquals(99L, BeanContainer.convertValue("99", long.class));
        assertEquals(99L, BeanContainer.convertValue("99", Long.class));

        // boolean/Boolean
        assertEquals(true, BeanContainer.convertValue("true", boolean.class));
        assertEquals(true, BeanContainer.convertValue("true", Boolean.class));

        // double/Double
        assertEquals(3.14, (Double) BeanContainer.convertValue("3.14", double.class), 0.001);
        assertEquals(3.14, (Double) BeanContainer.convertValue("3.14", Double.class), 0.001);

        // float/Float
        assertEquals(2.5f, (Float) BeanContainer.convertValue("2.5", float.class), 0.001f);
        assertEquals(2.5f, (Float) BeanContainer.convertValue("2.5", Float.class), 0.001f);

        // short/Short
        assertEquals((short) 10, BeanContainer.convertValue("10", short.class));
        assertEquals((short) 10, BeanContainer.convertValue("10", Short.class));

        // byte/Byte
        assertEquals((byte) 7, BeanContainer.convertValue("7", byte.class));
        assertEquals((byte) 7, BeanContainer.convertValue("7", Byte.class));

        // Unsupported type -> null (no ClassCastException)
        assertNull(BeanContainer.convertValue("any", Object.class));
        assertNull(BeanContainer.convertValue("42", StringBuilder.class));

        // bad numeric input -> NumberFormatException (config error surfaces loudly)
        assertThrows(NumberFormatException.class, () -> BeanContainer.convertValue("${noDefault}", int.class));
        assertThrows(NumberFormatException.class, () -> BeanContainer.convertValue("not-a-number", long.class));
    }

    // ==================== BeanContainer - resolveValue ====================

    @Test public void testResolveValue() {
        BeanContainer c = new BeanContainer();
        c.setStaticProperty("existing.key", "resolvedValue");
        c.setStaticProperty("int.key", "7");

        // null expression -> null for any target type
        assertNull(c.resolveValue(null, String.class));
        assertNull(c.resolveValue(null, int.class));

        // plain string (no placeholder) passed through conversion
        assertEquals("hello", c.resolveValue("hello", String.class));
        assertEquals(123, c.resolveValue("123", int.class));
        assertEquals(true, c.resolveValue("true", boolean.class));

        // resolved placeholder then converted
        assertEquals("resolvedValue", c.resolveValue("${existing.key}", String.class));
        assertEquals(7, c.resolveValue("${int.key}", int.class));

        // placeholder with default, key missing -> default converted to numeric
        assertEquals(42, c.resolveValue("${missing.key:42}", int.class));

        // multiple placeholders resolved and concatenated as a string
        assertEquals("1-2", c.resolveValue("${a:1}-${b:2}", String.class));

        // unresolved placeholder, String target -> throws (fail-fast on missing config)
        assertThrows(IllegalStateException.class, () -> c.resolveValue("${noDefault}", String.class));

        // unresolved placeholder, numeric target -> IllegalStateException (fails before conversion)
        assertThrows(IllegalStateException.class, () -> c.resolveValue("${noDefault}", int.class));
    }

    // ==================== BeanContainer - invokeLifecycle exception ====================

    public static class LifecycleExceptionBean {
        @PostConstruct
        public void init() { throw new RuntimeException("init failed"); }
    }

    @Test public void testInvokeLifecycleException() {
        BeanContainer c = new BeanContainer();
        c.setPostConstructAnnotations(new Class<?>[]{PostConstruct.class});
        c.register("fail", new LifecycleExceptionBean(), false);
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
            @Override public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) { return null; }
            @Override public void write(Object v, ConverterConfig cfg, HttpResponse resp) {}
        };
        assertNotNull(new AnnotationRouterHandler().messageConverter(conv));
    }

    @Test public void testRouterPropertiesAndConfig() {
        Map<String, String> m = new HashMap<String, String>(); m.put("p1", "v1");
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.properties(m);
        h.clear();
    }

    // Controllers for real request branch
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
            @Override public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) { return null; }
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
                        .scanPackages("io.github.wycst.wastnet.http.annotation")
                        .scan());
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
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .annotationResolver(r)
                        .scanPackages("io.github.wycst.wastnet.http.annotation")
                        .scan());
    }

    @Test
    public void testRouterScanProcessBeanException() throws Exception {
        ExceptionBeanProducer bean = new ExceptionBeanProducer();
        Method failMethod = ExceptionBeanProducer.class.getMethod("failBean");
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertThrows(RuntimeException.class,
                () -> AnnotationRouteUtils.tryProcessBeanMethod(bean, failMethod, "", h.beanContainer, h.resolver));
    }

    @Test
    public void testRouterScanMissingBeanDep() throws Exception {
        Method needDep = MissingDepBeanProducer.class.getMethod("needDep", Integer.class);
        AnnotationRouterHandler handler = new AnnotationRouterHandler();
        // A bare (non-@Inject) parameter that cannot be resolved is now deferred
        // (returns null) instead of throwing, so it can be retried during scan.
        assertNull(AnnotationRouteUtils.resolveParameters(needDep.getParameters(), "test", handler.beanContainer, handler.resolver));
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
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
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
        // which never gets registered — once retries are exhausted the exception is thrown (lines 211-214).
        AnnotationResolver r = new AnnotationResolver() {
            @Override public boolean accept(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.ExhaustRetryConfig.class; }
            @Override public boolean isComponent(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.ExhaustRetryConfig.class; }
            @Override public String resolveComponentName(Class<?> c) { return "exhaustConfig"; }
            @Override public boolean isController(Class<?> c) { return false; }
            @Override public boolean isWebSocketEndpoint(Class<?> c) { return false; }
            @Override public String resolveControllerPath(Class<?> c) { return ""; }
            @Override public String resolveWebSocketPath(Class<?> c) { return ""; }
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
            @Override public boolean isConfiguration(Class<?> c) { return c == testonly.retrybeans.BeanDeferredConfig.ExhaustRetryConfig.class; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation ann) { return ((Value) ann).value(); }
            @Override public String resolveInjectName(java.lang.annotation.Annotation ann) { return ((Inject) ann).value(); }
            @Override public String resolveBeanName(java.lang.annotation.Annotation ann) { return ((Bean) ann).value(); }
        };
        // When a @Bean dependency can never be resolved, scan() must fail once retries are
        // exhausted. Only the exception *type* is asserted — the message wording is an
        // implementation detail that may change, so it is intentionally not checked here.
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .injectBy(Inject.class)
                        .annotationResolver(r)
                        .scanPackages("testonly.retrybeans")
                        .scan());
    }

    // A @Configuration whose @Bean method reads an @Value field: the field must be injected before the
    // @Bean method runs, so the produced bean reflects the resolved property.
    @Test
    public void testRouterScanConfigValueInjectedBeforeBean() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("testonly.valuebean");
        h.scan();
        testonly.valuebean.ValueBeanConfig.MyService svc = h.beanContainer.getBean(testonly.valuebean.ValueBeanConfig.MyService.class);
        assertNotNull(svc);
        assertEquals("default-url", svc.url);
        testonly.valuebean.ValueBeanConfig.ValueBeforeBeanConfig cfg = h.beanContainer.getBean(testonly.valuebean.ValueBeanConfig.ValueBeforeBeanConfig.class);
        assertNotNull(cfg);
        assertEquals("default-url", cfg.getUrl());
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
                    return Collections.singletonList(
                            new MethodRouteInfo("/fail", new HttpMethod[]{}, m));
                } catch (Exception e) { return Collections.emptyList(); }
            }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
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
                        .scanPackages("io.github.wycst.wastnet.http.annotation")
                        .scan());
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
                    return Collections.singletonList(
                            new MethodRouteInfo("/test", new HttpMethod[]{HttpMethod.GET}, m));
                } catch (Exception e) { return Collections.emptyList(); }
            }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
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
                        .scanPackages("io.github.wycst.wastnet.http.annotation")
                        .scan());
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
            @Override public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> c) { return Collections.emptyList(); }
            @Override public List<MethodRouteInfo> resolveSseEndpoints(Class<?> c) { return Collections.emptyList(); }
            @Override public String resolveComponentName(Class<?> c) { return ""; }
            @Override public boolean isConfiguration(Class<?> c) { return false; }
            @Override public String resolveValueExpression(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveInjectName(java.lang.annotation.Annotation a) { return null; }
            @Override public String resolveBeanName(java.lang.annotation.Annotation a) { return null; }
        };
        assertThrows(RuntimeException.class, () ->
                new AnnotationRouterHandler()
                        .annotationResolver(resolver)
                        .scanPackages("io.github.wycst.wastnet.http.annotation")
                        .scan());
    }

    /** Real HTTP server request to exercise anonymous class handle() methods */
    @Test
    public void testRouterRealRequests() throws Exception {
        HttpMessageConverter conv = new HttpMessageConverter() {
            @Override public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) { return null; }
            @Override public void write(Object v, ConverterConfig cfg, HttpResponse resp) {}
        };
        AnnotationRouterHandler handler = new AnnotationRouterHandler()
                .messageConverter(conv)
                .property("null.val", "defaultNull")
                .property("exists", "existsValue")
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
            MediaType mt = MediaType.parse("text/plain");
            Response r3 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/body/read")
                    .post(okhttp3.RequestBody.create(mt, "test"))
                    .build()).execute();
            assertEquals(200, r3.code());
            r3.close();

            Response r4 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/fastbody/get").get().build()).execute();
            assertEquals(200, r4.code());
            r4.close();

            Response r5 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/mixed/echo")
                    .post(okhttp3.RequestBody.create(mt, "body"))
                    .build()).execute();
            assertEquals(200, r5.code());
            r5.close();

            Response r7 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/sse-only/stream").get().build()).execute();
            assertEquals(200, r7.code());
            r7.close();

            // Empty base path -> combinePath base.isEmpty() + path with /
            Response r8 = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/test").get().build()).execute();
            assertEquals(200, r8.code());
            r8.close();

            Response r8b = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:51008/noslash").get().build()).execute();
            assertEquals(200, r8b.code());
            r8b.close();

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

        // exactRoutes is protected in HttpRouterHandler; exposed via same-package accessor
        @SuppressWarnings("unchecked")
        Map<String, io.github.wycst.wastnet.http.handler.HttpRoute> routes =
                (Map<String, io.github.wycst.wastnet.http.handler.HttpRoute>)
                        handler.exactRoutes();

        // Create mock request/response
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);

        // Invoke each registered route to cover anonymous class handle() methods
        for (Map.Entry<String, io.github.wycst.wastnet.http.handler.HttpRoute> entry : routes.entrySet()) {
            try {
                entry.getValue().handle(entry.getKey(), req, resp);
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== combinePath scan-driven coverage ====================

    // A @Controller whose base path carries a trailing slash ("/api/") must still register a
    // single-slash route and dispatch a client-style "/api/users" request. This exercises the
    // combinePath trailing-slash edge case through the real scan -> register -> dispatch flow,
    // which the old direct static calls never covered (they omitted the trailing-slash input).
    @Test
    public void testRouterScanTrailingBaseResolvesClientUri() throws Throwable {
        io.github.wycst.wastnet.http.annotation.trailing.TrailingBaseController.invoked = false;
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.http.annotation.trailing");
        h.scan();

        @SuppressWarnings("unchecked")
        Map<String, io.github.wycst.wastnet.http.handler.HttpRoute> routes =
                (Map<String, io.github.wycst.wastnet.http.handler.HttpRoute>) h.exactRoutes();

        // base "/api/" + "/users" must normalize to "/api/users", never "//"
        assertTrue(routes.containsKey("/api/users"),
                "expected /api/users registered, got: " + routes.keySet());
        assertFalse(routes.containsKey("/api//users"),
                "trailing-slash base must not produce a double-slash route key");

        // a client-style single-slash URI must dispatch to the endpoint
        io.github.wycst.wastnet.http.handler.HttpRoute route = routes.get("/api/users");
        assertNotNull(route, "route for /api/users should be registered");
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        route.handle("/api/users", req, resp);
        assertTrue(io.github.wycst.wastnet.http.annotation.trailing.TrailingBaseController.invoked,
                "endpoint should be invoked for client URI /api/users");
    }

    // ==================== combinePath branch coverage (scan-driven) ====================

    // Every combinePath branch, exercised through the real scan -> register flow because the
    // method is now private. Mirrors the assertions the old direct static tests used to make.
    @Test
    public void testCombinePathBranchesCoveredByScan() throws Throwable {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.http.annotation.pathbranches");
        h.scan();
        @SuppressWarnings("unchecked")
        Map<String, io.github.wycst.wastnet.http.handler.HttpRoute> routes =
                (Map<String, io.github.wycst.wastnet.http.handler.HttpRoute>) h.exactRoutes();

        // base "" + "/empty" -> first branch -> "/empty"
        assertTrue(routes.containsKey("/empty"), "empty base + /empty -> /empty, got: " + routes.keySet());
        // base "/" + "/root" -> first branch -> "/root"
        assertTrue(routes.containsKey("/root"), "root base / + /root -> /root, got: " + routes.keySet());
        // base "/pSlash" + "/" -> path starts with "/" -> "/pSlash/"
        assertTrue(routes.containsKey("/pSlash/"), "base /pSlash + path / -> /pSlash/, got: " + routes.keySet());
        // base "/pNoLead" + "sub" -> no leading slash -> "/pNoLead/sub"
        assertTrue(routes.containsKey("/pNoLead/sub"),
                "base /pNoLead + sub -> /pNoLead/sub, got: " + routes.keySet());

        // no branch may ever leak a double slash into a route key
        for (String key : routes.keySet()) {
            assertFalse(key.contains("//"), "route key must not contain double slash: " + key);
        }
    }

    // ==================== PackageScanner ====================

    @Test public void testPackageScannerScan() {
        Set<Class<?>> classes = PackageScanner.scan(c -> true, Thread.currentThread().getContextClassLoader(), "io.github.wycst.wastnet.http.annotation");
        assertTrue(classes.size() >= 22);
        assertTrue(classes.contains(AnnotationFilter.class));
    }

    @Test public void testPackageScannerWithFilter() {
        Set<Class<?>> result = PackageScanner.scan(clazz -> AnnotationFilter.class.isAssignableFrom(clazz),
                Thread.currentThread().getContextClassLoader(),
                "io.github.wycst.wastnet.http.annotation");
        assertTrue(result.contains(AnnotationFilter.class));
        assertTrue(result.contains(AnnotationResolver.class));
    }

    @Test public void testPackageScannerInvalid() {
        assertTrue(PackageScanner.scan(c -> true, Thread.currentThread().getContextClassLoader(), "nonexistent.pkg").isEmpty());
    }

    // ==================== HttpMessageConverter ====================

    @Test public void testHttpMessageConverter() throws Exception {
        HttpMessageConverter c = new HttpMessageConverter() {
            @Override public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) { return null; }
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
