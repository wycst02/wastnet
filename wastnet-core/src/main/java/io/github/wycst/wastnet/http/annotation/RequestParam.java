package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bind a query (or form) parameter to a controller method parameter.
 *
 * <p>Example:
 * <pre>{@code
 * @Endpoint("/search")
 * public void search(@RequestParam("q") String q,
 *                    @RequestParam(value = "page", required = false, defaultValue = "1") int page) { ... }
 * }</pre>
 *
 * @author wangyc
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestParam {
    String value();
    boolean required() default true;
    String defaultValue() default "";
}
