package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.util.ConfigLoader;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, internal bean container.
 * <p>
 * Not intended for public use — exposed only for the convenience of
 * {@link AnnotationRouterHandler} internal wiring.
 *
 * @author wangyc
 */
public class BeanContainer implements RegistrarContext {

    private AnnotationResolver resolver;

    void setResolver(AnnotationResolver resolver) {
        this.resolver = resolver;
    }

    private final Map<String, BeanDefinition> beanDefinitions = new ConcurrentHashMap<>();
    private final Map<Class<?>, BeanDefinition> beansByType = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<String, BeanDefinition>> beansByAssignableType = new ConcurrentHashMap<>();

    // Static configuration: set programmatically via property()/properties().
    // It is preserved across hot reloads (only clearAll() wipes it).
    private final Map<String, String> staticConfig = new HashMap<String, String>();
    // Scan configuration: loaded from a config file at the start of each scan()/doScan() (see
    // loadScanProperties). It is re-read on every hot reload, so editing the file takes effect on reload.
    private final Map<String, String> scanConfig = new HashMap<String, String>();

    // Pluggable annotation classes (default to framework's own)
    private Class<?>[] valueAnnotations = new Class<?>[]{Value.class};
    private Class<?>[] injectAnnotations = new Class<?>[]{Inject.class};
    private Class<?>[] beanAnnotations = new Class<?>[]{Bean.class};
    private Class<?>[] postConstructAnnotations = new Class<?>[]{PostConstruct.class};
    private Class<?>[] preDestroyAnnotations = new Class<?>[]{PreDestroy.class};

    // ── Registration & lifecycle ──

    /**
     * Register a bean. {@code asFactory} marks {@code @Bean} results whose concrete type is
     * name-qualified (multiple instances of the same type allowed); class-annotated beans are singletons.
     */
    void register(String name, Object instance, boolean asFactory) {
        register(name, new BeanDefinition(instance.getClass(), instance, true, false), asFactory);
    }

    /**
     * Register a fully-built {@link BeanDefinition}. The definition already carries the
     * original source class, the live instance, the singleton flag and the proxy flag.
     */
    void register(String name, BeanDefinition bd, boolean asFactory) {
        Object instance = bd.getInstance();
        // Reject the same instance registered under more than one name (1-to-1).
        for (Map.Entry<String, BeanDefinition> e : beanDefinitions.entrySet()) {
            if (e.getValue().getInstance() == instance) {
                throw new IllegalStateException(
                        "Same instance already registered as bean: " + e.getKey());
            }
        }
        if (beanDefinitions.putIfAbsent(name, bd) != null) {
            throw new IllegalStateException("Duplicate bean name: " + name);
        }
        Class<?> implClass = bd.getInstance().getClass();
        ClassLoader loader = implClass.getClassLoader();
        if (!asFactory) {
            // class-annotated bean: concrete type is a singleton (one instance per scanned class)
            beansByType.put(implClass, bd);
        }
        indexAssignableTypes(bd, name, implClass, loader, asFactory);
        // Map the declared source type into beansByType so getBean(sourceType) resolves; implClass stays ambiguous under beansByAssignableType.
        if (bd.getSourceClass() != implClass) {
            beansByType.put(bd.getSourceClass(), bd);
        }
    }

    private void indexAssignableTypes(BeanDefinition bd, String name, Class<?> implClass,
                                       ClassLoader loader, boolean includeConcrete) {
        for (Class<?> type : collectAssignableTypes(implClass, new LinkedHashSet<Class<?>>())) {
            if (type.getClassLoader() != loader) continue;
            if (type == implClass) {
                if (!includeConcrete) continue;
            } else if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            registerUnderType(beansByAssignableType, type, name, bd);
        }
    }

    private static void registerUnderType(Map<Class<?>, Map<String, BeanDefinition>> index,
                                          Class<?> type, String name, BeanDefinition bd) {
        Map<String, BeanDefinition> byName = index.computeIfAbsent(type, k -> new ConcurrentHashMap<String, BeanDefinition>(4));
        if (byName.putIfAbsent(name, bd) != null) {
            throw new IllegalStateException("Duplicate bean of type " + type.getName() + " with name: " + name);
        }
    }

    private static Set<Class<?>> collectAssignableTypes(Class<?> clazz, Set<Class<?>> out) {
        if (clazz == null || clazz == Object.class) return out;
        if (out.add(clazz)) {
            for (Class<?> iface : clazz.getInterfaces()) {
                collectAssignableTypes(iface, out);
            }
        }
        return collectAssignableTypes(clazz.getSuperclass(), out);
    }

    /** Inject fields on all registered beans (dependencies are already in the container). */
    void injectAllFields() {
        for (BeanDefinition bd : beanDefinitions.values()) {
            try {
                injectFields(bd.getInstance(), bd.getSourceClass(), true);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject fields on " + bd.getSourceClass().getName(), e);
            }
        }
    }

    // ── Field injection ──

    /**
     * Inject {@code @Value} fields (and, when {@code dependencies} is true, {@code @Inject} fields) of
     * {@code bean}. {@code sourceClass} is the original annotated class so proxied (CGLIB subclass) beans
     * are handled correctly. Pass {@code dependencies = false} for the pre-{@code @Bean} phase on a
     * {@code @Configuration} whose dependencies are not yet resolvable.
     */
    void injectFields(Object bean, Class<?> sourceClass, boolean dependencies) throws Exception {
        for (Field field : sourceClass.getDeclaredFields()) {
            Annotation valueAnn = findAnnotation(field, valueAnnotations);
            if (valueAnn != null) {
                injectValue(bean, field, valueAnn);
            } else if (dependencies) {
                Annotation injectAnn = findAnnotation(field, injectAnnotations);
                if (injectAnn != null) {
                    injectDependency(bean, field, injectAnn);
                }
            }
        }
    }

    private void injectDependency(Object bean, Field field, Annotation ann) throws Exception {
        String injectName = resolver.resolveInjectName(ann);
        Object dependency = !injectName.isEmpty()
                ? getBean(injectName) : getBean(field.getType());
        if (dependency == null) {
            throw new RuntimeException(
                    "Cannot resolve dependency for field '" + field.getName()
                            + "' of type " + field.getType().getName()
                            + " in " + bean.getClass().getName()
                            + ". Make sure the dependency is annotated with @Component.");
        }
        field.setAccessible(true);
        field.set(bean, dependency);
    }

    private void injectValue(Object bean, Field field, Annotation ann) throws Exception {
        String expression = resolver.resolveValueExpression(ann);
        String resolved = resolvePlaceholder(expression);
        ParamValueConverters.ensureConvertible(field.getType());
        Object converted = convertValue(resolved, field.getType());
        field.setAccessible(true);
        field.set(bean, converted);
    }

    Annotation findAnnotation(AnnotatedElement element, Class<?>... anns) {
        for (Class<?> ann : anns) {
            if (ann != null) {
                Annotation present = element.getAnnotation(ann.asSubclass(Annotation.class));
                if (present != null) return present;
            }
        }
        return null;
    }

    /** Invoke @PostConstruct on all registered beans (call after injection is complete). */
    void invokeAllPostConstruct() {
        for (BeanDefinition bd : beanDefinitions.values()) {
            invokeLifecycle(bd.getInstance(), postConstructAnnotations);
        }
    }

    /**
     * Clear only the scan-scoped state: scanned bean instances (with {@code @PreDestroy}) and the
     * scan configuration. Programmatic static configuration is preserved so it survives hot reloads.
     */
    void clearScan() {
        for (BeanDefinition bd : beanDefinitions.values()) {
            invokeLifecycle(bd.getInstance(), preDestroyAnnotations);
        }
        beanDefinitions.clear();
        beansByType.clear();
        beansByAssignableType.clear();
        scanConfig.clear();
    }

    /** Full teardown: also drops programmatic static configuration. Used by a complete {@code clear()}. */
    void clearAll() {
        clearScan();
        staticConfig.clear();
    }

    // ── Annotation mapping ──

    void setValueAnnotations(Class<?>... anns) {
        this.valueAnnotations = anns;
    }

    void setInjectAnnotations(Class<?>... anns) {
        this.injectAnnotations = anns;
    }

    void setBeanAnnotations(Class<?>... anns) {
        this.beanAnnotations = anns;
    }

    Annotation findValueAnnotation(AnnotatedElement element) {
        return findAnnotation(element, valueAnnotations);
    }

    Annotation findInjectAnnotation(AnnotatedElement element) {
        return findAnnotation(element, injectAnnotations);
    }

    Annotation findBeanAnnotation(AnnotatedElement element) {
        return findAnnotation(element, beanAnnotations);
    }

    void setPostConstructAnnotations(Class<?>... anns) {
        this.postConstructAnnotations = anns;
    }

    void setPreDestroyAnnotations(Class<?>... anns) {
        this.preDestroyAnnotations = anns;
    }

    // ── Config ──

    /**
     * Contributes a static configuration value, preserved across hot reloads.
     *
     * @param key the configuration key
     * @param value the configuration value
     */
    void setStaticProperty(String key, String value) {
        staticConfig.put(key, value);
    }

    /**
     * Contributes multiple static configuration values at once, preserved across hot reloads.
     *
     * @param props the key-value pairs to add
     */
    void addStaticProperties(Map<String, String> props) {
        staticConfig.putAll(props);
    }

    /** {@inheritDoc} */
    @Override
    public void addProperty(String key, String value) {
        scanConfig.put(key, value);
    }

    /** {@inheritDoc} */
    @Override
    public void addProperties(Map<String, String> props) {
        scanConfig.putAll(props);
    }

    /**
     * Load scan configuration from the given classpath resources, using the context class loader.
     * Convenience overload for callers without an explicit loader (e.g. tests).
     */
    void loadScanProperties(String... classpathResources) {
        loadScanProperties(Thread.currentThread().getContextClassLoader(), false, classpathResources);
    }

    /**
     * Load scan configuration from the given classpath resources. Each resource is searched in standard
     * locations (classpath root, classpath /config, jar directory, jar /config, parent /config), in
     * increasing priority. Re-read on every scan/hot reload, so editing a config file takes effect on
     * reload. When {@code ignoreInternal} is true the two classpath (jar-internal) resources are skipped.
     *
     * @param cl             class loader used to resolve classpath resources (same active loader as class scanning)
     * @param ignoreInternal skip classpath resources when true (external files only)
     * @param classpathResources config file names to load
     */
    void loadScanProperties(ClassLoader cl, boolean ignoreInternal, String... classpathResources) {
        for (String resource : classpathResources) {
            if (resource == null || resource.isEmpty()) continue;
            String name = resource.startsWith("/") ? resource.substring(1) : resource;
            Properties props = ConfigLoader.createFileProps(cl, name, ignoreInternal);
            for (String key : props.stringPropertyNames()) {
                scanConfig.put(key, props.getProperty(key));
            }
        }
    }

    /**
     * Resolve a config key across system properties ({@code -D}, highest priority), scan configuration
     * (re-read from files on hot reload, plus registrar-contributed values added during {@code onRegister}) and
     * static configuration (programmatic, preserved). A {@code -D} system property overrides all others.
     */
    @Override
    public String getConfig(String key) {
        // A -D system property (env override) takes the highest priority; skip empty keys
        // (e.g. the bare "${}" placeholder) since System.getProperty("") throws.
        if (key != null && !key.isEmpty()) {
            String value = System.getProperty(key);
            if (value != null) return value;
        }
        String value = scanConfig.get(key);
        if (value != null) return value;
        return staticConfig.get(key);
    }

    /** Resolve {@code key}, falling back to {@code def} when the key is absent (null if no default). */
    @Override
    public String getConfig(String key, String def) {
        String value = getConfig(key);
        return value != null ? value : def;
    }

    /**
     * Resolve a {@code @Value} expression ({@code ${key:default}}) to the target type.
     * Used by {@link AnnotationRouterHandler} for constructor/method parameter injection.
     */
    Object resolveValue(String expression, Class<?> targetType) {
        String raw = resolvePlaceholder(expression);
        return convertValue(raw, targetType);
    }

    // ── Placeholder resolution ──

    public String resolvePlaceholder(String raw) {
        if (raw == null) return null;
        int cursor = 0;
        StringBuilder sb = null;
        while (true) {
            int start = raw.indexOf("${", cursor);
            if (start == -1) {
                if (sb == null) return raw;             // no placeholder: fast path, no StringBuilder
                sb.append(raw, cursor, raw.length());
                break;
            }
            int end = raw.indexOf('}', start + 2);
            if (end == -1) {
                if (sb == null) return raw;             // malformed but nothing resolved yet: return as-is
                sb.append(raw, cursor, raw.length());
                break;
            }
            if (sb == null) sb = new StringBuilder(raw.length());
            sb.append(raw, cursor, start);
            String inner = raw.substring(start + 2, end);
            int colon = inner.indexOf(':');
            String key = (colon > -1) ? inner.substring(0, colon).trim() : inner.trim();
            String def = (colon > -1) ? inner.substring(colon + 1).trim() : null;
            String resolved = getConfig(key, def);
            if (resolved == null) {
                throw new IllegalStateException("Missing configuration for key '" + key
                        + "' (no default provided) in expression: " + raw);
            }
            sb.append(resolved);
            cursor = end + 1;
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static <T> T convertValue(String value, Class<T> targetType) {
        if (value == null) return null;
        return (T) ParamValueConverters.resolve(targetType).apply(value);
    }

    // ── Method callbacks ──
    private static void invokeLifecycle(Object bean, Class<?>... annTypes) {
        for (Method method : bean.getClass().getMethods()) {
            if (method.getParameterCount() == 0) {
                for (Class<?> annType : annTypes) {
                    if (annType != null && method.isAnnotationPresent(annType.asSubclass(Annotation.class))) {
                        try {
                            method.invoke(bean);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to invoke @" + annType.getSimpleName()
                                    + " on " + bean.getClass().getName(), e);
                        }
                        break;
                    }
                }
            }
        }
    }

    @Override
    public <T> T getBean(Class<T> type) {
        BeanDefinition direct = beansByType.get(type);
        if (direct != null) return type.cast(direct.getInstance());
        Map<String, BeanDefinition> byName = beansByAssignableType.get(type);
        if (byName == null) return null;
        if (byName.size() == 1) return type.cast(byName.values().iterator().next().getInstance());
        throw new IllegalStateException("Multiple beans of type " + type.getName()
                + "; qualify by name via @Inject(\"name\"): " + byName.keySet());
    }

    /** Look up a bean by its registration name. */
    @Override
    public Object getBean(String name) {
        BeanDefinition bd = beanDefinitions.get(name);
        return bd != null ? bd.getInstance() : null;
    }

    /** Bridge method for {@link BeanRegistrationHandler}s; registers as a singleton indexed by type. */
    @Override
    public void registerBean(String name, Object instance) {
        register(name, instance, false);
    }

    /** {@inheritDoc} */
    @Override
    public <T> void registerBean(Class<T> type, Object instance) {
        if (!type.isInstance(instance)) {
            throw new IllegalArgumentException("Instance is not assignable to " + type.getName());
        }
        register(type.getName(), new BeanDefinition(type, instance, true, false), false);
    }

    /** Look up a bean's definition by registration name; exposes sourceClass for proxied beans. */
    BeanDefinition getBeanDefinition(String name) {
        return beanDefinitions.get(name);
    }

    /**
     * Expose all registered bean definitions for iteration (e.g. interceptor scanning).
     */
    Collection<BeanDefinition> getBeans() {
        return beanDefinitions.values();
    }

}
