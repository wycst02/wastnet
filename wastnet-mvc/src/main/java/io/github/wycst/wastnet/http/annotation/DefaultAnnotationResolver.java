package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.SseEmitter;
import io.github.wycst.wastnet.http.handler.RouterInterceptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation that resolves the framework's built-in annotations:
 * {@link Controller @Controller}, {@link Endpoint @Endpoint},
 * {@link Sse @Sse}, {@link WebSocket @WebSocket},
 * {@link Component @Component}.
 *
 * @author wangyc
 */
final class DefaultAnnotationResolver implements AnnotationResolver {

    @Override
    public boolean accept(Class<?> clazz) {
        return isComponent(clazz) || isController(clazz) || isWebSocketEndpoint(clazz)
                || isConfiguration(clazz);
    }

    @Override
    public boolean isController(Class<?> clazz) {
        return clazz.isAnnotationPresent(Controller.class)
                || clazz.isAnnotationPresent(RestController.class);
    }

    @Override
    public String resolveControllerPath(Class<?> clazz) {
        Controller ann = clazz.getAnnotation(Controller.class);
        if (ann != null) return ann.value();
        RestController rc = clazz.getAnnotation(RestController.class);
        return rc != null ? rc.value() : "";
    }

    @Override
    public List<MethodRouteInfo> resolveEndpointRoutes(Class<?> clazz) {
        List<MethodRouteInfo> routes = new ArrayList<>();
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            Endpoint ann = method.getAnnotation(Endpoint.class);
            if (ann != null) {
                String path = ann.value().trim();
                if (!path.isEmpty()) {
                    MethodRouteInfo routeInfo = new MethodRouteInfo(path, ann.allowMethods(), method, Endpoint.class, ann.responseType());
                    routeInfo.setInterceptorNames(resolveInterceptorNames(clazz, method));
                    routes.add(routeInfo);
                }
            }
        }
        return routes;
    }

    /**
     * Merge class-level and method-level {@link WithInterceptor} names, class-level first, deduplicated.
     */
    private String[] resolveInterceptorNames(Class<?> clazz, Method method) {
        WithInterceptor classAnn = clazz.getAnnotation(WithInterceptor.class);
        WithInterceptor methodAnn = method.getAnnotation(WithInterceptor.class);
        if (classAnn == null && methodAnn == null) return null;
        List<String> names = new ArrayList<>();
        if (classAnn != null) {
            for (String name : classAnn.value()) {
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        if (methodAnn != null) {
            for (String name : methodAnn.value()) {
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        return names.isEmpty() ? null : names.toArray(new String[0]);
    }

    @Override
    public List<MethodRouteInfo> resolveSseEndpoints(Class<?> clazz) {
        List<MethodRouteInfo> endpoints = new ArrayList<>();
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            Sse ann = method.getAnnotation(Sse.class);
            if (ann == null) continue;
            String path = ann.value().trim();
            if (path.isEmpty()) continue;
            Class<?>[] paramTypes = method.getParameterTypes();
            int sseCount = 0;
            for (Class<?> pt : paramTypes) {
                if (pt == SseEmitter.class) ++sseCount;
            }
            if (sseCount != 1) {
                throw new IllegalArgumentException(
                        "@Sse method " + clazz.getSimpleName() + "." + method.getName()
                                + " must have exactly one parameter of type SseEmitter");
            }
            if (method.getReturnType() != void.class) {
                throw new IllegalArgumentException(
                        "@Sse method " + clazz.getSimpleName() + "." + method.getName()
                                + " must return void");
            }
            MethodRouteInfo endpointInfo = new MethodRouteInfo(path, new HttpMethod[0], method, Sse.class);
            endpointInfo.setInterceptorNames(resolveInterceptorNames(clazz, method));
            endpoints.add(endpointInfo);
        }
        return endpoints;
    }

    @Override
    public boolean isWebSocketEndpoint(Class<?> clazz) {
        return clazz.isAnnotationPresent(WebSocket.class);
    }

    @Override
    public String resolveWebSocketPath(Class<?> clazz) {
        WebSocket ann = clazz.getAnnotation(WebSocket.class);
        return ann != null && !ann.value().isEmpty() ? ann.value() : "";
    }

    @Override
    public boolean isComponent(Class<?> clazz) {
        // @Interceptor only counts as a component when it honours the RouterInterceptor contract
        return clazz.isAnnotationPresent(Component.class)
                || (clazz.isAnnotationPresent(Interceptor.class) && RouterInterceptor.class.isAssignableFrom(clazz));
    }

    @Override
    public boolean isConfiguration(Class<?> clazz) {
        return clazz.isAnnotationPresent(Configuration.class);
    }

    @Override
    public String resolveComponentName(Class<?> clazz) {
        Component ann = clazz.getAnnotation(Component.class);
        if (ann != null && !ann.value().isEmpty()) {
            return ann.value();
        }
        Interceptor annInterceptor = clazz.getAnnotation(Interceptor.class);
        if (annInterceptor != null && !annInterceptor.value().isEmpty()) {
            return annInterceptor.value();
        }
        return AnnotationResolver.resolveBeanName(clazz);
    }

    // ── Value / Inject ──

    @Override
    public String resolveValueExpression(Annotation annotation) {
        if (annotation instanceof Value) {
            return ((Value) annotation).value();
        }
        return annotationValue(annotation);
    }

    @Override
    public String resolveInjectName(Annotation annotation) {
        if (annotation instanceof Inject) {
            return ((Inject) annotation).value();
        }
        return annotationValue(annotation);
    }

    @Override
    public String resolveBeanName(Annotation annotation) {
        if (annotation instanceof Bean) {
            return ((Bean) annotation).value();
        }
        return annotationValue(annotation);
    }

    // Read value() via reflection for third-party annotations bridged through
    // valueBy/injectBy/beanBy (no dedicated framework type available).
    private static String annotationValue(Annotation annotation) {
        try {
            Method valueMethod = annotation.annotationType().getMethod("value");
            return valueMethod.invoke(annotation).toString();
        } catch (Exception e) {
            return "";
        }
    }
}
