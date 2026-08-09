package io.github.wycst.wastnet.http.extension;

import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * Active request interception SPI (the "cut-in" point).
 * <p>
 * Invoked before the business request handler. Return {@code true} to proceed to the
 * business handler, or {@code false} if the response has already been written
 * and the business handler should be skipped (e.g. auth rejection, CORS
 * preflight, rate limiting).
 * <p>
 * All callbacks run on the I/O worker thread and must be non-blocking and cheap.
 * Implementations must NOT block (e.g. synchronous DB calls); dispatch to a
 * business executor if blocking work is required.
 *
 * @author wangyc
 */
public interface HttpServerInterceptor {

    /**
     * Active cut-in invoked before the business request handler.
     *
     * @param request  the HTTP request
     * @param response the HTTP response (may be written to and committed here)
     * @param ctx      the channel context
     * @return {@code true} to proceed to the business handler, or {@code false}
     *         if the response has already been written and the business handler
     *         should be skipped
     * @throws Exception if the interceptor itself fails; the framework's exception
     *                   handling will take over
     */
    boolean beforeHandle(HttpRequest request, HttpResponse response, ChannelContext ctx) throws Exception;
}
