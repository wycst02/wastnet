package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.*;

/**
 * Marks a {@link BeanRegistrationHandler} implementation and binds it to the
 * {@code @EnableXxx} switch that activates it (the value). A handler is only
 * invoked when its bound annotation is present in the
 * {@link AnnotationRouterHandler#enables} white-list.
 *
 * @author wangyc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Registration {
    /** The {@code @EnableXxx} annotation that activates this registrar. */
    Class<? extends Annotation> value();

    /** Load order of this registrar. Lower values run first; defaults to highest (run last). */
    int order() default Integer.MAX_VALUE;
}
