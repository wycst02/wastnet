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
 *
 * @author wangyc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Configuration {
}
