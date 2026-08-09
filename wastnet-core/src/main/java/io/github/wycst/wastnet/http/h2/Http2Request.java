/*
 * Copyright 2026, wangyunchao.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpInternalRequest;
import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.InputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP/2 request that delegates header, body and path access to the underlying
 * {@link Http2ServerStream}.
 * <p>
 * Each instance corresponds to a single HTTP/2 stream. The request body is
 * streamable ({@link #bodyStream()}); the full payload can be buffered via
 * {@link #getBodyData()} when content-length allows. Error status can be set
 * when request parsing fails, making this a bad request ({@link #isBad()}).
 *
 * @author wangyc
 */
public class Http2Request extends HttpInternalRequest {

    private final Http2ServerStream stream;
    private HttpStatus errorStatus;

    public Http2Request(Http2ServerStream stream) {
        super(stream.ctx);
        this.stream = stream;
        this.response = new Http2Response(this, stream, ctx);
    }

    @Override
    public HttpVersion getHttpVersion() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public String getHost() {
        return stream.authority;
    }

    @Override
    public InputStream bodyStream() {
        return stream.getInputStream();
    }

    @Override
    public String getScheme() {
        return stream.scheme;
    }

    @Override
    public String getUri() {
        return stream.path;
    }

    @Override
    public String getRequestUri() {
        return stream.requestUri;
    }

    @Override
    public boolean isCompleted() {
        return stream.endStream;
    }

    @Override
    public void complete() {
        super.complete();
        if (!isCompleted()) {
            try {
                stream.completeRequest();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public long getContentLength() {
        return stream.getContentLength();
    }

    @Override
    public String getContentType() {
        return stream.contentType;
    }

    @Override
    public HttpMethod getMethod() {
        return stream.method;
    }

    @Override
    public byte[] getBodyData() {
        return stream.getBodyData();
    }

    /**
     * Get the trailer fields (RFC 7540 §8.1) received after the request body.
     */
    public Map<String, Object> getTrailers() {
        return stream.getTrailers();
    }

    /**
     * Register a callback fired when the trailer block is fully received (streaming mode only).
     * <p>
     * The callback is invoked on the reader thread at the moment the trailer header block is
     * decoded — at the same time the request body stream reaches end-of-stream (read() returns
     * -1). In non-streaming mode the listener cannot be registered before dispatch, so use
     * {@link #getTrailers()} instead.
     */
    public void setTrailersListener(TrailersListener listener) {
        stream.setTrailersListener(listener);
    }

    @Override
    public Set<String> getHeaderNames() {
        return stream.headers.keySet();
    }

    @Override
    public String getHeader(String name) {
        Object value = stream.headers.get(name.toLowerCase());
        if (value == null) return null;
        return value.getClass() == String.class ? (String) value : ((List<String>) value).get(0);
    }

    /**
     * Set header
     *
     * @param key   the header key
     * @param value the header value
     */
    public void setHeader(String key, Serializable value) {
        stream.headers.put(String.valueOf(key).toLowerCase(), String.valueOf(value));
    }

    /**
     * Remove header
     *
     * @param key the header key
     */
    @Override
    public void removeHeader(String key) {
        stream.headers.remove(String.valueOf(key).toLowerCase());
    }

    @Override
    public boolean containsHeader(String name) {
        return stream.headers.containsKey(name.toLowerCase());
    }

    @Override
    public Object getRawHeader(String name) {
        return stream.headers.get(name.toLowerCase());
    }

    @Override
    public String getUriParameter(String name) {
        List<String> values = stream.parameters.get(name);
        return values != null ? values.get(0) : null;
    }

    @Override
    protected List<String> getUriParameterValues(String name) {
        return stream.parameters.get(name);
    }

    @Override
    protected Set<String> getUriParameterNames() {
        return stream.parameters.keySet();
    }

    @Override
    public String getQueryString() {
        String path = stream.path;
        if (path == null) return null;
        int idx = path.indexOf('?');
        return idx > -1 ? path.substring(idx + 1) : null;
    }

    @Override
    public StringBuffer getRequestURL() {
        String path = stream.path;
        if (path == null) return new StringBuffer();
        int qIdx = path.indexOf('?');
        String pathOnly = qIdx > -1 ? path.substring(0, qIdx) : path;
        return new StringBuffer(getScheme() + "://" + getHeader(":authority") + pathOnly);
    }

    /**
     * Set the error status, making this a bad request.
     * Used by {@link Http2ServerStream} when request parsing fails.
     */
    Http2Request errorStatus(HttpStatus errorStatus) {
        this.errorStatus = errorStatus;
        return this;
    }

    @Override
    public boolean isBad() {
        return errorStatus != null;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return errorStatus;
    }

    @Override
    public String getErrorMessage() {
        return errorStatus.text;
    }

    @Override
    public boolean isStream() {
        return stream.needStreaming;
    }

    @Override
    public void setRewriteUri(String newUri) {
        if (newUri == null) return;
        newUri = Utils.encodeUriPath(newUri);
        String queryString = getQueryString();
        if (queryString != null) {
            int index = newUri.lastIndexOf('?');
            if (index == -1) {
                newUri += "?" + queryString;
            } else {
                newUri = index == newUri.length() - 1 ? newUri + queryString : newUri + "&" + queryString;
            }
        }
        stream.path = newUri;
        stream.requestUri = newUri.contains("?") ? newUri.substring(0, newUri.indexOf('?')) : newUri;
    }

    /**
     * Get the HTTP/2 stream ID for this request.
     *
     * @return stream identifier
     */
    public int streamId() {
        return stream.streamId;
    }

    /**
     * Get the underlying stream context.
     *
     * @return stream context
     */
    public Http2ServerStream stream() {
        return stream;
    }

    @Override
    public void delegate(ChannelContext targetCtx) throws Throwable {
    }
}
