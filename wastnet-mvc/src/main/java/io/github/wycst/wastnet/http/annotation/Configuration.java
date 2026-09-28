package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a configuration source with {@link Bean @Bean} methods.
 * <p>
 * Only classes annotated with {@code @Configuration} are scanned for {@code @Bean} methods.
 * Regular {@link Component @Component} classes do not support {@code @Bean}.
 * <p>
 * A {@code @Configuration} class must declare a <b>no-arg constructor</b>: constructor injection is
 * unsupported, and a class with a parameterized constructor is silently skipped (together with all
 * of its {@code @Bean} methods) during scanning.
 *
 * @author wangyc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Configuration {
}
