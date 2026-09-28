package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bind one or more {@link InterceptorType#ENDPOINT ENDPOINT} interceptors to a controller
 * class or a single endpoint method.
 * <p>
 * Values are interceptor bean names declared by {@link Interceptor#value()}. A class-level
 * declaration applies to every endpoint of the class; a method-level declaration appends to it.
 * Interceptors run in {@link Interceptor#order()} order before the endpoint method is invoked,
 * and any interceptor returning {@code false} short-circuits the request.
 *
 * <pre>{@code
 * @RestController("/admin")
 * @WithInterceptor("admin")
 * public class AdminController {
 *
 *     @Endpoint("/users")
 *     @WithInterceptor("audit")
 *     public Object users() { ... }
 * }
 * }</pre>
 *
 * @author wangyc
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface WithInterceptor {

    /**
     * Interceptor bean names to apply.
     */
    String[] value() default {};
}
