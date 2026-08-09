package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a configuration value into a field.
 * <p>
 * The value is resolved from the scanner's configuration map.
 * Supports placeholder syntax: {@code ${key:defaultValue}}.
 *
 * @author wangyc
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Value {
    /** The configuration key, optionally with a default: {@code ${key:default}}. */
    String value();
}
