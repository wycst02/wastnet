package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Holds information about a single endpoint route parsed from a method annotation.
 *
 * @author wangyc
 */
public class MethodRouteInfo {
    private final String path;
    private final HttpMethod[] httpMethods;
    private final Method method;
    private final Class<? extends Annotation> annotationType;
    private final ContentType responseType;
    private String[] interceptorNames;

    /**
     * @param path         endpoint path (e.g. "/users/{id}")
     * @param httpMethods  HTTP methods this route matches
     * @param method       the Java method implementing this endpoint
     */
    public MethodRouteInfo(String path, HttpMethod[] httpMethods, Method method) {
        this(path, httpMethods, method, null, null);
    }

    /**
     * @param path          endpoint path (e.g. "/users/{id}")
     * @param httpMethods   HTTP methods this route matches
     * @param method        the Java method implementing this endpoint
     * @param annotationType the annotation type that registered this route
     *                       (e.g. {@code Endpoint.class} or {@code Sse.class}); may be {@code null}
     */
    public MethodRouteInfo(String path, HttpMethod[] httpMethods, Method method, Class<? extends Annotation> annotationType) {
        this(path, httpMethods, method, annotationType, null);
    }

    /**
     * @param path          endpoint path (e.g. "/users/{id}")
     * @param httpMethods   HTTP methods this route matches
     * @param method        the Java method implementing this endpoint
     * @param annotationType the annotation type that registered this route
     *                       (e.g. {@code Endpoint.class} or {@code Sse.class}); may be {@code null}
     * @param responseType   the content type the endpoint produces;
     *                       {@code null} falls back to {@link ContentType#JSON}
     */
    public MethodRouteInfo(String path, HttpMethod[] httpMethods, Method method, Class<? extends Annotation> annotationType, ContentType responseType) {
        this.path = path;
        this.httpMethods = httpMethods;
        this.method = method;
        this.annotationType = annotationType;
        this.responseType = responseType;
    }

    /** @return the endpoint path */
    public String getPath() {
        return path;
    }

    /** @return the HTTP methods this route matches */
    public HttpMethod[] getHttpMethods() {
        return httpMethods;
    }

    /** @return the Java method implementing this endpoint */
    public Method getMethod() {
        return method;
    }

    /**
     * @return the annotation type that registered this route,
     *         or {@code null} if not tracked
     */
    public Class<? extends Annotation> getAnnotationType() {
        return annotationType;
    }

    /**
     * @return the content type the endpoint produces,
     *         or {@code null} if not set (defaults to {@link ContentType#JSON})
     */
    public ContentType getResponseType() {
        return responseType;
    }

    /**
     * @return endpoint interceptor bean names declared by {@link WithInterceptor},
     *         or {@code null} if none
     */
    public String[] getInterceptorNames() {
        return interceptorNames;
    }

    /** Set endpoint interceptor bean names declared by {@link WithInterceptor}. */
    public void setInterceptorNames(String[] interceptorNames) {
        this.interceptorNames = interceptorNames;
    }
}
