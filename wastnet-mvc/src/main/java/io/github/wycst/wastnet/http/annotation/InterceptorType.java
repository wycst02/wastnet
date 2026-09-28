package io.github.wycst.wastnet.http.annotation;

/**
 * Scope of a route-level interceptor declared by {@link Interceptor @Interceptor}.
 *
 * @author wangyc
 */
public enum InterceptorType {

    /**
     * Runs unconditionally for every request before route dispatch.
     */
    PRE_ROUTE,

    /**
     * Runs only on endpoints that reference it via {@link WithInterceptor @WithInterceptor}.
     */
    ENDPOINT
}
