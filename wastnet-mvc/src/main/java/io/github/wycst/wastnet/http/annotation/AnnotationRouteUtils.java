package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Stateless helpers for {@link AnnotationRouterHandler}: class / registrar scanning,
 * annotation attribute reading, and request-parameter / path-variable binding.
 * <p>
 * Every member is a pure function with no handler state, so they live outside the
 * stateful handler and are imported statically where needed.
 */
final class AnnotationRouteUtils {

    private AnnotationRouteUtils() {}

    // ── Scan / registrar helpers ──

    /** Locate a public no-arg constructor, or null if the class declares none. */
    static Constructor<?> findPublicNoArgConstructor(Class<?> clazz) {
        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.getParameterCount() == 0) return c;
        }
        return null;
    }

    /** Locate the public constructor accepting a single {@link RegistrarContext}, or null if none. */
    static Constructor<?> findRegistrarConstructor(Class<?> clazz) {
        for (Constructor<?> c : clazz.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 1 && params[0] == RegistrarContext.class) {
                return c;
            }
        }
        return null;
    }

    /** Order of a registrar from its {@link Registration#order()}, defaulting to {@link Integer#MAX_VALUE}. */
    static int registrarOrder(BeanRegistrationHandler handler) {
        Registration r = handler.getClass().getAnnotation(Registration.class);
        return r != null ? r.order() : Integer.MAX_VALUE;
    }

    /** Stable sort by @Interceptor.order(). */
    static void sortByOrder(List<BeanDefinition> chain) {
        chain.sort((a, b) -> {
            Integer oa = a.getSourceClass().getAnnotation(Interceptor.class).order();
            Integer ob = b.getSourceClass().getAnnotation(Interceptor.class).order();
            return oa.compareTo(ob);
        });
    }

    /** A registrar candidate: implements BeanRegistrationHandler and is bound via @Registration. */
    static boolean isRegistrar(Class<?> c) {
        return BeanRegistrationHandler.class.isAssignableFrom(c)
                && c.isAnnotationPresent(Registration.class);
    }

    /**
     * Check if a class is eligible for registration: must be public, non-abstract, and not an
     * anonymous class (anonymous classes have no name and must not be registered as components).
     */
    static boolean isEligibleClass(Class<?> clazz) {
        if (clazz.isAnonymousClass()) {
            return false;
        }
        int mod = clazz.getModifiers();
        return Modifier.isPublic(mod) && !Modifier.isAbstract(mod);
    }

    // ── Annotation attribute reading ──

    /** Best-effort raw-type assertion that the value returned by {@link HttpMessageConverter#read} is assignable to the endpoint's body parameter type. */
    static Object assertBodyAssignable(Object value, Class<?> targetClass) {
        if (value == null) return null;
        if (targetClass != null && !targetClass.isPrimitive() && !targetClass.isInstance(value)) {
            throw new IllegalArgumentException("HttpMessageConverter returned "
                    + value.getClass().getName() + " but the endpoint body expects " + targetClass.getName());
        }
        return value;
    }

    static boolean hasAnyAnnotation(Annotation[] anns, Class<?>[] list) {
        for (Annotation ann : anns) {
            if (isInList(ann, list)) return true;
        }
        return false;
    }

    static boolean isInList(Annotation ann, Class<?>[] list) {
        Class<? extends Annotation> t = ann.annotationType();
        for (Class<?> c : list) {
            if (c.equals(t)) return true;
        }
        return false;
    }

    static String readStringAttr(Annotation ann, String method, String def) {
        try {
            Method m = ann.annotationType().getMethod(method);
            return m.invoke(ann).toString();
        } catch (Exception e) {
            return def;
        }
    }

    static boolean readBooleanAttr(Annotation ann, String method, boolean def) {
        try {
            Method m = ann.annotationType().getMethod(method);
            return (Boolean) m.invoke(ann);
        } catch (Exception e) {
            return def;
        }
    }

    // ── Request parameter / path binding ──

    /** Resolve the converter for a parameter value type. Enums are handled generically via {@link Enum#valueOf}; unregistered types fall back to null, so a converter must be registered via {@link AnnotationRouterHandler#registerParamConverter} to support a custom type. */
    static Function<String, Object> resolveConverter(Class<?> type) {
        return ParamValueConverters.resolve(type);
    }

    // Resolve the element type of an array or collection request parameter (String fallback for raw collections).
    static Class<?> resolveParamElementType(Parameter param, Class<?> type) {
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

    // Scalar / multi-value binding shared by @RequestParam and @RequestHeader. singleGetter returns the raw
    // scalar (null when absent), multiGetter returns all values (null when absent); label customizes the error.
    private static Object resolveSimpleParam(HttpRequest request, String name, Class<?> type, Class<?> elemType,
            boolean required, String def, boolean isMulti,
            Function<String, Object> converter, Function<String, Object> elemConverter,
            String label, Function<String, String> singleGetter, Function<String, List<String>> multiGetter) {
        if (isMulti) {
            List<String> vals = multiGetter.apply(name);
            if (vals == null) {
                if (required) throw new IllegalArgumentException("Missing required " + label + ": " + name);
                return null;
            }
            if (elemType == String.class && List.class.isAssignableFrom(type)) {
                return vals;
            }
            Object array = Array.newInstance(elemType, vals.size());
            for (int k = 0; k < vals.size(); ++k) Array.set(array, k, elemConverter.apply(vals.get(k)));
            return type.isArray() ? array : toCollection(type, (Object[]) array);
        }
        String v = singleGetter.apply(name);
        if (v == null) {
            if (!def.isEmpty()) return converter.apply(def);
            if (required) throw new IllegalArgumentException("Missing required " + label + ": " + name);
            return type.isPrimitive() ? Array.get(Array.newInstance(type, 1), 0) : null;
        }
        return converter.apply(v);
    }

    // Bind a @RequestParam: scalar, multi-value (array/Collection) or file (MultipartField). File params are
    // handled by resolveFileParam; the rest delegates to resolveSimpleParam with no request-time type lookup.
    static Object resolveRequestParam(HttpRequest request, String name, Class<?> type,
            Class<?> elemType, boolean required, String def, boolean isFile, boolean isMulti,
            Function<String, Object> converter, Function<String, Object> elemConverter) {
        if (isFile) return resolveFileParam(request, name, type, required);
        return resolveSimpleParam(request, name, type, elemType, required, def, isMulti, converter, elemConverter,
                "parameter", request::getParameter, request::getParameterValues);
    }

    // Bind a @RequestHeader: scalar or multi-value (array/Collection), delegating to resolveSimpleParam.
    static Object resolveRequestHeader(HttpRequest request, String name, Class<?> type,
            Class<?> elemType, boolean required, String def, boolean isMulti,
            Function<String, Object> converter, Function<String, Object> elemConverter) {
        return resolveSimpleParam(request, name, type, elemType, required, def, isMulti, converter, elemConverter,
                "header", request::getHeader, request::getFullHeader);
    }

    // Bind a multipart file parameter: single MultipartField or MultipartField[] only.
    static Object resolveFileParam(HttpRequest request, String name, Class<?> type, boolean required) {
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

    static Object toCollection(Class<?> type, Object[] arr) {
        if (SortedSet.class.isAssignableFrom(type)) {
            return new TreeSet<>(Arrays.asList(arr));
        }
        if (Set.class.isAssignableFrom(type)) {
            return new LinkedHashSet<>(Arrays.asList(arr));
        }
        List<Object> list = new ArrayList<>(arr.length);
        list.addAll(Arrays.asList(arr));
        return list;
    }

    // Fetch the 1-based path segment at the precomputed position (see argPathSegIndex),
    // avoiding the cost of String.split at request time.
    static String segmentAt(String p, int pos) {
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

    // Parse a path segment as a variable placeholder: ${name} / {name} / {name:regex}.
    // Returns [name, regex], or null if it is not a variable (including an empty "{}").
    static String[] parsePathVar(String seg) {
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

    /**
     * Fill {@code pathVarSegIndex} with each path variable's name→segment-index and return the
     * compiled route regex (with anchors). Call only for template paths (map is non-null).
     */
    static String buildRoutePattern(String fullPath, Map<String, Integer> pathVarSegIndex) {
        String[] segs = fullPath.split("/", -1);
        StringBuilder rb = new StringBuilder();
        for (int si = 1; si < segs.length; ++si) {
            String seg = segs[si];
            rb.append("/");
            String[] pv = parsePathVar(seg);
            if (pv != null) {
                pathVarSegIndex.put(pv[0], si);
                rb.append(pv[1] != null ? "(" + pv[1] + ")" : "([^/]+)");
            } else {
                rb.append(Pattern.quote(seg));
            }
        }
        return "^" + rb + "$";
    }

    // ── Bean instantiation / parameter resolution (beanContainer passed in) ──

    /** Pick the @Inject constructor if exactly one, else the first public constructor. */
    static Constructor<?> findConstructor(Constructor<?>[] constructors, BeanContainer beanContainer) {
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
     * Resolve method/constructor parameters supporting @Value, @Inject(name), or by-type lookup
     * from the container. Returns null if any @Inject dependency is not yet available.
     */
    static Object[] resolveParameters(Parameter[] params, String context,
            BeanContainer beanContainer, AnnotationResolver resolver) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; ++i) {
            Parameter param = params[i];
            Annotation valueAnn = beanContainer.findValueAnnotation(param);
            if (valueAnn != null) {
                ParamValueConverters.ensureConvertible(param.getType());
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
                return null; // deferrable, consistent with @Inject by-type resolution
            }
        }
        return args;
    }

    /** Instantiate via {@code ctor} (passing {@code args}) and inject the instance's @Value fields. */
    static Object instantiateAndInject(Class<?> clazz, Constructor<?> ctor,
            BeanContainer beanContainer, Object... args) {
        try {
            Object instance = ctor.newInstance(args);
            beanContainer.injectFields(instance, clazz, false);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate " + clazz.getName(), e);
        }
    }

    /** Invoke a @Bean method (resolving its parameters) and register the result; false if a dependency is missing. */
    static boolean tryProcessBeanMethod(Object bean, Method method, String beanName,
            BeanContainer beanContainer, AnnotationResolver resolver) {
        try {
            Object[] args = resolveParameters(method.getParameters(),
                    "@Bean method " + method.getName() + " on " + bean.getClass().getName(),
                    beanContainer, resolver);
            if (args == null) return false;
            Object result = method.invoke(bean, args);
            beanContainer.register(beanName.isEmpty() ? method.getName() : beanName,
                    new BeanDefinition(method.getReturnType(), result, true, false), true);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to process @Bean method " + method.getName()
                    + " on " + bean.getClass().getName(), e);
        }
    }
}
