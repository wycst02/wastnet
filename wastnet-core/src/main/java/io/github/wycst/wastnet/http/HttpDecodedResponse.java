package io.github.wycst.wastnet.http;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a decoded HTTP/1.x response message.
 * <p>
 * Used by {@link HttpResponseDecoder} to hold the result of parsing
 * a response status line, headers, and body.
 * </p>
 *
 * @author wangyc
 */
public class HttpDecodedResponse implements HttpMessage {

    private final HttpVersion version;
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, Object> headers;
    private final byte[] body;
    private final long contentLength;
    private final String contentType;
    private final boolean stream;
    private final InputStream bodyStream;

    public HttpDecodedResponse(HttpVersion version, int statusCode, String reasonPhrase,
                               Map<String, Object> headers, byte[] body,
                               long contentLength, String contentType) {
        this(version, statusCode, reasonPhrase, headers, body, contentLength, contentType, false, null);
    }

    public HttpDecodedResponse(HttpVersion version, int statusCode, String reasonPhrase,
                               Map<String, Object> headers, byte[] body,
                               long contentLength, String contentType,
                               boolean stream, InputStream bodyStream) {
        this.version = version;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.headers = new LinkedHashMap<String, Object>(headers);
        this.body = body;
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.stream = stream;
        this.bodyStream = bodyStream;
    }

    public HttpVersion getVersion() {
        return version;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getReasonPhrase() {
        return reasonPhrase;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isStream() {
        return stream;
    }

    public InputStream getBodyStream() {
        return bodyStream;
    }

    @Override
    public boolean isHttpRequest() {
        return false;
    }

    @Override
    public boolean isUpgrade() {
        return false;
    }
}
