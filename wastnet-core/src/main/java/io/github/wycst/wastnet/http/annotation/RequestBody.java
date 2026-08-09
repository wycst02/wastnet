package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates a method parameter should be deserialized from the HTTP request body.
 * <p>
 * Used together with {@link Endpoint @Endpoint}.
 * The framework will call {@code HttpMessageConverter.read()} to deserialize the body.
 *
 * @author wangyc
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestBody {
}
