package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.routerfixtures.RouterFixtures.*;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.io.TempDir;

import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Dev hot-reload / dev-watcher / classloader-restart coverage for {@link DevHotReloader}
 * (exercised directly, since the test lives in the same package and the reloader's members are
 * package-private): dev-environment detection, main-class & default scan-package resolution, the dev
 * {@code WatchService} watcher (start/destroy guards, watch-exclude rules, {@code devWatchLoop}
 * branches via a fake {@link WatchService}), and the {@code RestartClassLoader}
 * rebuild on {@code reload(Path)}.
 *
 * <p>Skipped on JDK 8 via {@link DisabledOnJre} (the real file-watch / surefire manifest-jar boot
 * is unreliable there); extracted from {@code AnnotationRouterHandlerExtraTest} so the rest of the
 * suite still runs on JDK 8.
 */
@DisabledOnJre(JRE.JAVA_8)
public class DevHotReloaderTest {

    private static final String PKG = "io.github.wycst.wastnet.routerfixtures";

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
            public Object read(HttpRequest req, ConverterConfig cfg, java.lang.reflect.Type type) {
                return null;
            }

            @Override
            public void write(Object v, ConverterConfig cfg, HttpResponse resp) {
            }
        };
    }

    private static HttpRoute getExactRoute(AnnotationRouterHandler handler, String path) {
        return handler.exactRoutes().get(path);
    }

    private static BeanContainer beanContainerOf(AnnotationRouterHandler h) {
        return h.beanContainer;
    }

    private static ClassLoader baseLoader() {
        return DevHotReloader.getBaseClassLoader();
    }

    private static String setSunJavaCommand(String value) {
        String original = System.getProperty("sun.java.command");
        if (value == null) {
            System.clearProperty("sun.java.command");
        } else {
            System.setProperty("sun.java.command", value);
        }
        return original;
    }

    private static void restoreSunJavaCommand(String original) {
        if (original == null) {
            System.clearProperty("sun.java.command");
        } else {
            System.setProperty("sun.java.command", original);
        }
    }

    @Test
    public void testGetMainClassNameSunJavaCommand() {
        String original = setSunJavaCommand("com.example.Main");
        try {
            assertEquals("com.example.Main", DevHotReloader.getMainClassName());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testGetMainClassNameJarCommandFallsThrough() {
        String original = setSunJavaCommand("app.jar");
        try {
            assertDoesNotThrow(() -> DevHotReloader.getMainClassName());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testEffectiveScanPackagesConfigured() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.scanPackages("com.a", "com.b");
        assertArrayEquals(new String[]{"com.a", "com.b"}, h.effectiveScanPackages());
    }

    @Test
    public void testEffectiveScanPackagesDefaultBranch() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("com.example.Main");
        try {
            String[] pkgs = h.effectiveScanPackages();
            assertNotNull(pkgs);
            assertEquals(1, pkgs.length);
            assertEquals("com.example", pkgs[0]);
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testResolveDefaultScanPackageNoPackage() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("Main");
        try {
            assertNull(h.resolveDefaultScanPackage());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testGetMainClassNameEmptyCommand() {
        String original = setSunJavaCommand("   ");
        try {
            assertDoesNotThrow(() -> DevHotReloader.getMainClassName());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testIsDevEnvironmentCacheHit() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        boolean first = h.hotReloader.isDevEnvironment();
        assertEquals(first, h.hotReloader.isDevEnvironment());
    }

    @Test
    public void testDetectDevEnvironmentNoMainClass() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("app.jar");
        try {
            assertFalse(h.hotReloader.isDevEnvironment());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testHotReloadDisable() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.hotReload(false);
        assertTrue(h.hotReloader.hotReloadDisabled);
    }

    @Test
    public void testHotReloadEnable() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.hotReload(true);
        assertFalse(h.hotReloader.hotReloadDisabled);
    }

    @Test
    public void testCloseQuietlyUrlClassLoader() throws Exception {
        ClassLoader cl = new URLClassLoader(new URL[0], baseLoader());
        assertDoesNotThrow(() -> DevHotReloader.closeQuietly(cl));
        if (cl instanceof URLClassLoader) ((URLClassLoader) cl).close();
    }

    @Test
    public void testCloseQuietlyNonUrlClassLoader() {
        assertDoesNotThrow(() -> DevHotReloader.closeQuietly(baseLoader()));
    }

    @Test
    public void testReloadNotDevEnvironment() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("app.jar");
        try {
            assertDoesNotThrow(() -> h.hotReloader.reload((Path) null));
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testStartDevWatcherHotReloadDisabledGuard() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.hotReload(false);
        h.hotReloader.startWatcher();
        assertNull(h.hotReloader.devWatcher);
    }

    @Test
    public void testStartDevWatcherNotDevGuard() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("app.jar");
        try {
            h.hotReloader.startWatcher();
            assertNull(h.hotReloader.devWatcher);
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testDestroyDevWatcherNoWatcher() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertDoesNotThrow(() -> h.hotReloader.destroyWatcher());
    }

    @Test
    public void testScheduleReloadNoScheduler() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertDoesNotThrow(() -> h.hotReloader.scheduleReload((Path) null));
    }

    @Test
    public void testIsWatchExcludedBranches() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        DevHotReloader reloader = h.hotReloader;
        Path root = Paths.get(System.getProperty("java.io.tmpdir"), "wastnet-watch-root").toAbsolutePath();

        assertFalse(reloader.isWatchExcluded(root.resolve("com/foo"), root));
        assertFalse(reloader.isWatchExcluded(root, root));

        h.hotReloadWatchExclude("com.example.stable", "com.example.generated");
        assertTrue(reloader.isWatchExcluded(root.resolve("com/example/stable"), root));
        assertTrue(reloader.isWatchExcluded(root.resolve("com/example/stable/sub"), root));
        assertTrue(reloader.isWatchExcluded(root.resolve("com/example/generated"), root));
        assertFalse(reloader.isWatchExcluded(root.resolve("com/example/other"), root));
        assertFalse(reloader.isWatchExcluded(root.resolve("com/exampleX/stable"), root));

        h.hotReloadWatchExclude("com.example.stable", "", null);
        assertTrue(reloader.isWatchExcluded(root.resolve("com/example/stable"), root));
        assertFalse(reloader.isWatchExcluded(root.resolve("com/example/other"), root));
    }

    @Test
    public void testHotReloadWatchExcludeSetter() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertSame(h, h.hotReloadWatchExclude("a.b", "c.d"));
        assertArrayEquals(new String[]{"a.b".replace('.', File.separatorChar), "c.d".replace('.', File.separatorChar)},
                h.hotReloader.hotReloadWatchExcludes);
    }

    @Test
    public void testHotReloadWatchExcludeAfterPreparedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.prepared = true;
        assertThrows(IllegalStateException.class, () -> h.hotReloadWatchExclude("a.b"));
    }

    @Test
    public void testStartDevWatcherWithExcludesStarts() throws Exception {
        Path root = Files.createTempDirectory("wastnet-watch");
        Files.createDirectories(root.resolve("com/example/stable"));
        Files.createDirectories(root.resolve("com/example/other"));
        URL rootUrl = root.toUri().toURL();
        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[]{rootUrl}, prev));
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.hotReloadWatchExclude("com.example.stable");
        try {
            h.hotReloader.devEnvironment = Boolean.TRUE;
            h.hotReloader.startWatcher();
            assertNotNull(h.hotReloader.devWatcher);
        } finally {
            h.hotReloader.destroyWatcher();
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    @Test
    public void testRestartClassLoaderChildFirst(@TempDir File tempDir) throws Exception {
        String entry = "io/github/wycst/wastnet/http/annotation/AnnotationFilter.class";
        byte[] buf;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(entry)) {
            assertNotNull(in);
            buf = new byte[in.available()];
            in.read(buf);
        }
        File classFile = new File(tempDir, entry);
        classFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(classFile)) {
            fos.write(buf);
        }
        URL rootUrl = tempDir.toURI().toURL();

        ClassLoader base = baseLoader();
        ClassLoader rcl = new DevHotReloader.RestartClassLoader(new URL[]{rootUrl}, base);

        Class<?> childLoaded = rcl.loadClass("io.github.wycst.wastnet.http.annotation.AnnotationFilter");
        assertSame(rcl, childLoaded.getClassLoader());
        assertSame(childLoaded, rcl.loadClass("io.github.wycst.wastnet.http.annotation.AnnotationFilter"));

        Class<?> delegated = rcl.loadClass("java.lang.String");
        assertNotSame(rcl, delegated.getClassLoader());

        if (rcl instanceof URLClassLoader) ((URLClassLoader) rcl).close();
    }

    @WebSocket("")
    public static class WsEmptyPath extends WebSocketResource {
    }

    @WebSocket("/chat")
    public static class WsChat extends WebSocketResource {
    }

    public static class RespNoConverterController {
        @Endpoint("/rb")
        @ResponseBody
        public String rb() {
            return "x";
        }
    }

    @Controller
    public static class FastPathController {
        @Endpoint("/fp")
        public void fp(HttpRequest req, HttpResponse resp) {
        }
    }

    public static class BodyParamController {
        @Endpoint("/bp")
        public void bp(@RequestBody String body) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wsRoutesOf(AnnotationRouterHandler h) {
        try {
            Field f = Class.forName("io.github.wycst.wastnet.http.upgrade.DefaultUpgradeHandler")
                    .getDeclaredField("resourceHashMap");
            f.setAccessible(true);
            return (Map<String, Object>) f.get(h);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object beanField(BeanContainer bc, String name) {
        try {
            Field f = BeanContainer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(bc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }



    @Test
    public void testIsInListAndHasAnyAnnotation() throws Exception {
        Method bpM = BodyParamController.class.getMethod("bp", String.class);
        java.lang.annotation.Annotation rbAnn = bpM.getParameterAnnotations()[0][0];
        assertEquals(true, AnnotationRouteUtils.isInList(rbAnn, new Class[]{RequestBody.class}));
        assertEquals(false, AnnotationRouteUtils.isInList(rbAnn, new Class[]{ResponseBody.class}));

        assertEquals(true, AnnotationRouteUtils.hasAnyAnnotation(bpM.getParameterAnnotations()[0], new Class[]{RequestBody.class}));
        assertEquals(false, AnnotationRouteUtils.hasAnyAnnotation(bpM.getParameterAnnotations()[0], new Class[]{ResponseBody.class}));
    }

    @Test
    public void testRegisterWebSocketEndpointSkipsEmptyPath() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.registerWebSocketEndpoint(WsEmptyPath.class);
        assertTrue(wsRoutesOf(h).isEmpty());
    }

    @Test
    public void testRegisterWebSocketEndpointRegisters() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.registerWebSocketEndpoint(WsChat.class);
        assertTrue(wsRoutesOf(h).containsKey("/chat"));
    }

    @Test
    public void testConverterConfigResponseType() throws Exception {
        MethodRouteInfo withType = new MethodRouteInfo("/x", new HttpMethod[]{HttpMethod.GET},
                DevHotReloaderTest.class.getDeclaredMethod("testConverterConfigResponseType"), null, ContentType.JSON);
        ConverterConfig cfg = new ConverterConfig(withType);
        assertEquals(ContentType.JSON, cfg.getResponseType());

        MethodRouteInfo noType = new MethodRouteInfo("/y", new HttpMethod[]{HttpMethod.GET},
                DevHotReloaderTest.class.getDeclaredMethod("testConverterConfigResponseType"));
        assertNotNull(new ConverterConfig(noType));
    }

    @Test
    public void testClearResetsState() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(RequestParamController.class), true, false, false))
                .scanPackages(PKG);
        h.scan();
        assertNotNull(getExactRoute(h, "/rp/search"));
        h.clear();
        assertNull(getExactRoute(h, "/rp/search"));
        assertFalse(h.prepared);
    }

    @Test
    public void testScanPackagesAfterPreparedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(RequestParamController.class), true, false, false))
                .scanPackages(PKG);
        h.prepare();
        assertThrows(IllegalStateException.class, () -> h.scanPackages("x"));
    }

    @Test
    public void testScanAfterPreparedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(RequestParamController.class), true, false, false))
                .scanPackages(PKG);
        h.prepare();
        assertThrows(IllegalStateException.class, () -> h.scan());
    }

    @Test
    public void testPrepareNonDev() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .annotationResolver(acceptResolver(set(RequestParamController.class), true, false, false))
                .scanPackages(PKG);
        h.prepare();
        assertNotNull(getExactRoute(h, "/rp/search"));
        assertTrue(h.prepared);
    }

    @Test
    public void testBuildAppLoaderRestartClassLoader() throws Exception {
        File dir = File.createTempFile("wastnet-prj", "");
        dir.delete();
        dir.mkdirs();
        URL dirUrl = dir.toURI().toURL();
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader base = new URLClassLoader(new URL[]{dirUrl}, baseLoader())) {
            Thread.currentThread().setContextClassLoader(base);
            AnnotationRouterHandler h = new AnnotationRouterHandler();
            ClassLoader loader = h.hotReloader.buildAppLoader();
            assertTrue(loader instanceof URLClassLoader);
            assertNotSame(base, loader);
            if (loader instanceof URLClassLoader) ((URLClassLoader) loader).close();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }

    @Test
    public void testBuildAppLoaderUsesProjectDirs() throws Exception {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        URL jarUrl = Test.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader onlyJar = new URLClassLoader(new URL[]{jarUrl}, null);
        Thread.currentThread().setContextClassLoader(onlyJar);
        try {
            AnnotationRouterHandler h = new AnnotationRouterHandler();
            ClassLoader loader = h.hotReloader.buildAppLoader();
            assertNotSame(onlyJar, loader);
            assertTrue(loader instanceof DevHotReloader.RestartClassLoader);
            if (loader instanceof URLClassLoader) ((URLClassLoader) loader).close();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }

    @Test
    public void testDetectDevEnvironmentTrue() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            assertTrue(h.hotReloader.isDevEnvironment());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testResolveDefaultScanPackageWithPackage() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("com.example.sub.Main");
        try {
            assertEquals("com.example.sub", h.resolveDefaultScanPackage());
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testScheduleReloadWithScheduler() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        h.hotReloader.devScheduler = sch;
        try {
            DevHotReloader reloader = h.hotReloader;
            reloader.scheduleReload((Path) null);
            assertNotNull(reloader.devPendingReload);
        } finally {
            sch.shutdownNow();
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testStartAndDestroyDevWatcher() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReloader.startWatcher();
            assertNotNull(h.hotReloader.devWatcher);
            assertNotNull(h.hotReloader.devScheduler);
            h.hotReloader.destroyWatcher();
            assertNull(h.hotReloader.devWatcher);
            assertNull(h.hotReloader.devScheduler);
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testWatcherSchedulerThreadFactoryAndScheduledReloadRuns() throws Exception {
        String savedDebounce = System.getProperty("wastnet.http.hot-reload.debounce");
        System.setProperty("wastnet.http.hot-reload.debounce", "20");
        AnnotationRouterHandler h = new AnnotationRouterHandler().scanPackages(PKG);
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            DevHotReloader reloader = h.hotReloader;
            reloader.startWatcher();
            assertNotNull(reloader.devWatcher);
            reloader.scheduleReload(Paths.get("Foo.class"));
            Thread.sleep(400);
            assertNull(reloader.devPendingReload);
        } finally {
            h.hotReloader.destroyWatcher();
            restoreSunJavaCommand(original);
            if (savedDebounce != null) System.setProperty("wastnet.http.hot-reload.debounce", savedDebounce);
            else System.clearProperty("wastnet.http.hot-reload.debounce");
        }
    }

    @Test
    public void testDestroyWatcherCancelsPendingReload() {
        String savedDebounce = System.getProperty("wastnet.http.hot-reload.debounce");
        System.setProperty("wastnet.http.hot-reload.debounce", "60000");
        AnnotationRouterHandler h = new AnnotationRouterHandler().scanPackages(PKG);
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            DevHotReloader reloader = h.hotReloader;
            reloader.startWatcher();
            reloader.scheduleReload(Paths.get("Bar.class"));
            assertNotNull(reloader.devPendingReload);
            reloader.destroyWatcher();
            assertNull(reloader.devPendingReload);
            assertNull(reloader.devWatcher);
        } finally {
            restoreSunJavaCommand(original);
            if (savedDebounce != null) System.setProperty("wastnet.http.hot-reload.debounce", savedDebounce);
            else System.clearProperty("wastnet.http.hot-reload.debounce");
        }
    }

    @Test
    public void testConsoleOnReloadFalseViaPublicApi() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.routerfixtures.reloadpkg");
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReload(true, false);
            h.hotReloader.reload((Path) null);
            assertNotNull(h.hotReloader.scanClassLoader);
            assertNotNull(getExactRoute(h, "/rl/ping"));
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testReloadDevEnvironment() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.routerfixtures.reloadpkg");
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReloader.reload((Path) null);
            assertNotNull(h.hotReloader.scanClassLoader);
            assertNotNull(getExactRoute(h, "/rl/ping"));
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    private static AnnotationResolver throwingResolver() {
        AnnotationResolver boom = mock(AnnotationResolver.class);
        when(boom.accept(any())).thenThrow(new IllegalStateException("boom"));
        when(boom.isController(any())).thenThrow(new IllegalStateException("boom"));
        return boom;
    }

    @Test
    public void testReloadFailureRetainsPreviousLoader() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.routerfixtures.reloadpkg");
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReloader.reload((Path) null);
            ClassLoader before = h.hotReloader.scanClassLoader;
            assertNotNull(before);
            h.annotationResolver(throwingResolver());
            assertDoesNotThrow(() -> h.hotReloader.reload((Path) null));
            assertSame(before, h.hotReloader.scanClassLoader);
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testReloadFailureConsoleOffRetainsPreviousLoader() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.routerfixtures.reloadpkg");
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReload(true, false);
            h.hotReloader.reload((Path) null);
            ClassLoader before = h.hotReloader.scanClassLoader;
            assertNotNull(before);
            h.annotationResolver(throwingResolver());
            assertDoesNotThrow(() -> h.hotReloader.reload((Path) null));
            assertSame(before, h.hotReloader.scanClassLoader);
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Controller
    @WithInterceptor("noSuchInterceptorBean")
    static class UnknownInterceptorController {
        @Endpoint("/uic")
        public void uic() {
        }
    }

    @Controller
    @WithInterceptor("depBean")
    static class NonInterceptorBeanController {
        @Endpoint("/nib")
        public void nib() {
        }
    }

    @Test
    public void testRegisterControllerUnknownInterceptorBeanThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertThrows(RuntimeException.class, () -> h.registerController(UnknownInterceptorController.class));
    }

    @Test
    public void testRegisterControllerNonInterceptorBeanThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.processBeanMethods(set(NamedInjectConfig.class), new LinkedHashSet<Class<?>>());
        assertThrows(RuntimeException.class, () -> h.registerController(NonInterceptorBeanController.class));
    }

    @Test
    public void testReloadHotReloadDisabledGuard() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.hotReload(false);
        assertDoesNotThrow(() -> h.hotReloader.reload((Path) null));
        assertNull(h.hotReloader.scanClassLoader);
    }

    @Test
    public void testSecondReloadClosesPreviousLoader() {
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .scanPackages("io.github.wycst.wastnet.routerfixtures.reloadpkg");
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReloader.reload((Path) null);
            ClassLoader first = h.hotReloader.scanClassLoader;
            assertNotNull(first);
            h.hotReloader.reload((Path) null);
            assertNotNull(h.hotReloader.scanClassLoader);
            assertNotSame(first, h.hotReloader.scanClassLoader);
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testGetBaseClassLoaderIgnoresContextLoader() {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            assertSame(AnnotationRouterHandler.class.getClassLoader(), DevHotReloader.getBaseClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }

    @Test
    public void testScheduleReloadShutdownSchedulerGuard() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        sch.shutdown();
        h.hotReloader.devScheduler = sch;
        h.hotReloader.scheduleReload((Path) null);
        assertNull(h.hotReloader.devPendingReload);
    }

    @Test
    public void testScheduleReloadCancelsPending() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        h.hotReloader.devScheduler = sch;
        try {
            DevHotReloader reloader = h.hotReloader;
            reloader.scheduleReload(Paths.get("A.class"));
            Object first = reloader.devPendingReload;
            assertNotNull(first);
            reloader.scheduleReload(Paths.get("B.class"));
            assertNotNull(reloader.devPendingReload);
            assertNotSame(first, reloader.devPendingReload);
        } finally {
            sch.shutdownNow();
        }
    }

    @Test
    public void testStartWatcherTwiceKeepsSingleWatcher() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand(DevHotReloaderTest.class.getName());
        try {
            h.hotReloader.startWatcher();
            Object ws = h.hotReloader.devWatcher;
            assertNotNull(ws);
            h.hotReloader.startWatcher();
            assertSame(ws, h.hotReloader.devWatcher);
        } finally {
            h.hotReloader.destroyWatcher();
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testSettersUpdateFields() {
        HttpMessageConverter conv = mockConverter();
        AnnotationResolver res = new DefaultAnnotationResolver();
        AnnotationRouterHandler h = new AnnotationRouterHandler()
                .messageConverter(conv)
                .annotationResolver(res)
                .requestBodyBy(RequestBody.class)
                .responseBodyBy(ResponseBody.class)
                .pathParamBy(PathParam.class)
                .requestParamBy(RequestParam.class)
                .valueBy(Value.class)
                .injectBy(Inject.class)
                .postConstructBy(PostConstruct.class)
                .preDestroyBy(PreDestroy.class)
                .properties(Collections.singletonMap("p1", "v1"));

        assertSame(conv, h.converters.get(ContentType.JSON));
        assertSame(res, h.resolver);
        assertArrayEquals(new Class[]{RequestBody.class}, h.requestBodyAnnotations);
        assertArrayEquals(new Class[]{ResponseBody.class}, h.responseBodyAnnotations);
        assertArrayEquals(new Class[]{PathParam.class}, h.pathParamAnnotations);
        assertArrayEquals(new Class[]{RequestParam.class}, h.requestParamAnnotations);

        BeanContainer bc = beanContainerOf(h);
        assertArrayEquals(new Class[]{Value.class}, (Class[]) beanField(bc, "valueAnnotations"));
        assertArrayEquals(new Class[]{Inject.class}, (Class[]) beanField(bc, "injectAnnotations"));
        assertArrayEquals(new Class[]{PostConstruct.class}, (Class[]) beanField(bc, "postConstructAnnotations"));
        assertArrayEquals(new Class[]{PreDestroy.class}, (Class[]) beanField(bc, "preDestroyAnnotations"));
        Map<String, String> config = (Map<String, String>) beanField(bc, "staticConfig");
        assertEquals("v1", config.get("p1"));
    }

    @Test
    public void testRegisterControllerResponseBodyRequiresConverter() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertThrows(RuntimeException.class, () -> h.registerController(RespNoConverterController.class));
    }

    @Test
    public void testFastPathHandler() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.registerController(FastPathController.class);
        HttpRoute route = getExactRoute(h, "/fp");
        assertNotNull(route);
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        assertDoesNotThrow(() -> route.handle("/fp", req, resp));
    }

    @Test
    public void testBodyParamBinding() {
        AnnotationRouterHandler h = new AnnotationRouterHandler().messageConverter(mockConverter());
        h.registerController(BodyParamController.class);
        HttpRoute route = getExactRoute(h, "/bp");
        assertNotNull(route);
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        assertDoesNotThrow(() -> route.handle("/bp", req, resp));
    }

    public static class ScalarParamController {
        static int captured;
        @Endpoint("/sp")
        public void sp(@RequestParam("id") int id) {
            captured = id;
        }
    }

    public static class MultiParamController {
        static List<Integer> captured;
        @Endpoint("/mp")
        public void mp(@RequestParam("ids") List<Integer> ids) {
            captured = ids;
        }
    }

    public static class ValueComp {
        static String captured;
        public ValueComp(@Value("${app.name}") String name) {
            captured = name;
        }
    }

    public static class CompFixture {
    }

    abstract static class AbstractEligible {
    }

    static class PackagePrivateEligible {
    }

    @Test
    public void testIsEligibleClassBranches() {
        assertEquals(false, AnnotationRouteUtils.isEligibleClass(AbstractEligible.class));
        assertEquals(false, AnnotationRouteUtils.isEligibleClass(PackagePrivateEligible.class));
        assertEquals(true, AnnotationRouteUtils.isEligibleClass(FastPathController.class));
    }

    @Test
    public void testDoScanNoPackagesReturnsEarly() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        String original = setSunJavaCommand("Main");
        try {
            assertDoesNotThrow(() -> h.scan());
            assertNull(getExactRoute(h, "/anything"));
        } finally {
            restoreSunJavaCommand(original);
        }
    }

    @Test
    public void testRegisterComponentSuccess() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        assertDoesNotThrow(() -> h.registerComponent(CompFixture.class));
        assertNotNull(beanContainerOf(h).getBean(CompFixture.class));
    }

    @Test
    public void testProcessBeanMethodsConfigurationAndBean() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.processBeanMethods(set(NamedInjectConfig.class), new LinkedHashSet<Class<?>>());
        BeanContainer bc = beanContainerOf(h);
        assertEquals("DEP", bc.getBean("depBean"));
        assertEquals("DEP!", bc.getBean("combined"));
    }

    @Test
    public void testRequestParamScalarConversionAtRequest() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.registerController(ScalarParamController.class);
        HttpRoute route = getExactRoute(h, "/sp");
        assertNotNull(route);
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        when(req.getParameter("id")).thenReturn("42");
        ScalarParamController.captured = -1;
        assertDoesNotThrow(() -> route.handle("/sp", req, resp));
        assertEquals(42, ScalarParamController.captured);
    }

    @Test
    public void testRequestParamMultiConversionAtRequest() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        h.registerController(MultiParamController.class);
        HttpRoute route = getExactRoute(h, "/mp");
        assertNotNull(route);
        HttpRequest req = mock(HttpRequest.class);
        HttpResponse resp = mock(HttpResponse.class);
        when(req.getParameterValues("ids")).thenReturn(Arrays.asList("1", "2", "3"));
        assertDoesNotThrow(() -> route.handle("/mp", req, resp));
        assertEquals(Arrays.asList(1, 2, 3), MultiParamController.captured);
    }

    @Test
    public void testResolveParametersValueInjection() {
        AnnotationRouterHandler h = new AnnotationRouterHandler().property("app.name", "wastnet-test");
        h.registerComponent(ValueComp.class);
        assertEquals("wastnet-test", ValueComp.captured);
    }

    public static class UnresolvedConfig {
        @Bean
        public String need(@Inject("missing") String m) {
            return m;
        }
    }

    @Test
    public void testProcessBeanMethodsUnresolvedThrows() {
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> h.processBeanMethods(set(UnresolvedConfig.class), new LinkedHashSet<Class<?>>()));
        assertTrue(ex.getMessage().contains("Cannot resolve dependencies"));
    }

    static class FakeWatchEvent<T> implements WatchEvent<T> {
        private final T context;

        FakeWatchEvent(T context) {
            this.context = context;
        }

        @Override
        public Kind<T> kind() {
            return (Kind<T>) StandardWatchEventKinds.ENTRY_MODIFY;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public T context() {
            return context;
        }
    }

    private static final Path WATCH_DIR = Paths.get(".");

    static class FakeWatchKey implements WatchKey {
        private final Watchable watchable;
        private final List<? extends WatchEvent<?>> events;

        FakeWatchKey(Watchable watchable, List<? extends WatchEvent<?>> events) {
            this.watchable = watchable;
            this.events = events;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return (List) events;
        }

        @Override
        public boolean reset() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public Watchable watchable() {
            return watchable;
        }
    }

    static class FakeWatchService implements WatchService {
        private final Deque<WatchKey> keys = new ArrayDeque<>();
        private boolean throwInterrupted;
        private boolean throwClosed;

        void enqueue(WatchKey k) {
            keys.add(k);
        }

        @Override
        public WatchKey take() throws InterruptedException {
            if (throwInterrupted) throw new InterruptedException();
            if (throwClosed) throw new ClosedWatchServiceException();
            return keys.poll();
        }

        @Override
        public WatchKey poll() {
            return keys.poll();
        }

        @Override
        public WatchKey poll(long timeout, TimeUnit unit) {
            return keys.poll();
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void testDevWatchLoopEventProcessing() {
        FakeWatchService fs1 = new FakeWatchService();
        fs1.enqueue(new FakeWatchKey(WATCH_DIR, Collections.singletonList(
                new FakeWatchEvent<Path>(Paths.get("note.txt")))));
        AnnotationRouterHandler h1 = new AnnotationRouterHandler();
        ScheduledExecutorService sch1 = Executors.newSingleThreadScheduledExecutor();
        h1.hotReloader.devWatcher = fs1;
        h1.hotReloader.devScheduler = sch1;
        DevHotReloader reloader1 = h1.hotReloader;
        reloader1.devPendingReload = null;
        reloader1.devWatchLoop();
        assertNull(reloader1.devPendingReload);
        sch1.shutdownNow();

        FakeWatchService fs2 = new FakeWatchService();
        fs2.enqueue(new FakeWatchKey(WATCH_DIR, Collections.singletonList(
                new FakeWatchEvent<Path>(Paths.get("Foo.class")))));
        fs2.enqueue(new FakeWatchKey(WATCH_DIR, Collections.singletonList(
                new FakeWatchEvent<String>("not-a-path"))));
        AnnotationRouterHandler h2 = new AnnotationRouterHandler();
        ScheduledExecutorService sch2 = Executors.newSingleThreadScheduledExecutor();
        h2.hotReloader.devWatcher = fs2;
        h2.hotReloader.devScheduler = sch2;
        DevHotReloader reloader2 = h2.hotReloader;
        reloader2.devPendingReload = null;
        reloader2.devWatchLoop();
        assertNotNull(reloader2.devPendingReload);
        sch2.shutdownNow();
    }

    @Test
    public void testDevWatchLoopReloadTriggerPaths() {
        Path target = WATCH_DIR.resolve("app.properties").toAbsolutePath().normalize();
        FakeWatchService fs = new FakeWatchService();
        fs.enqueue(new FakeWatchKey(WATCH_DIR, Collections.singletonList(
                new FakeWatchEvent<Path>(Paths.get("app.properties")))));
        AnnotationRouterHandler h = new AnnotationRouterHandler();
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor();
        h.hotReloader.devWatcher = fs;
        h.hotReloader.devScheduler = sch;
        DevHotReloader reloader = h.hotReloader;
        reloader.devPendingReload = null;
        reloader.setReloadTriggerPaths(Collections.singleton(target.toString()));
        reloader.devWatchLoop();
        assertNotNull(reloader.devPendingReload);
        sch.shutdownNow();
    }

    @Test
    public void testDevWatchLoopTakeExceptions() {
        FakeWatchService fsI = new FakeWatchService();
        fsI.throwInterrupted = true;
        AnnotationRouterHandler hI = new AnnotationRouterHandler();
        hI.hotReloader.devWatcher = fsI;
        DevHotReloader reloaderI = hI.hotReloader;
        reloaderI.devWatchLoop();
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();

        FakeWatchService fsC = new FakeWatchService();
        fsC.throwClosed = true;
        AnnotationRouterHandler hC = new AnnotationRouterHandler();
        ScheduledExecutorService schC = Executors.newSingleThreadScheduledExecutor();
        hC.hotReloader.devWatcher = fsC;
        hC.hotReloader.devScheduler = schC;
        DevHotReloader reloaderC = hC.hotReloader;
        reloaderC.devPendingReload = null;
        reloaderC.devWatchLoop();
        assertNull(reloaderC.devPendingReload);
        schC.shutdownNow();
    }
}
