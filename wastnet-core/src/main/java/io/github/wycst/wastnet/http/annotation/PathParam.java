package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bind a path template variable (e.g. {@code ${id}} in the route path) to a
 * controller method parameter.
 *
 * <p>Example:
 * <pre>{@code
 * @Endpoint("/user/${id}")
 * public void get(@PathParam("id") long id) { ... }
 * }</pre>
 *
 * @author wangyc
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathParam {
    String value();
}
