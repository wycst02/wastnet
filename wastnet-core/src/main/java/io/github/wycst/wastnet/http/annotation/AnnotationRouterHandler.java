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
import java.util.*;
import java.util.regex.Pattern;

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

    private final BeanContainer beanContainer;

    private AnnotationResolver resolver;
    private HttpMessageConverter messageConverter;
    private Class<?>[] requestBodyAnnotations = new Class<?>[]{RequestBody.class};
    private Class<?>[] responseBodyAnnotations = new Class<?>[]{ResponseBody.class, RestController.class};
    private Class<?>[] pathParamAnnotations = new Class<?>[]{PathParam.class};
    private Class<?>[] requestParamAnnotations = new Class<?>[]{RequestParam.class};

    public AnnotationRouterHandler() {
        resolver = new DefaultAnnotationResolver();
        beanContainer = new BeanContainer();
        beanContainer.setResolver(resolver);
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
     * Set the message converter for processing {@code @RequestBody} and
     * {@code @ResponseBody} annotations.
     * <p>
     * When configured, {@code @RequestBody} parameters are automatically deserialized
     * from the HTTP request body, and controller methods annotated with
     * {@code @ResponseBody} are wired so their return values are automatically
     * serialized to the HTTP response body.
     * <p>
     * Default is {@code null} (annotations are ignored). Configure this to enable
     * automatic serialization/deserialization via e.g. JSON.
     */
    public AnnotationRouterHandler messageConverter(HttpMessageConverter converter) {
        this.messageConverter = converter;
        return this;
    }

    /**
     * Set a custom {@link AnnotationResolver} to bridge third-party annotation
     * systems (e.g. Spring Boot, Micronaut, etc.).
     * <p>
     * This is the central extension point of the framework. By implementing
     * {@link AnnotationResolver}, users can:
     * <ul>
     *   <li>Map third-party annotations (e.g. {@code @RestController},
     *       {@code @Service}, {@code @GetMapping}) to the framework's
     *       controller/component/endpoint model</li>
     *   <li>Provide a custom scanner filter via {@link AnnotationFilter#accept(Class)}
     *       to avoid loading irrelevant classes during package scanning</li>
     *   <li>Control how route paths, component names, and lifecycle annotations
     *       are resolved from external annotation libraries</li>
     * </ul>
     * <p>
     * Default is {@link DefaultAnnotationResolver}, which resolves the
     * framework's own annotations ({@code @Controller}, {@code @Component},
     * {@code @Endpoint}, {@code @Sse}, {@code @WebSocket}).
     * <p>
     * Example for Spring Boot bridging:
     * <pre>{@code
     * new AnnotationRouterHandler()
     *     .annotationResolver(new AnnotationResolver() {
     *         public boolean accept(Class<?> c) {
     *             return c.isAnnotationPresent(RestController.class)
     *                 || c.isAnnotationPresent(Component.class);
     *         }
     *         public boolean isController(Class<?> c) { ... }
     *         public boolean isComponent(Class<?> c)   { ... }
     *         // ... other methods
     *     })
     *     .scanPackages("com.example.controller");
     * }</pre>
     */
    public AnnotationRouterHandler annotationResolver(AnnotationResolver resolver) {
        this.resolver = resolver;
        beanContainer.setResolver(resolver);
        return this;
    }

    /**
     * Scan one or more packages for {@code @Controller}, {@code @Component}
     * and {@code @WebSocket} classes.
     * Components are registered first so they are available for constructor injection
     * into controllers.
     */
    public AnnotationRouterHandler scanPackages(String... packageNames) {
        Set<Class<?>> allControllers = new LinkedHashSet<>();
        Set<Class<?>> allComponents = new LinkedHashSet<>();
        Set<Class<?>> allConfigurations = new LinkedHashSet<>();
        Set<Class<?>> allWebSocketClasses = new LinkedHashSet<>();
        for (String pkg : packageNames) {
            for (Class<?> clazz : classifyClasses(pkg)) {
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
        // Phase 1: @Configuration, @Bean and @Component registration
        processBeanMethods(allConfigurations, allComponents);
        // Phase 2: register controllers and websocket endpoints
        for (Class<?> c : allControllers) registerController(c);
        for (Class<?> c : allWebSocketClasses) registerWebSocketEndpoint(c);
        // Phase 3: bulk field injection
        beanContainer.injectAllFields();
        // Phase 4: @PostConstruct on all beans
        beanContainer.invokeAllPostConstruct();
        // Phase 5: register route-level interceptors (@Interceptor) ordered by order()
        registerInterceptors();
        return this;
    }

    /** Register PRE_ROUTE @Interceptor beans into the parent chain, ordered by @Interceptor.order(). */
    private void registerInterceptors() {
        if (isInterceptorsDisabled()) return;
        Collection<Object> beans = beanContainer.getBeans();
        if (beans == null || beans.isEmpty()) return;
        List<RouterInterceptor> chain = null;
        for (Object bean : beans) {
            // read @Interceptor meta once; beans without it are skipped (not auto PRE_ROUTE)
            Interceptor ann = bean.getClass().getAnnotation(Interceptor.class);
            // only PRE_ROUTE run unconditionally; ENDPOINT ones are bound per endpoint;
            // skip @Interceptor(disabled = true)
            if (!(bean instanceof RouterInterceptor) || ann == null || ann.disabled() || ann.type() == InterceptorType.ENDPOINT) continue;
            if (chain == null) chain = new ArrayList<>();
            chain.add((RouterInterceptor) bean);
        }
        if (chain == null) return;
        sortByOrder(chain);
        for (RouterInterceptor interceptor : chain) {
            interceptor(interceptor);
        }
    }

    /** Stable sort by @Interceptor.order(). */
    private static void sortByOrder(List<RouterInterceptor> chain) {
        chain.sort((a, b) -> {
            Integer oa = a.getClass().getAnnotation(Interceptor.class).order();
            Integer ob = b.getClass().getAnnotation(Interceptor.class).order();
            return oa.compareTo(ob);
        });
    }

    /**
     * Resolve the ENDPOINT interceptors bound to a route via {@code @WithInterceptor}.
     *
     * @return the ordered interceptor list, or an empty list if the route declares none
     */
    private List<RouterInterceptor> resolveEndpointInterceptors(MethodRouteInfo routeInfo) {
        String[] names = routeInfo.getInterceptorNames();
        if (isInterceptorsDisabled() || names == null) return Collections.emptyList();
        List<RouterInterceptor> chain = new ArrayList<>(names.length);
        for (String name : names) {
            Object bean = beanContainer.getBean(name);
            if (bean == null) {
                throw new RuntimeException("@WithInterceptor(\"" + name + "\") on "
                        + routeInfo.getMethod() + " refers to an unknown interceptor bean");
            }
            if (!(bean instanceof RouterInterceptor)) {
                throw new RuntimeException("@WithInterceptor(\"" + name + "\") on "
                        + routeInfo.getMethod() + " refers to " + bean.getClass().getName()
                        + " which does not implement RouterInterceptor");
            }
            // no @Interceptor annotation (or PRE_ROUTE) is skipped here; only ENDPOINT ones are bound.
            Interceptor ann = bean.getClass().getAnnotation(Interceptor.class);
            // skip non-ENDPOINT (no @Interceptor, PRE_ROUTE) or @Interceptor(disabled = true)
            if (ann == null || ann.type() != InterceptorType.ENDPOINT || ann.disabled()) continue;
            // de-duplicate in case the same interceptor is referenced by both class and method level
            RouterInterceptor interceptor = (RouterInterceptor) bean;
            if (!chain.contains(interceptor)) chain.add(interceptor);
        }
        if (chain.isEmpty()) return Collections.emptyList();
        sortByOrder(chain);
        return chain;
    }

    /** Scan a package and return eligible classes. */
    private Set<Class<?>> classifyClasses(String packageName) {
        Set<Class<?>> classes = PackageScanner.scan(packageName, resolver);
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> clazz : classes) {
            if (isEligibleClass(clazz)) result.add(clazz);
        }
        return result;
    }

    /**
     * Scan {@code @Bean} methods on all registered component instances and
     * register their return values back into the container.
     */
    private void processBeanMethods(Set<Class<?>> configurations, Set<Class<?>> components) {
        List<Executable> deferred = new ArrayList<>();
        // Register @Configuration and process @Bean methods
        for (Class<?> configClass : configurations) {
            Constructor<?> ctor = configClass.getConstructors().length > 0 ? configClass.getConstructors()[0] : null;
            if (ctor != null && ctor.getParameterCount() > 0) continue; // only no-arg supported
            String name = Character.toLowerCase(configClass.getSimpleName().charAt(0)) + configClass.getSimpleName().substring(1);
            Object configInstance;
            try {
                configInstance = configClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate @Configuration: " + configClass.getName(), e);
            }
            beanContainer.register(name, configInstance);
            for (Method method : configClass.getDeclaredMethods()) {
                int mod = method.getModifiers();
                if (!Modifier.isPublic(mod) || Modifier.isStatic(mod)) continue;
                Annotation beanAnn = beanContainer.findBeanAnnotation(method);
                if (beanAnn == null) continue;
                if (!tryProcessBeanMethod(configInstance, method, resolver.resolveBeanName(beanAnn))) {
                    deferred.add(method);
                }
            }
        }
        // Register @Component classes
        for (Class<?> c : components) {
            if (!registerComponent(c)) {
                Constructor<?> ctor = c.getConstructors().length > 0 ? c.getConstructors()[0] : null;
                if (ctor != null) deferred.add(ctor);
            }
        }
        // Unified retry (up to 4 retries)
        if (!deferred.isEmpty()) {
            List<Executable> pending = deferred;
            int retry = 4;
            while (--retry > -1 && !pending.isEmpty()) {
                List<Executable> next = new ArrayList<>();
                for (Executable exec : pending) {
                    if (exec instanceof Method) {
                        Method method = (Method) exec;
                        Object configInstance = beanContainer.getBean(method.getDeclaringClass());
                        if (configInstance != null) {
                            Annotation beanAnn = beanContainer.findBeanAnnotation(method);
                            if (beanAnn != null && tryProcessBeanMethod(configInstance, method, resolver.resolveBeanName(beanAnn))) {
                                continue;
                            }
                        }
                    } else if (exec instanceof Constructor) {
                        if (registerComponent(exec.getDeclaringClass())) continue;
                    }
                    next.add(exec);
                }
                pending = next;
            }
            if (!pending.isEmpty()) {
                throw new RuntimeException("Cannot resolve dependencies after "
                        + "4 retries: " + pending);
            }
        }
    }

    /** @return true if resolved and invoked, false if deferred due to missing deps */
    private boolean tryProcessBeanMethod(Object bean, Method method, String beanName) {
        try {
            Object[] args = resolveParameters(method.getParameters(),
                    "@Bean method " + method.getName() + " on " + bean.getClass().getName());
            if (args == null) return false;
            invokeBeanMethod(bean, method, beanName, args);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process @Bean method " + method.getName()
                    + " on " + bean.getClass().getName(), e);
        }
    }

    private void invokeBeanMethod(Object bean, Method method, String beanName, Object[] args) throws Exception {
        Object result = method.invoke(bean, args);
        String name = beanName.isEmpty() ? method.getName() : beanName;
        beanContainer.register(name, result);
    }

    private void registerWebSocketEndpoint(Class<?> clazz) {
        try {
            String path = resolver.resolveWebSocketPath(clazz);
            if (path.isEmpty()) return;
            WebSocketResource resource = (WebSocketResource) clazz.getDeclaredConstructor().newInstance();
            ws(path, resource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register WebSocket endpoint: " + clazz.getName(), e);
        }
    }

    private boolean registerComponent(Class<?> clazz) {
        try {
            Object instance = resolveInstance(clazz);
            if (instance == null) return false;
            String name = resolver.resolveComponentName(clazz);
            beanContainer.register(name, instance);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register component: " + clazz.getName(), e);
        }
    }

    /**
     * Register a controller's bean in the container so it is also managed
     * and receives lifecycle callbacks.
     */
    private void registerControllerBean(String name, Object controller) {
        try {
            beanContainer.register(name, controller);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize controller: " + controller.getClass().getName(), e);
        }
    }

    private void registerController(Class<?> clazz) {
        String basePath = resolver.resolveControllerPath(clazz);
        List<MethodRouteInfo> routes = resolver.resolveEndpointRoutes(clazz);
        List<MethodRouteInfo> sseEndpoints = resolver.resolveSseEndpoints(clazz);
        if (routes.isEmpty() && sseEndpoints.isEmpty()) return;

        try {
            final Object controller = resolveInstance(clazz);
            registerControllerBean(clazz.getSimpleName(), controller);

            // Controller-class-level response-body annotations (e.g. @RestController)
            // apply to every @Endpoint method in the class.
            final boolean classHasResponseBody = hasAnyAnnotation(clazz.getAnnotations(), responseBodyAnnotations);

            // HTTP routes
            for (final MethodRouteInfo routeInfo : routes) {
                final String fullPath = combinePath(basePath, routeInfo.getPath());

                // Pre-compute parameter kinds at scan time
                Method endpointMethod = routeInfo.getMethod();
                Parameter[] params = endpointMethod.getParameters();
                final int[] argKinds = new int[params.length];
                final Class<?>[] argBodyTypes = new Class<?>[params.length];
                final Class<?>[] argPathTypes = new Class<?>[params.length];
                final int[] argPathSegIndex = new int[params.length];
                final String[] argParamNames = new String[params.length];
                final Class<?>[] argParamTypes = new Class<?>[params.length];
                final boolean[] argParamRequired = new boolean[params.length];
                final String[] argParamDefaults = new String[params.length];
                final boolean[] argParamIsFile = new boolean[params.length];
                final boolean[] argParamIsMulti = new boolean[params.length];
                final Class<?>[] argParamComponentTypes = new Class<?>[params.length];

                // Parse path template variables: ${name} (wastnet) or Spring's {name}, with optional inline regex {name:pattern}
                final Map<String, Integer> pathVarSegIndex;
                final String routePattern;
                if (fullPath.indexOf('{') > -1) {
                    if (!fullPath.startsWith("/")) {
                        throw new IllegalArgumentException("Path must start with '/': " + fullPath);
                    }
                    String[] segs = fullPath.split("/", -1);
                    StringBuilder rb = new StringBuilder();
                    pathVarSegIndex = new HashMap<>(segs.length);
                    for (int si = 0; si < segs.length; ++si) {
                        String seg = segs[si];
                        if (si == 0) continue; // leading slash
                        rb.append("/");
                        String[] pv = parsePathVar(seg);
                        if (pv != null) {
                            pathVarSegIndex.put(pv[0], si);
                            rb.append(pv[1] != null ? "(" + pv[1] + ")" : "([^/]+)");
                        } else {
                            rb.append(Pattern.quote(seg));
                        }
                    }
                    routePattern = "^" + rb + "$";
                } else {
                    pathVarSegIndex = null;
                    routePattern = null;
                }

                for (int i = 0; i < params.length; ++i) {
                    Class<?> pType = params[i].getType();
                    if (pType == HttpRequest.class) {
                        argKinds[i] = KIND_REQUEST;
                    } else if (pType == HttpResponse.class) {
                        argKinds[i] = KIND_RESPONSE;
                    } else if (hasAnyAnnotation(params[i].getAnnotations(), requestBodyAnnotations)) {
                        argKinds[i] = KIND_BODY;
                        argBodyTypes[i] = pType;
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
                                argPathTypes[i] = pType;
                                break;
                            }
                            if (isInList(ann, requestParamAnnotations)) {
                                argKinds[i] = KIND_PARAM;
                                argParamNames[i] = readStringAttr(ann, "value", "");
                                argParamTypes[i] = pType;
                                argParamRequired[i] = readBooleanAttr(ann, "required", true);
                                argParamDefaults[i] = readStringAttr(ann, "defaultValue", "");
                                Class<?> elemType = resolveParamElementType(params[i], pType);
                                argParamIsFile[i] = pType == MultipartField.class || pType == MultipartField[].class;
                                argParamIsMulti[i] = pType.isArray() || Collection.class.isAssignableFrom(pType);
                                argParamComponentTypes[i] = elemType;
                                break;
                            }
                        }
                    }
                }
                final boolean isFastPath = argKinds.length == 2 && argKinds[0] == KIND_REQUEST && argKinds[1] == KIND_RESPONSE;
                final boolean hasResponseBody = endpointMethod.getReturnType() != void.class
                        && (classHasResponseBody
                                || hasAnyAnnotation(endpointMethod.getAnnotations(), responseBodyAnnotations));
                if (hasResponseBody && messageConverter == null) {
                    throw new RuntimeException("@ResponseBody on " + endpointMethod.getName()
                            + " requires a messageConverter configured via .messageConverter()");
                }
                final ConverterConfig converterConfig = buildConverterConfig(routeInfo);
                final List<RouterInterceptor> endpointInterceptors = resolveEndpointInterceptors(routeInfo);

                // Build MethodHandle bound to the controller instance
                MethodHandle mh = MethodHandles.lookup().unreflect(endpointMethod);
                final MethodHandle bound = MethodHandles.insertArguments(mh, 0, controller);

                HttpRoute handler;
                if (isFastPath) {
                    // Fast path: (HttpRequest, HttpResponse) — avoid array allocation
                    handler = (p, request, response) -> {
                        if (!applyInterceptors(endpointInterceptors, p, request, response)) {
                            return;
                        }
                        if (hasResponseBody) {
                            Object result = bound.invoke((HttpRequest) request, (HttpResponse) response);
                            converterConfig.beforeResponseBody(request, response, result);
                            messageConverter.write(result, converterConfig, response);
                        } else {
                            bound.invokeExact((HttpRequest) request, (HttpResponse) response);
                        }
                    };
                } else {
                    // General path: unusual signatures (incl. @PathParam)
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
                                    case KIND_BODY:     args[i] = messageConverter != null
                                            ? messageConverter.read(request, converterConfig, argBodyTypes[i]) : null; break;
                                    case KIND_PATH:     args[i] = convertParamValue(segmentAt(p, argPathSegIndex[i]), argPathTypes[i]); break;
                                    case KIND_PARAM:
                                        args[i] = resolveRequestParam(request, argParamNames[i], argParamTypes[i],
                                                argParamComponentTypes[i], argParamRequired[i], argParamDefaults[i],
                                                argParamIsFile[i], argParamIsMulti[i]);
                                        break;
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
                        Object result = bound.invokeWithArguments(args);
                        if (hasResponseBody) {
                            converterConfig.beforeResponseBody(request, response, result);
                            messageConverter.write(result, converterConfig, response);
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

            // SSE endpoints
            for (final MethodRouteInfo routeInfo : sseEndpoints) {
                final String fullPath = combinePath(basePath, routeInfo.getPath());

                MethodHandle mh = MethodHandles.lookup().unreflect(routeInfo.getMethod());
                final MethodHandle bound = MethodHandles.insertArguments(mh, 0, controller);

                sse(fullPath, emitter -> {
                    try {
                        bound.invokeExact(emitter);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                });

                // Interceptors must run before the emitter sends its headers, so guard the
                // registered SSE route rather than the handler itself.
                final List<RouterInterceptor> endpointInterceptors = resolveEndpointInterceptors(routeInfo);
                final HttpRoute sseRoute = exactRoutes.get(fullPath);
                if (sseRoute != null) {
                    exactRoute(fullPath, (p, request, response) -> {
                        if (!applyInterceptors(endpointInterceptors, p, request, response)) return;
                        sseRoute.handle(p, request, response);
                    });
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to register controller: " + clazz.getName(), e);
        }
    }

    /**
     * Check if a class is eligible for registration: must be public and non-abstract.
     */
    private static boolean isEligibleClass(Class<?> clazz) {
        int mod = clazz.getModifiers();
        return Modifier.isPublic(mod) && !Modifier.isAbstract(mod);
    }

    private Object resolveInstance(Class<?> clazz) throws Exception {
        Constructor<?> ctor = findConstructor(clazz.getConstructors());
        Parameter[] params;
        if (ctor == null || (params = ctor.getParameters()).length == 0) return clazz.getDeclaredConstructor().newInstance();
        Object[] args = resolveParameters(params, clazz.getName());
        if (args == null) return null;
        return ctor.newInstance(args);
    }

    /**
     * Select a constructor for injection, preferring one annotated with {@code @Inject}.
     *
     * @param constructors all public constructors of the class
     * @return the {@code @Inject}-annotated constructor if exactly one found,
     *         or the first constructor as fallback
     * @throws RuntimeException if multiple constructors are annotated with {@code @Inject}
     */
    private Constructor<?> findConstructor(Constructor<?>[] constructors) {
        Constructor<?> injectCtor = null;
        Constructor<?> first = null;
        for (Constructor<?> ctor : constructors) {
            if (first == null) first = ctor;
            if (beanContainer.findInjectAnnotation(ctor) != null) {
                if (injectCtor != null) {
                    throw new RuntimeException("Multiple @Inject constructors in "
                            + ctor.getDeclaringClass().getName());
                }
                injectCtor = ctor;
            }
        }
        return injectCtor != null ? injectCtor : first;
    }

    /**
     * Resolve method/constructor parameters supporting {@code @Value}, {@code @Inject(name)},
     * or by-type lookup from the container.
     *
     * @return args if all resolved, or null if any {@code @Inject} dependency is not yet available
     */
    private Object[] resolveParameters(Parameter[] params, String context) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; ++i) {
            Parameter param = params[i];
            Annotation valueAnn = beanContainer.findValueAnnotation(param);
            if (valueAnn != null) {
                String expression = resolver.resolveValueExpression(valueAnn);
                args[i] = beanContainer.resolveValue(expression, param.getType());
                continue;
            }
            Annotation injectAnn = beanContainer.findInjectAnnotation(param);
            if (injectAnn != null) {
                String injectName = resolver.resolveInjectName(injectAnn);
                args[i] = !injectName.isEmpty()
                        ? beanContainer.getBean(injectName) : beanContainer.getBean(param.getType());
                if (args[i] == null) return null; // deferrable
                continue;
            }
            args[i] = beanContainer.getBean(param.getType());
            if (args[i] == null) {
                throw new RuntimeException("Cannot resolve dependency '"
                        + param.getType().getSimpleName() + "' for " + context);
            }
        }
        return args;
    }

    protected ConverterConfig buildConverterConfig(MethodRouteInfo routeInfo) {
        ConverterConfig config = new ConverterConfig();
        if (routeInfo.getResponseType() != null) {
            config.responseType(routeInfo.getResponseType());
        }
        return config;
    }

    private static final int KIND_REQUEST = 0;
    private static final int KIND_RESPONSE = 1;
    private static final int KIND_BODY = 2;
    private static final int KIND_PATH = 3;
    private static final int KIND_PARAM = 4;

    private static boolean hasAnyAnnotation(Annotation[] anns, Class<?>[] list) {
        for (Annotation ann : anns) {
            if (isInList(ann, list)) return true;
        }
        return false;
    }

    private static boolean isInList(Annotation ann, Class<?>[] list) {
        Class<? extends Annotation> t = ann.annotationType();
        for (Class<?> c : list) {
            if (c.equals(t)) return true;
        }
        return false;
    }

    private static String readStringAttr(Annotation ann, String method, String def) {
        try {
            Method m = ann.annotationType().getMethod(method);
            Object v = m.invoke(ann);
            return v != null ? v.toString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean readBooleanAttr(Annotation ann, String method, boolean def) {
        try {
            Method m = ann.annotationType().getMethod(method);
            return ((Boolean) m.invoke(ann)).booleanValue();
        } catch (Exception e) {
            return def;
        }
    }

    private static Object convertParamValue(String value, Class<?> type) {
        if (value == null) value = "";
        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) return Integer.parseInt(value);
        if (type == long.class || type == Long.class) return Long.parseLong(value);
        if (type == short.class || type == Short.class) return Short.parseShort(value);
        if (type == byte.class || type == Byte.class) return Byte.parseByte(value);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == double.class || type == Double.class) return Double.parseDouble(value);
        if (type == float.class || type == Float.class) return Float.parseFloat(value);
        return value;
    }

    // Resolve the element type of an array or collection request parameter (String fallback for raw collections).
    private static Class<?> resolveParamElementType(Parameter param, Class<?> type) {
        if (type.isArray()) return type.getComponentType();
        if (Collection.class.isAssignableFrom(type)) {
            Type gt = param.getParameterizedType();
            if (gt instanceof ParameterizedType) {
                Type[] tas = ((ParameterizedType) gt).getActualTypeArguments();
                if (tas.length > 0 && tas[0] instanceof Class) return (Class<?>) tas[0];
            }
            return String.class;
        }
        return type;
    }

    // Bind a @RequestParam parameter: scalar, multi-value (array/Collection) or file (MultipartField).
    private static Object resolveRequestParam(HttpRequest request, String name, Class<?> type,
            Class<?> elemType, boolean required, String def, boolean isFile, boolean isMulti) {
        if (isFile) return resolveFileParam(request, name, type, required);
        if (isMulti) {
            List<String> vals = request.getParameterValues(name);
            if (vals == null) {
                if (required) throw new IllegalArgumentException("Missing required parameter: " + name);
                return null;
            }
            // List<String> keeps the raw value list; other element types fall through to conversion.
            if (elemType == String.class && List.class.isAssignableFrom(type)) {
                return vals;
            }
            Object array = Array.newInstance(elemType, vals.size());
            for (int k = 0; k < vals.size(); ++k) Array.set(array, k, convertParamValue(vals.get(k), elemType));
            return type.isArray() ? array : toCollection(type, (Object[]) array);
        }
        String pv = request.getParameter(name);
        if (pv == null) {
            if (required) throw new IllegalArgumentException("Missing required parameter: " + name);
            pv = def.isEmpty() ? null : def;
        }
        return pv == null ? null : convertParamValue(pv, type);
    }

    // Bind a multipart file parameter: single MultipartField or MultipartField[] only.
    private static Object resolveFileParam(HttpRequest request, String name, Class<?> type, boolean required) {
        if (type.isArray()) {
            List<MultipartField> fields = request.getMultipartFields(name);
            if (fields == null) {
                if (required) throw new IllegalArgumentException("Missing required file parameter: " + name);
                return null;
            }
            return fields.toArray(new MultipartField[0]);
        }
        MultipartField field = request.getMultipartField(name);
        if (field == null && required) throw new IllegalArgumentException("Missing required file parameter: " + name);
        return field;
    }

    private static Object toCollection(Class<?> type, Object[] arr) {
        if (Set.class.isAssignableFrom(type) || SortedSet.class.isAssignableFrom(type)) {
            return new LinkedHashSet<>(Arrays.asList(arr));
        }
        List<Object> list = new ArrayList<>(arr.length);
        list.addAll(Arrays.asList(arr));
        return list;
    }

    // Fetch the 1-based path segment at the precomputed position (see argPathSegIndex),
    // avoiding the cost of String.split at request time.
    private static String segmentAt(String p, int pos) {
        int start = -1, seg = 0, n = p.length();
        for (int i = 0; i < n; ++i) {
            if (p.charAt(i) == '/') {
                if (start >= 0 && seg == pos) return p.substring(start, i);
                start = i + 1;
                ++seg;
            }
        }
        return start >= 0 && seg == pos ? p.substring(start) : "";
    }

    // Parse a path segment as a variable placeholder: supports ${name} (wastnet) and Spring's
    // {name}, with optional inline regex {name:pattern}. Returns [name, regex] or null for a
    // non-variable segment (including an empty "{}" placeholder).
    private static String[] parsePathVar(String seg) {
        int len = seg.length();
        boolean dbl = seg.startsWith("${") && seg.endsWith("}");
        boolean sgl = !dbl && seg.startsWith("{") && seg.endsWith("}");
        if (len < 2 || (!dbl && !sgl)) return null;
        String inner = seg.substring(dbl ? 2 : 1, len - 1);
        if (inner.isEmpty()) return null;
        int colon = inner.indexOf(':');
        if (colon > -1) return new String[]{inner.substring(0, colon), inner.substring(colon + 1)};
        return new String[]{inner, null};
    }

    private static String combinePath(String base, String path) {
        if (base == null || base.isEmpty() || "/".equals(base)) {
            return path.startsWith("/") ? path : "/" + path;
        }
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return base;
        }
        return base + (path.startsWith("/") ? path : "/" + path);
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
        beanContainer.setProperty(key, value);
        return this;
    }

    /**
     * Set multiple configuration properties available for {@code @Value} injection.
     */
    public AnnotationRouterHandler properties(Map<String, String> props) {
        beanContainer.setProperties(props);
        return this;
    }

    /**
     * Load configuration from one or more classpath {@code .properties} files.
     * <p>
     * Example:
     * <pre>{@code
     * new AnnotationRouterHandler().loadProperties("application.properties")
     * }</pre>
     */
    public AnnotationRouterHandler loadProperties(String... classpathResources) {
        beanContainer.loadProperties(classpathResources);
        return this;
    }

    /**
     * Load configuration via a custom {@link ConfigLoader}.
     * <p>
     * Example:
     * <pre>{@code
     * new AnnotationRouterHandler().loadConfig(config -> {
     *     config.put("key", value);
     * })
     * }</pre>
     */
    public AnnotationRouterHandler loadConfig(ConfigLoader loader) {
        beanContainer.loadConfig(loader);
        return this;
    }

    /**
     * Clear all registered routes and release component instances.
     */
    @Override
    public void clear() {
        super.clear();
        beanContainer.clear();
    }
}
