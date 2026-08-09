package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a method produces a bean to be managed by the framework's
 * {@link io.github.wycst.wastnet.http.annotation.BeanContainer BeanContainer}.
 * <p>
 * Typically used on methods within classes annotated with {@link Component @Component}.
 * The bean name defaults to the method name; a custom name can be specified
 * via {@link #value()}.
 * <p>
 * If the method has parameters, each parameter is resolved from the container
 * by type (constructor-injection style).
 *
 * @author wangyc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {

    /**
     * Optional explicit bean name. Defaults to the method name if empty.
     */
    String value() default "";
}
