package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a method as a Server-Sent Events endpoint within a {@link Controller @Controller} class.
 * <p>
 * The method must declare exactly one {@link io.github.wycst.wastnet.http.SseEmitter SseEmitter}
 * parameter; it may additionally bind other parameters (e.g. {@code @PathVariable},
 * {@code @RequestParam}, {@link io.github.wycst.wastnet.http.HttpRequest HttpRequest}) just like a
 * regular {@link Endpoint @Endpoint}.
 *
 * @author wangyc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sse {
    
    /** The SSE endpoint path. */
    String value();

    /**
     * Maximum duration in milliseconds before the SSE connection is force-closed.
     * Values {@code < 0} (the default) fall back to the global
     * {@code wastnet.http.sse-timeout-ms} option; {@code 0} means an immediate close.
     */
    long timeout() default -1;
}
