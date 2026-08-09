package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * default request
 *
 * @since 2024-1-23
 * @author wangyc
 */
public class HttpDefaultRequest extends HttpDecodedRequest {

    public HttpDefaultRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] bodyData, long contentLength, String contentType, ChannelContext ctx) {
        super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, bodyData, contentLength, contentType, ctx);
    }

    public HttpDefaultRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] bodyData, long contentLength, String contentType) {
        this(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, bodyData, contentLength, contentType, null);
    }

    @Override
    public InputStream bodyStream() {
        return new ByteArrayInputStream(bodyData);
    }

    @Override
    public final boolean isCompleted() {
        return true;
    }
}
