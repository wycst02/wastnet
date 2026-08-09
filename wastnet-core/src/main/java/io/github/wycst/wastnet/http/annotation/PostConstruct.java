package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method to be invoked after the bean is instantiated
 * and all dependencies have been injected.
 * <p>
 * Analogue of {@code javax.annotation.PostConstruct} but without
 * requiring the {@code javax.annotation-api} dependency.
 *
 * @author wangyc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostConstruct {
}
