package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a class extending {@link io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource WebSocketResource}
 * as a WebSocket endpoint.
 *
 * @author wangyc
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WebSocket {
    /** The WebSocket endpoint path. */
    String value();
}
