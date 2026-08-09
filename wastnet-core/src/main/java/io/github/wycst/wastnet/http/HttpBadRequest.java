package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.List;
import java.util.Map;

/**
 * Represents a bad HTTP request with specific error code.
 *
 * @since 2024-3-8
 * @author wangyc
 */
public class HttpBadRequest extends HttpDecodedRequest {

    public static final HttpBadRequest PROTOCOL_ERROR_REQUEST = new HttpBadRequest().markProtocolError();

    private HttpStatus status = HttpStatus.BAD_REQUEST;
    private boolean stream;
    private boolean chunked;
    private boolean protocolError;

    public HttpBadRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] bodyData, long contentLength, String contentType, ChannelContext ctx) {
        super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, bodyData, contentLength, contentType, ctx);
    }

    HttpBadRequest() {
    }

    HttpBadRequest markProtocolError() {
        this.protocolError = true;
        return this;
    }

    public boolean isProtocolError() {
        return protocolError;
    }

    HttpBadRequest status(HttpStatus status) {
        this.status = status;
        return this;
    }

    HttpBadRequest stream(boolean stream) {
        this.stream = stream;
        return this;
    }

    HttpBadRequest chunked(boolean chunked) {
        this.chunked = chunked;
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public boolean isCompleted() {
        return !stream;
    }

    public void complete() {
        if (stream) {
            HttpBodyInputStream bodyStream = chunked ? new HttpChunkedStream(bodyData, ctx) : new HttpBodyInputStream(contentLength, bodyData, ctx);
            bodyStream.complete();
        }
    }

    /**
     * Get the error message based on status.
     *
     * @return error message description
     */
    public String getErrorMessage() {
        return status.text;
    }

    /**
     * Get the HTTP status code.
     *
     * @return HTTP status code
     */
    public int getHttpStatusCode() {
        return status.code;
    }

    /**
     * Get the HttpStatus enum.
     *
     * @return HttpStatus
     */
    public HttpStatus getHttpStatus() {
        return status;
    }

    @Override
    public final boolean isBad() {
        return true;
    }

    @Override
    public String toString() {
        return "BadHttpRequest{status=" + status + ", code=" + status.code + ", message='" + status.text + "'}";
    }
}








