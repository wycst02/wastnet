package io.github.wycst.wastnet.http.annotation;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, internal bean container.
 * <p>
 * Not intended for public use — exposed only for the convenience of
 * {@link AnnotationRouterHandler} internal wiring.
 *
 * @author wangyc
 */
public class BeanContainer {

    private AnnotationResolver resolver;

    void setResolver(AnnotationResolver resolver) {
        this.resolver = resolver;
    }

    private final Map<String, Object> beansByName = new ConcurrentHashMap<String, Object>();
    private final Map<Class<?>, Object> beansByType = new ConcurrentHashMap<Class<?>, Object>();
    private final Map<String, String> config = new HashMap<String, String>();

    // Pluggable annotation classes (default to framework's own)
    private Class<?>[] valueAnnotations = new Class<?>[]{Value.class};
    private Class<?>[] injectAnnotations = new Class<?>[]{Inject.class};
    private Class<?>[] beanAnnotations = new Class<?>[]{Bean.class};
    private Class<?>[] postConstructAnnotations = new Class<?>[]{PostConstruct.class};
    private Class<?>[] preDestroyAnnotations = new Class<?>[]{PreDestroy.class};

    // ── Registration & lifecycle ──

    /** Register a bean into the container without field injection or lifecycle. */
    void register(String name, Object instance) {
        beansByName.put(name, instance);
        beansByType.put(instance.getClass(), instance);
    }

    /** Inject fields on all registered beans (dependencies are already in the container). */
    void injectAllFields() {
        for (Object bean : beansByName.values()) {
            try {
                injectFields(bean);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject fields on " + bean.getClass().getName(), e);
            }
        }
    }

    /** Invoke @PostConstruct on all registered beans (call after injection is complete). */
    void invokeAllPostConstruct() {
        for (Object bean : beansByName.values()) {
            invokeLifecycle(bean, postConstructAnnotations);
        }
    }

    void clear() {
        for (Object bean : beansByName.values()) {
            invokeLifecycle(bean, preDestroyAnnotations);
        }
        beansByName.clear();
        beansByType.clear();
        config.clear();
    }

    // ── Annotation mapping ──

    void setValueAnnotations(Class<?>... anns) {
        this.valueAnnotations = anns;
    }

    void setInjectAnnotations(Class<?>... anns) {
        this.injectAnnotations = anns;
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

    void setProperty(String key, String value) {
        config.put(key, value);
    }

    void setProperties(Map<String, String> props) {
        config.putAll(props);
    }

    void loadProperties(String... classpathResources) {
        for (String resource : classpathResources) {
            InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resource);
            if (in == null) continue;
            try {
                Properties props = new Properties();
                props.load(in);
                for (String key : props.stringPropertyNames()) {
                    config.put(key, props.getProperty(key));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load config: " + resource, e);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    void loadConfig(ConfigLoader loader) {
        loader.load(config);
    }

    /**
     * Resolve a {@code @Value} expression ({@code ${key:default}}) to the target type.
     * Used by {@link AnnotationRouterHandler} for constructor/method parameter injection.
     */
    Object resolveValue(String expression, Class<?> targetType) {
        String raw = resolvePlaceholder(expression);
        return convertValue(raw, targetType);
    }

    // ── Field injection ──

    private void injectFields(Object bean) throws Exception {
        Class<?> clazz = bean.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            Annotation valueAnn = findAnnotation(field, valueAnnotations);
            if (valueAnn != null) {
                injectValue(bean, field, valueAnn);
            } else {
                Annotation injectAnn = findAnnotation(field, injectAnnotations);
                if (injectAnn != null) {
                    injectDependency(bean, field, injectAnn);
                }
            }
        }
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
        Object converted = convertValue(resolved, field.getType());
        field.setAccessible(true);
        field.set(bean, converted);
    }

    // ── Placeholder resolution ──

    private String resolvePlaceholder(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        while (true) {
            int start = raw.indexOf("${", cursor);
            if (start == -1) {
                sb.append(raw, cursor, raw.length());
                break;
            }
            int end = raw.indexOf('}', start + 2);
            if (end == -1) {
                sb.append(raw, cursor, raw.length());
                break;
            }
            sb.append(raw, cursor, start);
            String inner = raw.substring(start + 2, end);
            int colon = inner.indexOf(':');
            String key = (colon > -1) ? inner.substring(0, colon) : inner;
            String def = (colon > -1) ? inner.substring(colon + 1) : null;
            String value = config.get(key);
            sb.append(value != null ? value : (def != null ? def : raw.substring(start, end + 1)));
            cursor = end + 1;
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T convertValue(String value, Class<T> targetType) {
        if (value == null) return null;
        if (targetType == String.class) return (T) value;
        if (targetType == int.class || targetType == Integer.class) return (T) Integer.valueOf(value);
        if (targetType == long.class || targetType == Long.class) return (T) Long.valueOf(value);
        if (targetType == boolean.class || targetType == Boolean.class) return (T) Boolean.valueOf(value);
        if (targetType == double.class || targetType == Double.class) return (T) Double.valueOf(value);
        if (targetType == float.class || targetType == Float.class) return (T) Float.valueOf(value);
        if (targetType == short.class || targetType == Short.class) return (T) Short.valueOf(value);
        if (targetType == byte.class || targetType == Byte.class) return (T) Byte.valueOf(value);
        return null;
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

    @SuppressWarnings("unchecked")
    <T> T getBean(Class<T> type) {
        return (T) beansByType.get(type);
    }

    /** Look up a bean by its registration name. */
    Object getBean(String name) {
        return beansByName.get(name);
    }

    /**
     * Expose all registered bean instances for iteration (e.g. @Bean method scanning).
     */
    Collection<Object> getBeans() {
        return beansByName.values();
    }
}
