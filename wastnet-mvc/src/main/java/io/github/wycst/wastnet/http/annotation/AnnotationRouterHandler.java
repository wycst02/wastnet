package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;

import static io.github.wycst.wastnet.http.annotation.AnnotationRouteUtils.*;

/**
 * An extension of {@link HttpRouterHandler} that automatically scans packages
 * for annotated controllers and components, then registers them as routes.
 * <p>
 * Usage:
 * <pre>{@code
 * HTTPServer server = HTTPServer.of(8080);
 * server.requestHandler(
 *     new AnnotationRouterHandler()
 *         .scanPackages("com.example.controller")
 * );
 * server.start();
 * }</pre>
 * <p>
 * To bridge third-party annotations (e.g. Spring Boot), provide a custom
 * {@link AnnotationResolver} via {@link #annotationResolver(AnnotationResolver)}.
 *
 * @author wangyc
 */
public class AnnotationRouterHandler extends HttpRouterHandler {

    private static final Log log = LogFactory.getLog(AnnotationRouterHandler.class);

    final BeanContainer beanContainer;

    private final Set<Class<? extends Annotation>> enabledAnnotations = new LinkedHashSet<>();
    private final List<BeanRegistrationHandler> registrationHandlers = new ArrayList<>();
    // View resolvers for non-@ResponseBody return values; first match wins, else built-in fallback.
    private final List<ViewResolver> viewResolvers = new ArrayList<>();

    AnnotationResolver resolver;
    final Map<ContentType, HttpMessageConverter> converters = new EnumMap<>(ContentType.class);
    Class<?>[] requestBodyAnnotations = new Class<?>[]{RequestBody.class};
    Class<?>[] responseBodyAnnotations = new Class<?>[]{ResponseBody.class, RestController.class};
    Class<?>[] pathParamAnnotations = new Class<?>[]{PathParam.class};
    Class<?>[] requestParamAnnotations = new Class<?>[]{RequestParam.class};
    Class<?>[] headerParamAnnotations = new Class<?>[]{RequestHeader.class};

    private String[] scanPackages;
    private volatile String defaultScanPackage;
    boolean prepared;

    // Classpath config files auto-loaded each scan (incl. hot reload); feed @Value injection. Default: application.properties.
    private String[] configFiles = new String[]{"application.properties"};

    // If true, skip jar-internal config and load only external overrides (JAR dir, JAR /config, parent /config). Default false.
    private boolean ignoreInternalConfig;

    // Component proxy hook: requiresProxy gates wrapping, enhance builds the proxy (@Component/@Controller only; @Bean excluded). Null = disabled.
    private ComponentEnhancer componentEnhancer;

    // Dev hot-reload engine; re-scans in place. Pass a custom DevHotReloader.ReloadAction (e.g. rebuild-and-swap) to use a different strategy.
    final DevHotReloader hotReloader = new DevHotReloader((cl, trigger) -> doScanAndReprepare());

    // Context-prefixed paths of WebSocket endpoints registered by scanning; cleared (not all) on hot reload.
    private final Set<String> scannedUpgradePaths = new HashSet<>();
    // Interceptors registered from scanning; cleared (not all) on hot reload.
    private final Set<RouterInterceptor> scannedInterceptors = new HashSet<>();

    // Parameter-kind tags for precomputed route argument metadata (0 reserved = no kind matched).
    private static final int KIND_REQUEST = 1;
    private static final int KIND_RESPONSE = 2;
    private static final int KIND_BODY = 3;
    private static final int KIND_PATH = 4;
    private static final int KIND_PARAM = 5;
    private static final int KIND_HEADER = 6; // @RequestHeader parameter slot
    private static final int KIND_SSE = 7; // SseEmitter parameter slot; only on @Sse endpoints

    public AnnotationRouterHandler() {
        resolver = new DefaultAnnotationResolver();
        beanContainer = new BeanContainer();
        beanContainer.setResolver(resolver);
        // Pre-register the dependency-free TEXT converter so plain-text @ResponseBody
        // endpoints work out of the box. Override via messageConverter(ContentType.TEXT, ...).
        converters.put(ContentType.TEXT, new TextMessageConverter());
        applyReloadTrigger();
    }

    Map<String, HttpRoute> exactRoutes() {
        return exactRoutes;
    }

    List<RouterInterceptor> interceptors() {
        return interceptors;
    }

    /**
     * Set the annotation classes recognized as {@code @RequestBody}.
     * <p>
     * Default is the framework's own {@code @RequestBody}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.RequestBody.class}
     * to bridge existing code.
     */
    public AnnotationRouterHandler requestBodyBy(Class<?>... anns) {
        this.requestBodyAnnotations = anns;
        return this;
    }

    /**
     * Set the annotation classes recognized as {@code @ResponseBody}.
     * <p>
     * An annotation present on a controller class applies to all of its
     * {@code @Endpoint} methods (e.g. {@code @RestController}); an annotation
     * present on a method applies to that method only.
     * <p>
     * Default is the framework's own {@code @ResponseBody} and
     * {@code @RestController}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.ResponseBody.class} and/or
     * {@code org.springframework.web.bind.annotation.RestController.class} to
     * bridge existing code.
     */
    public AnnotationRouterHandler responseBodyBy(Class<?>... anns) {
        this.responseBodyAnnotations = anns;
        return this;
    }

    /**
     * Set the annotation classes recognized as a path variable binding.
     * <p>
     * Default is the framework's own {@code @PathParam}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.PathVariable.class}
     * to bridge existing code (its {@code value()} is read as the variable name).
     */
    public AnnotationRouterHandler pathParamBy(Class<?>... anns) {
        this.pathParamAnnotations = anns;
        return this;
    }

    /**
     * Set the annotation classes recognized as a request (query/form) parameter binding.
     * <p>
     * Default is the framework's own {@code @RequestParam}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.RequestParam.class} to bridge
     * existing code (its {@code value()}, {@code required()} and {@code defaultValue()}
     * are read reflectively; Spring's {@code defaultValue} sentinel is treated as none).
     */
    public AnnotationRouterHandler requestParamBy(Class<?>... anns) {
        this.requestParamAnnotations = anns;
        return this;
    }

    /**
     * Set the annotation classes recognized as {@code @RequestHeader}.
     * <p>
     * Default is the framework's own {@code @RequestHeader}. Pass Spring's
     * {@code org.springframework.web.bind.annotation.RequestHeader.class} to bridge existing code
     * (its {@code value()}, {@code required()} and {@code defaultValue()} are read reflectively).
     */
    public AnnotationRouterHandler headerParamBy(Class<?>... anns) {
        this.headerParamAnnotations = anns;
        return this;
    }

    /**
     * Set the annotation classes recognized for {@code @Value} property injection.
     * <p>
     * Default is the framework's own {@code @Value}. Pass Spring's
     * {@code org.springframework.beans.factory.annotation.Value.class}
     * to bridge existing code.
     */
    public AnnotationRouterHandler valueBy(Class<?>... anns) {
        beanContainer.setValueAnnotations(anns);
        return this;
    }

    /**
     * Set the annotation classes used for dependency injection.
     * <p>
     * Default is {@link Inject @Inject}.
     * Pass e.g. Spring's {@code Autowired.class} to bridge third-party annotations.
     */
    public AnnotationRouterHandler injectBy(Class<?>... anns) {
        beanContainer.setInjectAnnotations(anns);
        return this;
    }

    /**
     * Set the annotation classes recognized as {@code @Bean} (factory-method bean definitions).
     * <p>
     * Default is {@link Bean @Bean}. Pass e.g. Spring's
     * {@code org.springframework.context.annotation.Bean} to bridge third-party annotations.
     */
    public AnnotationRouterHandler beanBy(Class<?>... anns) {
        beanContainer.setBeanAnnotations(anns);
        return this;
    }

    /**
     * Set the annotation classes used for initialization callbacks.
     * <p>
     * Default is {@link PostConstruct @PostConstruct}.
     */
    public AnnotationRouterHandler postConstructBy(Class<?>... anns) {
        beanContainer.setPostConstructAnnotations(anns);
        return this;
    }

    /**
     * Set the annotation classes used for destruction callbacks.
     * <p>
     * Default is {@link PreDestroy @PreDestroy}.
     */
    public AnnotationRouterHandler preDestroyBy(Class<?>... anns) {
        beanContainer.setPreDestroyAnnotations(anns);
        return this;
    }

    /**
     * Set a configuration property available for {@code @Value} injection.
     */
    public AnnotationRouterHandler property(String key, String value) {
        beanContainer.setStaticProperty(key, value);
        return this;
    }

    /**
     * Set multiple configuration properties available for {@code @Value} injection.
     */
    public AnnotationRouterHandler properties(Map<String, String> props) {
        beanContainer.addStaticProperties(props);
        return this;
    }

    /**
     * Register the converter for {@link ContentType#JSON}; enables automatic
     * {@code @RequestBody} deserialization and {@code @ResponseBody} serialization.
     * Use {@link #messageConverter(ContentType, HttpMessageConverter)} for other types.
     */
    public AnnotationRouterHandler messageConverter(HttpMessageConverter converter) {
        return messageConverter(ContentType.JSON, converter);
    }

    /** Register a converter for a specific {@link ContentType}. */
    public AnnotationRouterHandler messageConverter(ContentType type, HttpMessageConverter converter) {
        this.converters.put(type, converter);
        return this;
    }

    private HttpMessageConverter converterFor(ContentType type) {
        return type != null ? converters.get(type) : null;
    }

    /**
     * Register {@link ViewResolver}s for non-{@code @ResponseBody} return values. At scan time the
     * handler pre-selects the first {@code supports} hit; fall back to {@link #handleDefaultResult}.
     */
    public AnnotationRouterHandler addViewResolver(ViewResolver... resolvers) {
        Collections.addAll(viewResolvers, resolvers);
        return this;
    }

    /**
     * Pre-select the first {@link ViewResolver} whose {@code supports} accepts the endpoint's declared
     * return type; {@code null} means fall back to {@link #handleDefaultResult}.
     */
    private ViewResolver resolveViewResolver(Class<?> returnType) {
        for (ViewResolver vr : viewResolvers) {
            if (vr.supports(returnType)) return vr;
        }
        return null;
    }

    /**
     * Fallback for unclaimed non-{@code @ResponseBody} values: {@code File}/{@code InputStream}/{@code byte[]}
     * stream as octet-stream (chunked when length unknown); anything else via {@code String.valueOf}.
     */
    void handleDefaultResult(Object result, HttpResponse response) throws Exception {
        if (result instanceof File) {
            response.sendFile((File) result);
        } else if (result instanceof InputStream) {
            try (InputStream in = (InputStream) result) {
                response.contentType(HttpHeaderValues.APPLICATION_OCTET_STREAM).chunked();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    response.writeChunked(buf, 0, n);
                }
            }
        } else if (result instanceof byte[]) {
            response.contentType(HttpHeaderValues.APPLICATION_OCTET_STREAM).body((byte[]) result);
        } else {
            response.contentType(HttpHeaderValues.TEXT_PLAIN_UTF8).body(String.valueOf(result));
        }
    }

    /**
     * Apply the endpoint response strategy: {@code @ResponseBody} via the converter, else the
     * pre-selected {@link ViewResolver}, else {@link #handleDefaultResult}. Shared by both paths.
     */
    private void writeResult(Object result, boolean hasResponseBody, HttpMessageConverter responseConverter,
                             ConverterConfig converterConfig, ViewResolver viewResolver,
                             HttpRequest request, HttpResponse response) throws Exception {
        if (hasResponseBody) {
            responseConverter.write(result, converterConfig, response);
        } else if (result != null) {
            if (viewResolver != null) {
                viewResolver.render(result, request, response);
            } else {
                handleDefaultResult(result, response);
            }
        }
    }

    /**
     * Set a custom {@link AnnotationResolver} to bridge third-party annotation
     * systems (e.g. Spring Boot). Default is {@link DefaultAnnotationResolver}.
     */
    public AnnotationRouterHandler annotationResolver(AnnotationResolver resolver) {
        this.resolver = resolver;
        beanContainer.setResolver(resolver);
        return this;
    }

    /**
     * Set (replace) the packages to be scanned for {@code @Controller}, {@code @Component}
     * and {@code @WebSocket} classes. The actual scanning is deferred until {@link #prepare()}
     * (invoked once as part of {@code server.start()}), so the scan cost is included in the
     * server's startup timing. Call with no arguments to clear the list.
     * <p>
     * Once {@link #prepare()} has run, this method throws {@link IllegalStateException}
     * because the scan list is frozen for the server's lifetime.
     */
    public AnnotationRouterHandler scanPackages(String... packageNames) {
        if (prepared) {
            throw new IllegalStateException(
                    "scanPackages() must be called before the server starts (prepare())");
        }
        scanPackages = packageNames;
        return this;
    }

    /**
     * Enable one or more {@code @EnableXxx} switches. Registrar classes
     * (see {@link BeanRegistrationHandler}) bound to these annotations will be
     * discovered and activated during the package scan.
     * <p>
     * Each call replaces the previously enabled set (no accumulation).
     */
    public AnnotationRouterHandler enables(Class<? extends Annotation>... annotations) {
        enabledAnnotations.clear();
        if (annotations != null) {
            Collections.addAll(enabledAnnotations, annotations);
        }
        return this;
    }

    /**
     * Set the component proxy hook: requiresProxy gates wrapping and enhance
     * builds the proxy (returning the original instance means no enhancement).
     * Applies to constructed @Component and @Controller instances; @Bean results excluded.
     * Pass {@code null} to disable wrapping.
     *
     * @param enhancer the enhancer, or {@code null} to disable wrapping
     * @return this for chaining
     */
    public AnnotationRouterHandler componentEnhancer(ComponentEnhancer enhancer) {
        this.componentEnhancer = enhancer;
        return this;
    }

    /**
     * Set the classpath config file(s) auto-loaded at the start of each scan (and on every hot reload).
     * Defaults to {@code application.properties}; call with no argument to disable.
     */
    public AnnotationRouterHandler configFiles(String... classpathResources) {
        if (prepared) {
            throw new IllegalStateException(
                    "configFiles() must be called before the server starts (prepare())");
        }
        configFiles = classpathResources;
        applyReloadTrigger();
        return this;
    }

    /**
     * Skip classpath (jar-internal) config resources when loading scan configuration.
     * When enabled, only external files — JAR directory, JAR /config, parent /config — are read,
     * so nothing bundled inside the jar is ever loaded. Default false.
     */
    public AnnotationRouterHandler ignoreInternalConfig(boolean ignore) {
        if (prepared) {
            throw new IllegalStateException(
                    "ignoreInternalConfig() must be called before the server starts (prepare())");
        }
        this.ignoreInternalConfig = ignore;
        return this;
    }

    /**
     * Packages whose compiled output directories should never be watched for changes. Delegates to
     * {@link DevHotReloader}; must be called before the server starts.
     */
    public AnnotationRouterHandler hotReloadWatchExclude(String... packageNames) {
        if (prepared) {
            throw new IllegalStateException(
                    "hotReloadWatchExclude() must be called before the server starts (prepare())");
        }
        hotReloader.setWatchExcludes(packageNames);
        return this;
    }

    /**
     * One-time startup preparation: run the deferred package scan (if any) and then let the
     * parent sort the registered routes. Called once by the server before it starts serving,
     * so the scan cost is counted in the startup timing.
     */
    @Override
    public void prepare() {
        if (!prepared) {
            scan();
            prepared = true;
        }
        super.prepare();
        hotReloader.startWatcher();
    }

    /**
     * Re-run the package scan and re-sort routes on top of the current (reload) class loader. Used by
     * {@link DevHotReloader#reload(Path)} so the dev re-scan path mirrors startup without re-triggering
     * {@link #prepare()}'s own scan/watcher bootstrap.
     */
    void doScanAndReprepare() {
        doScan();
        super.prepare();
    }

    /**
     * Perform the classpath scan and bean/route registration. Clears previous scan results first.
     * <p>
     * Only safe to call once, during startup (via {@link #prepare()}) before the handler is
     * prepared. Calling it at runtime would {@link #clear()} the live route table and
     * re-instantiate every bean mid-traffic, which is unsafe — in-flight requests would hit
     * vanished routes, and the route tables are not safe to mutate concurrently with request
     * dispatch. Hence the guard below rejects any call made after {@link #prepare()}.
     */
    public void scan() {
        if (prepared) {
            throw new IllegalStateException(
                    "scan() may only run once during startup (before prepare()); it must not be called at runtime");
        }
        doScan();
    }

    // Effective scan packages: those set via scan(...), or the main class's package by default.
    String[] effectiveScanPackages() {
        if (scanPackages != null) return scanPackages;
        String defaultPkg = resolveDefaultScanPackage();
        return defaultPkg == null ? null : new String[]{defaultPkg};
    }

    // Lazily resolve the main class's package; caches "" when no package can be determined.
    String resolveDefaultScanPackage() {
        String pkg = defaultScanPackage;
        if (pkg == null) {
            pkg = "";
            String mainName = DevHotReloader.getMainClassName();
            if (mainName != null) {
                int idx = mainName.lastIndexOf('.');
                if (idx > -1) pkg = mainName.substring(0, idx);
            }
            defaultScanPackage = pkg;
        }
        return pkg.isEmpty() ? null : pkg;
    }

    /**
     * Enable or disable dev hot reload. Delegates to {@link DevHotReloader}; call before
     * {@code server.start()}. Default is enabled (auto-started in a dev environment).
     */
    public AnnotationRouterHandler hotReload(boolean enabled) {
        hotReloader.setEnabled(enabled);
        return this;
    }

    /**
     * Enable or disable dev hot reload and its console feedback in one call.
     *
     * @param enabled whether the dev watcher is active
     * @param log     whether each reload prints a line to the console
     */
    public AnnotationRouterHandler hotReload(boolean enabled, boolean log) {
        hotReloader.setEnabled(enabled);
        hotReloader.setConsoleOnReload(log);
        return this;
    }

    /**
     * Build the hot-reload trigger from {@link #configFiles}: a change to any config file's real
     * on-disk path re-triggers the scan. Each entry is resolved to its file path, so jar / non-watched
     * resources are safely ignored. Called on construction and whenever {@code configFiles(...)} changes.
     */
    private void applyReloadTrigger() {
        Set<String> paths = null;
        if (configFiles.length != 0) {
            ClassLoader cl = scanClassLoader();
            Set<String> set = new HashSet<>();
            for (String name : configFiles) {
                if (name == null || name.isEmpty()) continue;
                URL url = cl.getResource(name);
                if (url == null || !"file".equals(url.getProtocol())) continue;
                try {
                    set.add(Paths.get(url.toURI()).toAbsolutePath().normalize().toString());
                } catch (URISyntaxException ignored) {}
            }
            if (!set.isEmpty()) paths = set;
        }
        hotReloader.setReloadTriggerPaths(paths);
    }

    /**
     * Resolve the class loader for scanning: the active reload loader, else the thread context
     * loader, else this class's own loader.
     */
    private ClassLoader scanClassLoader() {
        ClassLoader cl = hotReloader.scanClassLoader;
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = AnnotationRouterHandler.class.getClassLoader();
        }
        return cl;
    }

    // Core scan routine: classify classes, register beans/controllers/websocket endpoints, inject fields,
    // run @PostConstruct, then register interceptors. Called at startup and on every hot reload.
    final void doScan() {
        clearScanResources();
        // Reload scan config first; resolve it with the same class loader used for class scanning.
        beanContainer.loadScanProperties(scanClassLoader(), ignoreInternalConfig, configFiles);
        String[] pkgs = effectiveScanPackages();
        Set<Class<?>> allControllers = new HashSet<>();
        Set<Class<?>> allComponents = new HashSet<>();
        Set<Class<?>> allConfigurations = new HashSet<>();
        Set<Class<?>> allWebSocketClasses = new HashSet<>();
        // Business scan is gated by configured packages; registrar activation below is independent of it.
        if (pkgs != null) {
            for (Class<?> clazz : classifyClasses(pkgs)) {
                if (resolver.isController(clazz)) {
                    allControllers.add(clazz);
                } else if (resolver.isConfiguration(clazz)) {
                    allConfigurations.add(clazz);
                } else if (resolver.isComponent(clazz)) {
                    // @Interceptor classes are recognized as components and registered as beans here
                    allComponents.add(clazz);
                } else if (resolver.isWebSocketEndpoint(clazz)) {
                    allWebSocketClasses.add(clazz);
                }
            }
        }
        // Phase 0: register all registrars (read config + register beans) before components exist
        scanAndRegisterRegistrars();
        // Phase 1: register @Configuration/@Bean/@Component (may @Inject registrar beans)
        processBeanMethods(allConfigurations, allComponents);
        // Phase 2: register controllers and websocket endpoints
        for (Class<?> c : allControllers) registerController(c);
        for (Class<?> c : allWebSocketClasses) registerWebSocketEndpoint(c);
        // Phase 3: inject all fields (@Value / @Inject)
        beanContainer.injectAllFields();
        // Phase 4: @PostConstruct
        beanContainer.invokeAllPostConstruct();
        // Phase 5: notify registrars (onAllReady)
        invokeRegistrarsAllReady();
        // Phase 6: register @Interceptor chain
        registerInterceptors();
    }

    void clearScanResources() {
        // Drop only scanned HTTP / SSE routes, preserving manual ones.
        clearRoutesIf(r -> r.target() instanceof AnnotationRoute);
        // WebSocket endpoints: drop only the scanned ones.
        removeResources(scannedUpgradePaths);
        scannedUpgradePaths.clear();
        // Drop only scanned interceptors, preserving manual ones.
        clearInterceptorsIf(scannedInterceptors::contains);
        scannedInterceptors.clear();
        // Controllers / components are always scan products.
        clearRegistrars();
        beanContainer.clearScan();
    }

    /** Destroy and drop previously activated registrars (called before each re-scan / hot reload). */
    private void clearRegistrars() {
        for (BeanRegistrationHandler handler : registrationHandlers) {
            try {
                handler.onDestroy();
            } catch (Exception ignored) {
            }
        }
        registrationHandlers.clear();
    }

    /**
     * Phase 0 registrar discovery &amp; registration — independent of the business-bean scan in
     * {@link #doScan()}.
     * <p>
     * Derives one scan root per enabled {@code @EnableXxx} annotation (its first package segment),
     * scans those roots once, instantiates every candidate whose bound annotation is enabled, sorts
     * them by {@link Registration#order()}, then invokes {@link BeanRegistrationHandler#onRegister}
     * once each — BEFORE component instantiation.
     */
    private void scanAndRegisterRegistrars() {
        if (enabledAnnotations.isEmpty()) return;
        // one root per enabled annotation: its first package segment, deduped
        String[] roots = new String[enabledAnnotations.size()];
        int i = 0;
        for (Class<? extends Annotation> enableAnno : enabledAnnotations) {
            String pkg = enableAnno.getPackage().getName();
            int idx = pkg.indexOf('.');
            roots[i++] = idx > -1 ? pkg.substring(0, idx) : pkg;
        }
        // single scan across all roots; keep only enabled candidates
        for (Class<?> clazz : PackageScanner.scan(
                c -> isEligibleClass(c) && isRegistrar(c), scanClassLoader(),
                roots)) {
            Registration registration = clazz.getAnnotation(Registration.class);
            if (!enabledAnnotations.contains(registration.value())) continue;
            // Prefer a single RegistrarContext constructor when present; otherwise a public no-arg one.
            Constructor<?> ctor = findRegistrarConstructor(clazz);
            if (ctor != null) {
                registrationHandlers.add((BeanRegistrationHandler) instantiateAndInject(clazz, ctor, beanContainer, beanContainer));
            } else {
                ctor = findPublicNoArgConstructor(clazz);
                if (ctor == null) {
                    throw new RuntimeException("Registrar " + clazz.getName()
                            + " must declare a public no-arg constructor or one accepting RegistrarContext");
                }
                registrationHandlers.add((BeanRegistrationHandler) instantiateAndInject(clazz, ctor, beanContainer));
            }
        }
        // sort by @Registration.order() ascending, tie-break by class name for determinism
        registrationHandlers.sort((a, b) -> {
            int oa = registrarOrder(a), ob = registrarOrder(b);
            if (oa != ob) return Integer.compare(oa, ob);
            return a.getClass().getName().compareTo(b.getClass().getName());
        });
        // single sorted pass: each registrar reads config and registers its beans
        for (BeanRegistrationHandler handler : registrationHandlers) {
            try {
                handler.onRegister(beanContainer);
            } catch (Exception e) {
                throw new RuntimeException("Failed to register beans via BeanRegistrationHandler: "
                        + handler.getClass().getName(), e);
            }
        }
    }

    /**
     * Phase 5 — notify every activated registrar that the whole container is ready.
     * Runs after all beans are field-injected and their {@code @PostConstruct} executed, so a
     * registrar can safely rely on other beans being fully initialized.
     */
    private void invokeRegistrarsAllReady() {
        if (registrationHandlers.isEmpty()) return;
        for (BeanRegistrationHandler handler : registrationHandlers) {
            try {
                handler.onAllReady(beanContainer);
            } catch (Exception e) {
                throw new RuntimeException("Failed to notify BeanRegistrationHandler onAllReady: "
                        + handler.getClass().getName(), e);
            }
        }
    }

    /** Register PRE_ROUTE @Interceptor beans into the parent chain, ordered by @Interceptor.order(). */
    void registerInterceptors() {
        if (isInterceptorsDisabled()) return;
        Collection<BeanDefinition> beans = beanContainer.getBeans();
        if (beans.isEmpty()) return;
        List<BeanDefinition> chain = null;
        for (BeanDefinition bd : beans) {
            Object bean = bd.getInstance();
            // keep PRE_ROUTE interceptors only: skip non-interceptor, unannotated, ENDPOINT and disabled beans
            Interceptor ann = bd.getSourceClass().getAnnotation(Interceptor.class);
            if (!(bean instanceof RouterInterceptor) || ann == null || ann.disabled() || ann.type() == InterceptorType.ENDPOINT) continue;
            if (chain == null) chain = new ArrayList<>();
            chain.add(bd);
        }
        if (chain == null) return;
        sortByOrder(chain);
        for (BeanDefinition bd : chain) {
            RouterInterceptor interceptor = (RouterInterceptor) bd.getInstance();
            scannedInterceptors.add(interceptor);
            interceptor(interceptor);
        }
    }

    /**
     * Resolve the ENDPOINT interceptors bound to a route via {@code @WithInterceptor}.
     *
     * @return the ordered interceptor list, or an empty list if the route declares none
     */
    List<RouterInterceptor> resolveEndpointInterceptors(MethodRouteInfo routeInfo) {
        String[] names = routeInfo.getInterceptorNames();
        if (isInterceptorsDisabled() || names == null) return Collections.emptyList();
        List<BeanDefinition> chain = new ArrayList<>(names.length);
        for (String name : names) {
            BeanDefinition bd = beanContainer.getBeanDefinition(name);
            if (bd == null) {
                throw new RuntimeException("@WithInterceptor(\"" + name + "\") on "
                        + routeInfo.getMethod() + " refers to an unknown interceptor bean");
            }
            Object bean = bd.getInstance();
            if (!(bean instanceof RouterInterceptor)) {
                throw new RuntimeException("@WithInterceptor(\"" + name + "\") on "
                        + routeInfo.getMethod() + " refers to " + bean.getClass().getName()
                        + " which does not implement RouterInterceptor");
            }
            // bind ENDPOINT interceptors only: skip unannotated, PRE_ROUTE and disabled ones
            Interceptor ann = bd.getSourceClass().getAnnotation(Interceptor.class);
            if (ann == null || ann.type() != InterceptorType.ENDPOINT || ann.disabled()) continue;
            // de-duplicate in case the same interceptor is referenced by both class and method level
            if (!chain.contains(bd)) chain.add(bd);
        }
        if (chain.isEmpty()) return Collections.emptyList();
        sortByOrder(chain);
        List<RouterInterceptor> result = new ArrayList<>(chain.size());
        for (BeanDefinition bd : chain) result.add((RouterInterceptor) bd.getInstance());
        return result;
    }

    /** Scan the given packages in one pass and return eligible classes, using the active (reload) class loader if set. */
    private Set<Class<?>> classifyClasses(String... packageNames) {
        return PackageScanner.scan(c -> resolver.accept(c) && isEligibleClass(c), scanClassLoader(), packageNames);
    }

    /**
     * Register {@code @Configuration} classes and process their {@code @Bean} methods, then register
     * {@code @Component} classes, retrying deferred dependencies up to 5 times.
     * <p>
     * See {@link Configuration} for the no-arg constructor requirement.
     */
    void processBeanMethods(Set<Class<?>> configurations, Set<Class<?>> components) {
        List<Executable> deferred = new ArrayList<>();
        // @Configuration requires a no-arg constructor; otherwise it's skipped (with a warning) and its @Bean methods are ignored.
        for (Class<?> configClass : configurations) {
            Constructor<?> noArgCtor = findPublicNoArgConstructor(configClass);
            if (noArgCtor == null) {
                log.warn("@Configuration {} has no no-arg constructor; its @Bean methods will not be registered", configClass.getName());
                continue;
            }
            Object configInstance = instantiateAndInject(configClass, noArgCtor, beanContainer);
            // Register by FQN to avoid simple-name collisions across packages.
            beanContainer.register(configClass.getName(), configInstance, false);
            for (Method method : configClass.getDeclaredMethods()) {
                int mod = method.getModifiers();
                // void and primitive return types yield no usable bean type (a primitive resolves via
                // Class.cast → ClassCastException); skip so they're silently ignored like non-@Bean methods.
                if (!Modifier.isPublic(mod) || Modifier.isStatic(mod)
                        || method.getReturnType() == void.class || method.getReturnType().isPrimitive()) continue;
                Annotation beanAnn = beanContainer.findBeanAnnotation(method);
                if (beanAnn == null) continue;
                if (!tryProcessBeanMethod(configInstance, method, resolver.resolveBeanName(beanAnn), beanContainer, resolver)) {
                    deferred.add(method);
                }
            }
        }
        // Register @Component classes
        for (Class<?> c : components) {
            if (!registerComponent(c)) {
                Constructor<?>[] ctors = c.getConstructors();
                // marker only; retry consumes declaringClass and re-selects the ctor via findConstructor
                Constructor<?> ctor = ctors.length > 0 ? ctors[0] : null;
                if (ctor != null) deferred.add(ctor);
            }
        }
        // Unified retry (up to 5 retries)
        if (!deferred.isEmpty()) {
            List<Executable> pending = deferred;
            int retry = 5;
            while (--retry > -1 && !pending.isEmpty()) {
                List<Executable> next = new ArrayList<>();
                for (Executable exec : pending) {
                    if (exec instanceof Method) {
                        Method method = (Method) exec;
                        Object configInstance = beanContainer.getBean(method.getDeclaringClass());
                        if (configInstance != null) {
                            Annotation beanAnn = beanContainer.findBeanAnnotation(method);
                            if (beanAnn != null && tryProcessBeanMethod(configInstance, method, resolver.resolveBeanName(beanAnn), beanContainer, resolver)) {
                                continue;
                            }
                        }
                    } else if (registerComponent(exec.getDeclaringClass())) continue;
                    next.add(exec);
                }
                pending = next;
            }
            if (!pending.isEmpty()) {
                throw new RuntimeException("Cannot resolve dependencies after "
                        + "5 retries: " + pending);
            }
        }
    }

    void registerWebSocketEndpoint(Class<?> clazz) {
        try {
            String path = resolver.resolveWebSocketPath(clazz);
            if (path.isEmpty()) return;
            WebSocketResource resource = (WebSocketResource) clazz.getDeclaredConstructor().newInstance();
            ws(path, resource);
            scannedUpgradePaths.add(buildFullPath(path));
        } catch (Exception e) {
            throw new RuntimeException("Failed to register WebSocket endpoint: " + clazz.getName(), e);
        }
    }

    boolean registerComponent(Class<?> clazz) {
        try {
            BeanDefinition bd = resolveInstance(clazz);
            if (bd == null) return false;
            if (!bd.getSourceClass().isInstance(bd.getInstance())) {
                throw new IllegalArgumentException(
                    "ComponentEnhancer returned an object not assignable to " + clazz.getName());
            }
            String name = resolver.resolveComponentName(clazz);
            beanContainer.register(name, bd, false);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register component: " + clazz.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    void registerController(Class<?> clazz) {
        String basePath = normalizeBasePath(resolver.resolveControllerPath(clazz));
        List<MethodRouteInfo> routes = resolver.resolveEndpointRoutes(clazz);
        // Merge SSE endpoints into the same list: @Sse reuses the @Endpoint parameter-binding
        // pipeline and only differs in how the response is produced (SseEmitter stream vs body).
        routes.addAll(resolver.resolveSseEndpoints(clazz));
        if (routes.isEmpty()) return;
        Set<Method> seenMethods = new HashSet<>();

        try {
            final BeanDefinition def = Objects.requireNonNull(resolveInstance(clazz),
                    "Cannot instantiate controller " + clazz.getName()
                            + ": a required @Inject dependency was not resolvable (missing or not yet registered)") ;
            if (!def.getSourceClass().isInstance(def.getInstance())) {
                throw new IllegalArgumentException(
                    "ComponentEnhancer returned an object not assignable to " + clazz.getName());
            }
            // Register by FQN to avoid simple-name collisions across packages.
            final Object controller = def.getInstance();
            beanContainer.register(clazz.getName(), def, false);

            // Controller-class-level response-body annotations (e.g. @RestController)
            // apply to every @Endpoint method in the class.
            final boolean classHasResponseBody = hasAnyAnnotation(clazz.getAnnotations(), responseBodyAnnotations);

            // HTTP + SSE endpoints (merged)
            for (final MethodRouteInfo routeInfo : routes) {
                if (!seenMethods.add(routeInfo.getMethod())) {
                    throw new IllegalArgumentException("Method " + routeInfo.getMethod()
                            + " is annotated with both @Endpoint and @Sse; choose one");
                }
                final boolean isSse = routeInfo.getAnnotationType() == Sse.class;
                // Per-endpoint SSE timeout; < 0 falls back to the global SSE_TIMEOUT_MS option in startSse.
                final long sseTimeoutMs = isSse ? routeInfo.getMethod().getAnnotation(Sse.class).timeout() : -1;
                final String fullPath = buildFullPath(basePath, routeInfo.getPath());

                // Pre-compute parameter kinds at scan time
                Method endpointMethod = routeInfo.getMethod();
                Parameter[] params = endpointMethod.getParameters();
                final int[] argKinds = new int[params.length];
                final Type[] argBodyTypes = new Type[params.length];
                final int[] argPathSegIndex = new int[params.length];
                final String[] argParamNames = new String[params.length];
                final Class<?>[] argParamTypes = new Class<?>[params.length];
                final boolean[] argParamRequired = new boolean[params.length];
                final String[] argParamDefaults = new String[params.length];
                final boolean[] argParamIsFile = new boolean[params.length];
                final boolean[] argParamIsMulti = new boolean[params.length];
                final Class<?>[] argParamComponentTypes = new Class<?>[params.length];
                // Pre-resolved type converters (scan time) so request time does no type lookup
                final Function<String, Object>[] argConverters = new Function[params.length];
                final Function<String, Object>[] argParamElemConverters = new Function[params.length];
                // Index of the SseEmitter parameter when this endpoint is an @Sse endpoint; -1 otherwise.
                int argSseIndex = -1;

                // Parse path template variables: ${name} (wastnet) or Spring's {name}, with optional inline regex {name:pattern}
                final Map<String, Integer> pathVarSegIndex = fullPath.indexOf('{') > -1 ? new HashMap<>() : null;
                final String routePattern = pathVarSegIndex != null ? buildRoutePattern(fullPath, pathVarSegIndex) : null;

                for (int i = 0; i < params.length; ++i) {
                    Class<?> pType = params[i].getType();
                    argParamTypes[i] = pType;
                    if (pType == HttpRequest.class) {
                        argKinds[i] = KIND_REQUEST;
                    } else if (pType == HttpResponse.class) {
                        argKinds[i] = KIND_RESPONSE;
                    } else if (pType == SseEmitter.class) {
                        if (!isSse) {
                            throw new IllegalArgumentException(
                                    "SseEmitter can only be declared on @Sse endpoints, not on @Endpoint "
                                            + endpointMethod.getName() + " in " + clazz.getName());
                        }
                        argKinds[i] = KIND_SSE;
                        argSseIndex = i;
                    } else if (hasAnyAnnotation(params[i].getAnnotations(), requestBodyAnnotations)) {
                        if (isSse) {
                            throw new IllegalArgumentException("@RequestBody is not allowed on @Sse endpoint "
                                    + endpointMethod.getName() + "; SSE produces a stream, it does not consume a body");
                        }
                        argKinds[i] = KIND_BODY;
                        argBodyTypes[i] = params[i].getParameterizedType();
                    } else {
                        for (Annotation ann : params[i].getAnnotations()) {
                            if (isInList(ann, pathParamAnnotations)) {
                                String name = readStringAttr(ann, "value", "");
                                if (pathVarSegIndex == null || !pathVarSegIndex.containsKey(name)) {
                                    throw new IllegalArgumentException("Path variable \""
                                            + name + "\" has no matching ${" + name + "} or {" + name + "} in path " + fullPath);
                                }
                                argKinds[i] = KIND_PATH;
                                argPathSegIndex[i] = pathVarSegIndex.get(name);
                                ParamValueConverters.ensureConvertible(pType);
                                argConverters[i] = resolveConverter(pType);
                                break;
                            }
                            boolean isRequestParam = isInList(ann, requestParamAnnotations);
                            if (isRequestParam || isInList(ann, headerParamAnnotations)) {
                                argKinds[i] = isRequestParam ? KIND_PARAM : KIND_HEADER;
                                argParamNames[i] = readStringAttr(ann, "value", "");
                                argParamRequired[i] = readBooleanAttr(ann, "required", true);
                                argParamDefaults[i] = readStringAttr(ann, "defaultValue", "");
                                Class<?> elemType = resolveParamElementType(params[i], pType);
                                argParamIsFile[i] = isRequestParam && (pType == MultipartField.class || pType == MultipartField[].class);
                                argParamIsMulti[i] = pType.isArray() || Collection.class.isAssignableFrom(pType);
                                argParamComponentTypes[i] = elemType;
                                if (!argParamIsFile[i]) { // file params (MultipartField) are not resolved via the converter registry, so skip the check
                                    ParamValueConverters.ensureConvertible(argParamIsMulti[i] ? elemType : pType); // multi-value (array/collection) -> validate element type; scalar -> validate param type
                                }
                                argConverters[i] = resolveConverter(pType);
                                argParamElemConverters[i] = argParamIsMulti[i] ? resolveConverter(elemType) : null;
                                break;
                            }
                        }
                    }
                }
                // an unmatched parameter keeps 0, so it can never be mistaken for KIND_REQUEST
                final boolean isFastPath = argKinds.length == 2 && argKinds[0] == KIND_REQUEST && argKinds[1] == KIND_RESPONSE;
                final boolean hasResponseBody = !isSse && endpointMethod.getReturnType() != void.class
                        && (classHasResponseBody
                        || hasAnyAnnotation(endpointMethod.getAnnotations(), responseBodyAnnotations));
                final ConverterConfig converterConfig = new ConverterConfig(routeInfo);
                // Resolve the response converter once at scan time (the response type is fixed per endpoint),
                // so the per-request lambda skips the EnumMap lookup on every call.
                final HttpMessageConverter responseConverter = hasResponseBody ? converterFor(converterConfig.getResponseType()) : null;
                if (hasResponseBody && responseConverter == null) {
                    ContentType rt = converterConfig.getResponseType();
                    throw new RuntimeException("@ResponseBody endpoint " + clazz.getSimpleName() + "#" + endpointMethod.getName()
                            + " must serialize its return value as " + rt
                            + ", but no HttpMessageConverter is registered for that ContentType."
                            + " Configure one via .messageConverter(" + rt + ", converter).");
                }
                // Pre-select the view resolver at scan time from the endpoint's declared return type,
                // so the per-request lambda skips the resolver list lookup on every call.
                final ViewResolver routeViewResolver = hasResponseBody ? null : resolveViewResolver(endpointMethod.getReturnType());

                final List<RouterInterceptor> endpointInterceptors = resolveEndpointInterceptors(routeInfo);

                // Build MethodHandle bound to the controller instance
                MethodHandle mh = MethodHandles.lookup().unreflect(endpointMethod);
                final MethodHandle bound = MethodHandles.insertArguments(mh, 0, controller);

                AnnotationRoute handler;
                if (isFastPath) {
                    // void / non-void is a construction-time constant, so pick a specialised
                    // lambda once instead of re-checking getReturnType() on every request.
                    if (endpointMethod.getReturnType() == void.class) {
                        handler = (p, request, response) -> {
                            if (!applyInterceptors(endpointInterceptors, p, request, response)) {
                                return;
                            }
                            bound.invokeExact((HttpRequest) request, (HttpResponse) response);
                        };
                    } else {
                        handler = (p, request, response) -> {
                            if (!applyInterceptors(endpointInterceptors, p, request, response)) {
                                return;
                            }
                            writeResult(bound.invoke((HttpRequest) request, (HttpResponse) response), hasResponseBody, responseConverter, converterConfig, routeViewResolver, request, response);
                        };
                    }
                } else {
                    // General path: any signature other than (HttpRequest, HttpResponse).
                    // Also serves @Sse endpoints: the SseEmitter slot (KIND_SSE) is left empty
                    // during binding and filled after the stream opens via startSse().
                    final int sseIndex = argSseIndex;
                    handler = (p, request, response) -> {
                        if (!applyInterceptors(endpointInterceptors, p, request, response)) {
                            return;
                        }
                        Object[] args = new Object[argKinds.length];
                        try {
                            for (int i = 0; i < argKinds.length; ++i) {
                                switch (argKinds[i]) {
                                    case KIND_REQUEST:  args[i] = request; break;
                                    case KIND_RESPONSE: args[i] = response; break;
                                    case KIND_BODY: {
                                        HttpMessageConverter bc = converterFor(ContentType.fromRequest(request.getContentType()));
                                        args[i] = bc != null
                                                ? assertBodyAssignable(bc.read(request, converterConfig, argBodyTypes[i]), argParamTypes[i])
                                                : null;
                                        break;
                                    }
                                    case KIND_PATH:     args[i] = argConverters[i].apply(segmentAt(p, argPathSegIndex[i])); break;
                                    case KIND_PARAM:
                                        args[i] = resolveRequestParam(request, argParamNames[i], argParamTypes[i],
                                                argParamComponentTypes[i], argParamRequired[i], argParamDefaults[i],
                                                argParamIsFile[i], argParamIsMulti[i], argConverters[i], argParamElemConverters[i]);
                                        break;
                                    case KIND_SSE:      break; // filled after the SSE stream opens
                                    case KIND_HEADER:
                                        args[i] = resolveRequestHeader(request, argParamNames[i], argParamTypes[i],
                                                argParamComponentTypes[i], argParamRequired[i], argParamDefaults[i],
                                                argParamIsMulti[i], argConverters[i], argParamElemConverters[i]);
                                        break;
                                    default: {
                                        if(argParamTypes[i].isPrimitive()) {
                                            args[i] = Array.get(Array.newInstance(argParamTypes[i], 1), 0);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // param/path binding or body parse failed -> client error 400
                            log.warn("Bad request param binding failed: {}", e.getMessage());
                            response.status(HttpStatus.BAD_REQUEST)
                                    .contentType("text/plain;charset=utf-8")
                                    .body("400 Bad Request".getBytes());
                            return;
                        }
                        if (isSse) {
                            // Open the SSE stream (headers flushed) only after binding succeeds,
                            // then inject the emitter and run the endpoint on the worker thread.
                            startSse(request, response, sseTimeoutMs, emitter -> {
                                args[sseIndex] = emitter;
                                bound.invokeWithArguments(args);
                            });
                        } else {
                            writeResult(bound.invokeWithArguments(args), hasResponseBody, responseConverter, converterConfig, routeViewResolver, request, response);
                        }
                    };
                }

                HttpMethod[] httpMethods = routeInfo.getHttpMethods();
                if (routePattern != null) {
                    route(routePattern, handler, httpMethods);
                } else {
                    exactRoute(fullPath, handler, httpMethods);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to register controller: " + clazz.getName(), e);
        }
    }

    private BeanDefinition resolveInstance(Class<?> clazz) throws Exception {
        Constructor<?> ctor = findConstructor(clazz.getConstructors(), beanContainer);
        if (ctor == null) return null; // no public constructor -> not instantiable via public API
        Parameter[] params = ctor.getParameters();
        Object[] args = params.length == 0 ? new Object[0] : resolveParameters(params, clazz.getName(), beanContainer, resolver);
        if (args == null) return null;
        boolean proxy = requiresProxy(clazz);
        Object instance = proxy ? componentEnhancer.enhance(clazz, ctor, args) : ctor.newInstance(args);
        if (instance == null) return null;
        return new BeanDefinition(clazz, instance, true, proxy);
    }

    private boolean requiresProxy(Class<?> clazz) {
        if (componentEnhancer == null) return false;
        if (componentEnhancer.requiresProxy(clazz)) return true;
        Class<? extends Annotation>[] triggers = componentEnhancer.proxyAnnotations();
        if (triggers != null) {
            for (Class<? extends Annotation> trigger : triggers) {
                if (clazz.isAnnotationPresent(trigger)) return true;
                for (Method method : clazz.getMethods()) {
                    if (method.isAnnotationPresent(trigger)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Register (or override) a custom converter for a parameter value type.
     * Converters are stored in {@link ParamValueConverters}, shared by {@code @RequestParam},
     * {@code @PathParam} and {@code @Value}. Built-in primitive / String converters are locked
     * and cannot be overridden.
     *
     * @throws IllegalArgumentException if {@code type} is a locked built-in type
     */
    @SuppressWarnings("unchecked")
    public <T> AnnotationRouterHandler registerParamConverter(Class<T> type, Function<String, T> converter) {
        ParamValueConverters.register(type, (Function<String, Object>) converter);
        return this;
    }

    /**
     * Clear all registered routes and release component instances. Also stops the dev watcher and
     * resets the prepared flag so the handler can be prepared again.
     */
    @Override
    public void clear() {
        super.clear();
        beanContainer.clearAll();
        clearRegistrars();
        hotReloader.destroyWatcher();
        prepared = false;
    }
}
