package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.extension.HttpServerInterceptor;
import io.github.wycst.wastnet.http.extension.HttpServerObserver;
import io.github.wycst.wastnet.http.upgrade.DefaultUpgradeHandler;
import io.github.wycst.wastnet.http.upgrade.UpgradeHandler;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.util.Objects;

/**
 * HTTP server handler
 *
 * @since 2024-1-19
 * @author wangyc
 */
public final class HttpServerChannelHandler implements ChannelHandler<HttpMessage>, ClearableHandler {

    static final Log log = LogFactory.getLog(HttpServerChannelHandler.class);

    private ChannelHandler<?> childHandler;
    private HttpRequestHandler requestHandler = (request, response) -> {
        String body = "Welcome to wastnet v" + HTTPServer.VERSION;
        response.status(HttpStatus.OK)
                .contentType(HttpHeaderValues.TEXT_PLAIN_UTF8)
                .body(body.getBytes(Utils.UTF_8));
    };
    private UpgradeHandler upgradeHandler = new DefaultUpgradeHandler();
    private HttpExceptionHandler exceptionHandler;
    private boolean printStackTraceError = true;
    private HttpServerInterceptor serverInterceptor;
    private HttpServerObserver serverObserver;
    private HttpRequestLifecycleDelegate delegate;

    public void setRequestHandler(HttpRequestHandler requestHandler) {
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler must not be null");
    }

    /**
     * Clear all sub-handlers on server stop.
     */
    @Override
    public void clear() {
        if (upgradeHandler instanceof ClearableHandler) ((ClearableHandler) upgradeHandler).clear();
        if (requestHandler instanceof ClearableHandler) ((ClearableHandler) requestHandler).clear();
        if (childHandler instanceof ClearableHandler) ((ClearableHandler) childHandler).clear();
        HttpRequestLifecycleDelegate delegate = this.delegate;
        if (delegate != null) {
            delegate.clear();
        }
    }

    public void setUpgradeHandler(UpgradeHandler upgradeHandler) {
        this.upgradeHandler = Objects.requireNonNull(upgradeHandler, "upgradeHandler must not be null");
    }

    // Need to check for circular references
    public void setChildHandler(ChannelHandler<?> childHandler) {
        if (childHandler != this) {
            this.childHandler = childHandler;
        }
    }

    public void setPrintStackTraceError(boolean printStackTraceError) {
        this.printStackTraceError = printStackTraceError;
    }

    /**
     * Set custom exception handler
     *
     * @param exceptionHandler Exception handler
     */
    public void setExceptionHandler(HttpExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Set the active server interceptor (pre-handle cut-in point for auth/CORS/
     * rate-limit short-circuit, etc.). Pass {@code null} to disable.
     *
     * @param serverInterceptor the server interceptor, or {@code null} to disable
     */
    public void setInterceptor(HttpServerInterceptor serverInterceptor) {
        this.serverInterceptor = serverInterceptor;
    }

    /**
     * Set a passive server observer for request/connection lifecycle events
     * (metrics, tracing, audit logging, etc.). Pass {@code null} to disable.
     *
     * @param serverObserver the server observer, or {@code null} to disable
     */
    public void setObserver(HttpServerObserver serverObserver) {
        this.serverObserver = serverObserver;
    }

    /**
     * Prepare the handler before server starts.
     * <p>
     * If the requestHandler is also an UpgradeHandler (e.g., HttpRouterHandler extends DefaultUpgradeHandler),
     * use it directly as the upgrade handler. If any interceptor/observer is registered,
     * assemble the internal delegate once (left {@code null} when neither is set, for zero overhead).
     */
    public void prepare() {
        requestHandler.prepare();
        if (requestHandler instanceof UpgradeHandler) {
            this.upgradeHandler = (UpgradeHandler) requestHandler;
        }
        if (serverInterceptor != null || serverObserver != null) {
            this.delegate = new HttpRequestLifecycleDelegate(serverInterceptor, serverObserver);
        }
    }

    @Override
    public void onConnected(ChannelContext ctx) throws IOException {
        HttpRequestLifecycleDelegate delegate = this.delegate;
        if (delegate != null) {
            delegate.onConnectionOpen(ctx);
        }
        if (childHandler != null) {
            childHandler.onConnected(ctx);
        }
    }

    @Override
    public void onHandle(ChannelContext ctx, HttpMessage message) throws IOException {
        try {
            executeHandle(ctx, message);
        } catch (Throwable throwable) {
            if (printStackTraceError) {
                log.error("Exception in onHandle:", throwable);
            }
        }
    }

    private void executeHandle(ChannelContext ctx, HttpMessage target) throws Throwable {
        if (target.isHttpRequest()) {
            HttpRequestLifecycleDelegate delegate = this.delegate;
            HttpRequest request = (HttpRequest) target;
            HttpResponse response = request.getResponse();
            long start = 0L;
            Throwable error = null;
            boolean continued = true;
            if (delegate != null) {
                start = System.nanoTime();
                // passive notify + active cut-in: interceptor may short-circuit by writing the response itself
                continued = delegate.onRequestStart(request, response, ctx);
            }
            try {
                if (!continued) {
                    // interceptor has written the response; flush it to the channel, record
                    // completion, then skip the business handler
                    complete(request, response);
                    return;
                }
                if (!request.isBad()) {
                    // handshake websocket or h2c upgrade
                    if (upgradeHandler.upgrade(request, ctx)) { // upgrade success
                        return;
                    } // normal http request
                    try { // handle application
                        requestHandler.handle(request, response);
                    } catch (Throwable throwable) { // Handle exception thrown by application processor
                        error = throwable;
                        handleApplicationException(request, response, throwable);
                    } finally {
                        complete(request, response);
                    }
                } else {
                    HttpInternalRequest internalRequest = (HttpInternalRequest) request;
                    if (internalRequest.isProtocolError()) {
                        ctx.close();
                    } else {
                        // if parse fail then ack a bad response
                        handleBadRequest(internalRequest);
                        request.complete();
                    }
                }
            } finally {
                if (delegate != null) {
                    delegate.onRequestComplete(request, response,
                            System.nanoTime() - start, error);
                }
            }
        } else if (target.isUpgrade()) { // -> instanceof HttpUpgradeMessage
            upgradeHandler.handle(ctx, (HttpUpgradeMessage) target);
        } // ignore other http message
    }

    /**
     * Flush the response to the channel and clear unread request data.
     *
     * @param request  HTTP request
     * @param response HTTP response
     */
    private void complete(HttpRequest request, HttpResponse response) throws IOException {
        // Framework calls complete() to finish response (HttpCompleteResponse specific method)
        ((HttpInternalResponse) response).complete();
        // clear unread data
        request.complete();
    }

    @Override
    public void onClosed(ChannelContext ctx) throws IOException {
        HttpRequestLifecycleDelegate delegate = this.delegate;
        if (delegate != null) {
            delegate.onConnectionClose(ctx);
        }
        upgradeHandler.onClosed(ctx);
        if (childHandler != null) {
            childHandler.onClosed(ctx);
        }
    }

    public UpgradeHandler getUpgradeHandler() {
        return upgradeHandler;
    }

    /**
     * Handle exceptions thrown by application processor
     *
     * @param request   HTTP request
     * @param response  HTTP response
     * @param throwable Thrown exception
     */
    private void handleApplicationException(HttpRequest request, HttpResponse response, Throwable throwable) {
        // Capture into a local variable so a concurrent setExceptionHandler(null)
        // cannot cause a NullPointerException between the null-check and the call.
        HttpExceptionHandler exHandler = this.exceptionHandler;
        // If no exception handler is set, return simple error response directly
        if (exHandler == null) {
            // Reuse the failure handling logic of exception handler
            handleInternalException(response, throwable);
            return;
        }
        try {
            // Handle exception using configured exception handler
            exHandler.handleException(request, response, throwable);
        } catch (Throwable handlerException) {
            // the handler itself failed: report the original application error too, otherwise
            // only the handler's own failure surfaces and the root cause is lost
            if (printStackTraceError) {
                log.error("Original application exception (the exception handler failed afterwards):", throwable);
            }
            // Safe handling when exception handler itself fails
            // Check if response has been partially modified by exception handler
            handleExceptionHandlerFailure(response, handlerException);
        }
    }

    /**
     * Handle failure when exception handler execution fails
     *
     * @param response         HTTP response
     * @param handlerException Exception thrown by exception handler
     */
    private void handleExceptionHandlerFailure(HttpResponse response, Throwable handlerException) {
        // Check if response has been partially modified by exception handler
        boolean responseModified = response.getStatus() != HttpStatus.OK ||
                response.getContentLength() > 0;
        if (!responseModified) {
            // If response was not modified, use internal exception handling
            handleInternalException(response, handlerException);
        }
        // If response was modified, do nothing - let the partially built response be sent
    }

    /**
     * Handle internal exceptions with basic error response
     *
     * @param response         HTTP response
     * @param handlerException Exception thrown by exception handler
     */
    private void handleInternalException(HttpResponse response, Throwable handlerException) {
        // Log the error
        if (printStackTraceError) {
            log.error("Exception handler failed: {}", handlerException, handlerException.getMessage());
        }

        // Return basic 500 error response
        try {
            response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(HttpHeaderValues.TEXT_PLAIN_UTF8)
                    .body("500 Internal Server Error".getBytes());
        } catch (Throwable ignored) {
            // If even the most basic response cannot be built, give up
            log.error("Failed to send internal server error response:", handlerException);
        }
    }

    /**
     * Handle bad HTTP request with appropriate error response
     *
     * @param badRequest Bad HTTP request
     */
    private void handleBadRequest(HttpInternalRequest badRequest) {
        HttpResponse response = badRequest.getResponse();
        try {
            response.status(badRequest.getHttpStatus()).contentType(HttpHeaderValues.TEXT_PLAIN_UTF8)
                    .body(badRequest.getErrorMessage().getBytes()).commit();
        } catch (Throwable throwable) {
            if (printStackTraceError) {
                log.error("Failed to send bad request response:", throwable);
            }
        }
    }
}
