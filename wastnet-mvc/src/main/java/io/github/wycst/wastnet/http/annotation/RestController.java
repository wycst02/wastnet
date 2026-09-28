package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as a controller whose {@code @Endpoint} methods return values
 * are, by default, serialized to the HTTP response body. This is equivalent to
 * annotating every method with {@link ResponseBody @ResponseBody}.
 * <p>
 * Analogue of Spring's {@code @RestController}. The optional value specifies a
 * base path prefix for all endpoints in this class.
 *
 * @author wangyc
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RestController {
    String value() default "";
}
