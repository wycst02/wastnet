package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;

/**
 * HTTP exception handler base class
 * Used to uniformly handle exceptions thrown by application processors
 * Subclasses need to override handleException method to implement specific exception handling logic
 *
 * @since 2026-2-24
 * @author wangyc
 */
public interface HttpExceptionHandler {

    /**
     * Handle exceptions thrown by application processor
     * Implementation classes must provide specific exception handling logic
     *
     * @param request   HTTP request
     * @param response  HTTP response
     * @param throwable Thrown exception
     */
    void handleException(HttpRequest request, HttpResponse response, Throwable throwable);
}