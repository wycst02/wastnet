package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a class as a route-level interceptor.
 * <p>
 * An {@code @Interceptor} class must implement
 * {@link io.github.wycst.wastnet.http.handler.RouterInterceptor RouterInterceptor}. It is registered
 * as a managed component (eligible for constructor injection) and ordered by {@link #order()}.
 * Any interceptor returning {@code false} short-circuits the request.
 * <p>
 * {@link InterceptorType#PRE_ROUTE PRE_ROUTE} (default) interceptors are appended to the parent
 * {@code HttpRouterHandler} chain and run for every request before route dispatch;
 * {@link InterceptorType#ENDPOINT ENDPOINT} interceptors run only on endpoints that reference them
 * via {@link WithInterceptor @WithInterceptor}.
 *
 * @author wangyc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Interceptor {
    /**
     * Optional component name; defaults to a decapitalized simple class name.
     * This name is also how {@link WithInterceptor @WithInterceptor} references the interceptor.
     */
    String value() default "";

    /**
     * Execution order within the interceptor chain; smaller values run first.
     */
    int order() default 0;

    /**
     * Interceptor scope; defaults to {@link InterceptorType#PRE_ROUTE}.
     */
    InterceptorType type() default InterceptorType.PRE_ROUTE;

    /**
     * When true, this interceptor is disabled and will not execute, even if
     * referenced by {@link WithInterceptor @WithInterceptor} or registered globally.
     * Default is false (enabled).
     */
    boolean disabled() default false;
}
