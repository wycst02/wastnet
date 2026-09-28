package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.*;

/**
 * Mark a class as a request handler (controller).
 * <p>
 * The optional value specifies a base path prefix for all endpoints in this class.
 * <p>
 * This annotation is {@link Inherited @Inherited}, so subclasses of a
 * {@code @Controller}-annotated class will also be recognized as controllers.
 *
 * @author wangyc
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Controller {
    String value() default "";
}
