package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

/**
 * SPI for rendering a non-{@code @ResponseBody} controller return value to the response
 * (view names, {@code ModelAndView}, {@code View}, etc.). Registered via
 * {@link AnnotationRouterHandler#addViewResolver}; the first resolver whose {@link #supports}
 * matches is used.
 *
 * @author wangyc
 */
public interface ViewResolver {

    /**
     * Cheap, side-effect-free predicate: can this resolver handle a value of the given type?
     * Called per request with the runtime class of the return value. Match super-types via
     * {@code isAssignableFrom} (e.g. {@code View.class.isAssignableFrom(returnType)}) so a base-type
     * resolver also accepts its subclasses.
     *
     * @param returnType runtime class of the controller's return value
     * @return {@code true} if {@link #render} should be used
     */
    boolean supports(Class<?> returnType);

    /**
     * Render the return value to the response. Only called after {@link #supports} returned
     * {@code true}, so do not partially mutate the response for a value that is not yours.
     *
     * @param returnValue the controller's return value
     * @param request     the HTTP request (locale, request attributes, context path, session)
     * @param response    the HTTP response to write to
     * @throws Exception if rendering or I/O fails
     */
    void render(Object returnValue, HttpRequest request, HttpResponse response) throws Exception;
}
