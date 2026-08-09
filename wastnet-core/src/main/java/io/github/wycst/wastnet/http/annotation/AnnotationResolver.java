package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Pluggable strategy for resolving annotations to route and component metadata,
 * also acting as a scanner filter to avoid loading irrelevant classes.
 * <p>
 * The default implementation resolves framework annotations ({@code @Controller},
 * {@code @Endpoint}, {@code @Sse}, {@code @WebSocket}, {@code @Component}).
 * Users may provide a custom implementation to bridge third-party annotations
 * (e.g. Spring Boot's {@code @RestController}, {@code @RequestMapping}, {@code @Service}).
 *
 * @author wangyc
 */
public interface AnnotationResolver extends AnnotationFilter {

    /**
     * Returns {@code true} if the class is a request-handling controller.
     */
    boolean isController(Class<?> clazz);

    /**
     * Extract the base path from a controller class annotation.
     */
    String resolveControllerPath(Class<?> clazz);

    /**
     * Extract all endpoint route definitions from a controller class.
     */
    List<MethodRouteInfo> resolveEndpointRoutes(Class<?> clazz);

    /**
     * Extract all SSE endpoint definitions from a controller class.
     */
    List<MethodRouteInfo> resolveSseEndpoints(Class<?> clazz);

    /**
     * Returns {@code true} if the class is a WebSocket endpoint.
     */
    boolean isWebSocketEndpoint(Class<?> clazz);

    /**
     * Extract the WebSocket endpoint path from a class annotation.
     */
    String resolveWebSocketPath(Class<?> clazz);

    /**
     * Returns {@code true} if the class is a managed component.
     */
    boolean isComponent(Class<?> clazz);

    /**
     * Returns {@code true} if the class is a {@code @Configuration} class.
     */
    boolean isConfiguration(Class<?> clazz);

    /**
     * Extract the component/bean name from a component class annotation.
     */
    String resolveComponentName(Class<?> clazz);

    // ── Value / Inject resolution (annotation level) ──

    /**
     * Extract the value expression from a value annotation instance (e.g. {@code @Value("${key}")}).
     *
     * @param annotation the annotation instance on a field or parameter
     * @return the expression string, or {@code null} if this resolver does not handle the annotation
     */
    String resolveValueExpression(Annotation annotation);

    /**
     * Extract the bean name from an injection annotation instance (e.g. {@code @Inject("name")}).
     *
     * @param annotation the annotation instance on a field or parameter
     * @return the bean name if explicitly specified, an empty string for by-type lookup,
     *         or {@code null} if this resolver does not handle the annotation
     */
    String resolveInjectName(Annotation annotation);

    /**
     * Extract the bean name from a {@code @Bean} annotation instance.
     *
     * @param annotation the annotation instance on a method
     * @return the explicit bean name, or an empty string to use the method name,
     *         or {@code null} if this resolver does not handle the annotation
     */
    String resolveBeanName(Annotation annotation);
}
