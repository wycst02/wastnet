package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an HTTP endpoint within a {@link Controller @Controller} class.
 * <p>
 * The annotated method becomes routable at the path given by {@link #value()},
 * combined with the controller's base path. Path template variables use the
 * {@code ${name}} or {@code {name}} syntax and can carry an inline regex, e.g.
 * {@code "/user/{id}"} or {@code "/user/{id:[0-9]+}"}.
 * <p>
 * A method may declare parameters annotated with {@code @PathParam},
 * {@code @RequestParam} or {@code @RequestBody}, or receive the raw
 * {@code HttpRequest} / {@code HttpResponse}. If its return value is
 * {@code @ResponseBody} (or the enclosing class is {@link RestController}),
 * the result is handed to the configured {@code HttpMessageConverter}.
 *
 * @author wangyc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Endpoint {

    /**
     * The endpoint path relative to the controller's base path,
     * e.g. {@code "/list"} or {@code "/user/{id}"}.
     *
     * @return the endpoint path
     */
    String value();

    /**
     * Restricts the HTTP methods this endpoint accepts.
     * <p>
     * When empty, the endpoint matches any HTTP method; otherwise a request whose
     * method is not listed is rejected. Only used as an allow-list, not a deny-list.
     *
     * @return the allowed HTTP methods, or an empty array to allow all
     */
    HttpMethod[] allowMethods() default {};

    /**
     * Declares the content type the endpoint produces, which is resolved into a
     * {@code ConverterConfig} at scan time so the message converter can decide how
     * to serialize the return value. Defaults to {@link ContentType#JSON}.
     *
     * @return the produced content type
     */
    ContentType responseType() default ContentType.JSON;
}
