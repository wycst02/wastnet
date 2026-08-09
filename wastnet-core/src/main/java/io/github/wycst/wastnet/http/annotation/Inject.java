package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or parameter for dependency injection.
 * <p>
 * By default, the field/parameter type is used to look up a bean from the container.
 * Specify {@link #value()} to look up by name (e.g. for multiple beans of the same type).
 *
 * @author wangyc
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {

    /** Bean name qualifier; empty means lookup by type. */
    String value() default "";
}
