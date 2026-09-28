package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bind a request header to a controller method parameter.
 * <p>
 * Supported parameter types:
 * <ul>
 *     <li>{@code String} — the single header value (case-insensitive name lookup)</li>
 *     <li>any built-in / registered type (e.g. {@code int}, {@code enum}) — value is converted</li>
 *     <li>{@code String[]} / {@code List<String>} — all values of a multi-valued header</li>
 * </ul>
 *
 * @author wangyc
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestHeader {

    /** Header name (case-insensitive). */
    String value();

    /** Whether the header is required. When false and absent, the parameter is bound to null (or defaultValue). */
    boolean required() default true;

    /** Value used when the header is absent and not required. Empty string means none. */
    String defaultValue() default "";
}
