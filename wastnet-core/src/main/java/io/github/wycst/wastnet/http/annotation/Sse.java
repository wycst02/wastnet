package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a method as a Server-Sent Events endpoint within a {@link Controller @Controller} class.
 * <p>
 * The method must accept a single {@link io.github.wycst.wastnet.http.SseEmitter SseEmitter} parameter.
 *
 * @author wangyc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sse {
    /** The SSE endpoint path. */
    String value();
}
