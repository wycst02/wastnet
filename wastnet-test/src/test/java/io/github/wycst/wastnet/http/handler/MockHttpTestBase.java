package io.github.wycst.wastnet.http.handler;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Common mock objects for HTTP handler tests.
 * <p>
 * Provides reusable mock implementations of {@link HttpRequest}, {@link HttpResponse},
 * and {@link HttpRoute} to avoid verbose anonymous inner class duplication across test files.
 *
 * @author wangyc
 */
public class MockHttpTestBase {

    // ==================== HttpRequest mock ====================

    /**
     * Create a mock HttpRequest with the given HTTP method.
     */
    public static HttpRequest mockRequest(final HttpMethod method) {
        return mockRequest(method, "/test");
    }

    /**
     * Create a mock HttpRequest with the given HTTP method and URI.
     */
    public static HttpRequest mockRequest(final HttpMethod method, final String uri) {
        return new HttpRequest() {
            @Override public boolean isHttpRequest() { return true; }
            @Override public boolean isUpgrade() { return false; }
            @Override public boolean isBad() { return false; }
            @Override public long getRequestId() { return 0L; }
            @Override public HttpMethod getMethod() { return method; }
            @Override public String getUri() { return uri; }
            @Override public String getRequestUri() { return uri; }
            @Override public HttpVersion getHttpVersion() { return HttpVersion.HTTP_1_1; }
            @Override public String getScheme() { return "http"; }
            @Override public long getContentLength() { return 0; }
            @Override public String getContentType() { return null; }
            @Override public InputStream bodyStream() { return null; }
            @Override public byte[] getBodyData() { return new byte[0]; }
            @Override public String getHeader(String name) { return null; }
            @Override public String getHeader(String name, boolean caseSensitive) { return null; }
            @Override public boolean containsHeader(String name) { return false; }
            @Override public Object getRawHeader(String name) { return null; }
            @Override public List<String> getFullHeader(String name) { return null; }
            @Override public Set<String> getHeaderNames() { return Collections.emptySet(); }
            @Override public String getUriParameter(String name) { return null; }
            @Override public String getQueryString() { return null; }
            @Override public StringBuffer getRequestURL() { return new StringBuffer(uri); }
            @Override public String getDecodedRequestURL() { return uri; }
            @Override public boolean isSSL() { return false; }
            @Override public long getConnectionId() { return 0; }
            @Override public InetSocketAddress getRemoteAddress() { return null; }
            @Override public String getRemoteHost() { return null; }
            @Override public int getRemotePort() { return 0; }
            @Override public InetSocketAddress getServerAddress() { return null; }
            @Override public String getServerHost() { return null; }
            @Override public int getServerPort() { return 0; }
            @Override public boolean isStream() { return false; }
            @Override public boolean isCompleted() { return true; }
            @Override public void complete() {}
            @Override public boolean isMultipart() { return false; }
            @Override public boolean isFormUrlencoded() { return false; }
            @Override public boolean isJson() { return false; }
            @Override public Charset getCharset() { return StandardCharsets.UTF_8; }
            @Override public MultipartField getMultipartField(String name) { return null; }
            @Override public List<MultipartField> getMultipartFields(String name) { return null; }
            @Override public String getMultipartFieldValue(String name) { return null; }
            @Override public List<String> getMultipartFieldValues(String name) { return null; }
            @Override public Set<String> getMultipartFieldNames() { return Collections.emptySet(); }

            @Override
            public List<MultipartField> getMultipartFields() {
                return Collections.emptyList();
            }

            @Override public String getParameter(String name) { return null; }
            @Override public String getBodyParameter(String name) { return null; }
            @Override public List<String> getParameterValues(String name) { return null; }
            @Override public Set<String> getParameterNames() { return Collections.emptySet(); }
            @Override public HttpResponse getResponse() { return null; }
            @Override public void setAttribute(String key, Object value) {}
            @Override public Object getAttribute(String key) { return null; }
            @Override public void delegate(ChannelContext targetCtx) {}

            @Override
            public Object upgrade() throws Exception {
                return null;
            }

            @Override
            public WebSocketConnection upgrade(WebSocketResource ws) throws Exception {
                return null;
            }

            @Override public String toString() { return "MockRequest{" + method + " " + uri + "}"; }
            @Override public String getHost() { return null; }
            @Override public void removeAttribute(String key) {}
            @Override public java.util.Enumeration<String> getAttributeNames() { return Collections.emptyEnumeration(); }
            @Override public java.util.Map<String, String[]> getParameterMap() { return Collections.emptyMap(); }
        };
    }

    // ==================== HttpResponse mock ====================

    /**
     * Create a mock HttpResponse that captures status, content-type, body, and optional header key/value.
     */
    public static HttpResponse mockResponse(final HttpStatus[] statusHolder,
                                             final String[] contentTypeHolder,
                                             final byte[][] bodyHolder) {
        return mockResponse(statusHolder, contentTypeHolder, bodyHolder, null, null);
    }

    /**
     * Create a mock HttpResponse that captures status, Allow header, and body.
     * Specialized for method route tests.
     */
    public static HttpResponse mockResponse(final HttpStatus[] statusHolder,
                                             final String[] allowHeaderHolder,
                                             final byte[][] bodyHolder,
                                             final boolean routeTest) {
        if (!routeTest) {
            return mockResponse(statusHolder, allowHeaderHolder, bodyHolder, null, null);
        }
        return new HttpResponse() {
            @Override public HttpResponse status(HttpStatus s) { statusHolder[0] = s; return this; }
            @Override public HttpResponse status(int code) { statusHolder[0] = HttpStatus.of(code); return this; }
            @Override public void setStatus(HttpStatus status) { statusHolder[0] = status; }
            @Override public void setStatusAndText(HttpStatus s) { statusHolder[0] = s; }
            @Override public HttpStatus getStatus() { return statusHolder[0]; }
            @Override public HttpResponse contentType(String ct) { return this; }
            @Override public void setContentType(String ct) {}
            @Override public String getContentType() { return null; }
            @Override public void setContentLength(long len) {}
            @Override public long getContentLength() { return 0; }
            @Override public HttpResponse contentLength(long len) { return this; }
            @Override public void setCharacterEncoding(String ce) {}
            @Override public String getCharacterEncoding() { return null; }
            @Override public HttpResponse header(String key, Serializable value) {
                if (HttpHeaderNormalized.getAllow().equalsIgnoreCase(key) && allowHeaderHolder != null) {
                    allowHeaderHolder[0] = String.valueOf(value);
                }
                return this;
            }
            @Override public void addHeader(String key, Serializable value) {}
            @Override public void removeHeader(String key) {}
            @Override public void removeHeader(String key, Serializable value) {}
            @Override public void setHeader(String key, Serializable value) {
                if (HttpHeaderNormalized.getAllow().equalsIgnoreCase(key) && allowHeaderHolder != null) {
                    allowHeaderHolder[0] = String.valueOf(value);
                }
            }
            @Override public String getHeader(String key) { return null; }

            @Override
            public List<String> getHeaders(String name) {
                return Collections.emptyList();
            }

            @Override
            public Set<String> getHeaderNames() {
                return Collections.emptySet();
            }

            @Override public HttpResponse body(byte[] body) { if (bodyHolder != null) bodyHolder[0] = body; return this; }
            @Override public HttpResponse body(byte[] body, int offset, int len) { return this; }
            @Override public HttpResponse body(String body) { return this; }
            @Override public void write(byte[] buf) throws java.io.IOException {}
            @Override public void write(byte[] buf, int off, int len) throws java.io.IOException {}
            @Override public HttpResponse chunked() { return this; }
            @Override public HttpResponse chunked(boolean c) { return this; }
            @Override public void setChunked(boolean chunked) {}
            @Override public boolean isChunked() { return false; }
            @Override public void setChunkedEncoding() {}
            @Override public void removeChunkedEncoding() {}
            @Override public void writeChunked(byte[] data) throws java.io.IOException {}
            @Override public void writeChunked(byte[] data, int offset, int count) throws java.io.IOException {}
            @Override public HttpVersion getHttpVersion() { return HttpVersion.HTTP_1_1; }
            @Override public boolean isKeepAlive() { return false; }
            @Override public void setKeepAlive(boolean keepAlive) {}
            @Override public void setLastModified(long millis) {}
            @Override public void setAutoCommit(boolean ac) {}
            @Override public boolean isAutoCommit() { return true; }
            @Override public OutputStream outputStream() { return null; }
            @Override public boolean isGzipSupported() { return false; }
            @Override public void sendFile(java.io.File file) throws java.io.IOException {}
            @Override public void sendFile(java.io.File file, boolean cacheEnabled, int maxAge) throws java.io.IOException {}
            @Override public void sendFile(java.io.File file, boolean cacheEnabled, String cacheControl, String mimeType) throws java.io.IOException {}
            @Override public void flush() throws java.io.IOException {}
            @Override public void commit() throws java.io.IOException {}
            @Override public HttpResponse sse(String data) throws java.io.IOException { return this; }
            @Override public HttpResponse sse(String event, String data) throws java.io.IOException { return this; }
            @Override public HttpResponse sse(String event, String data, String id, long retry) throws java.io.IOException { return this; }
            @Override public boolean isCorrupted() { return false; }
            @Override public void earlyHints(String linkHeader) throws java.io.IOException {}
            @Override public int getBufferSize() { return 0; }
            @Override public boolean isCommitted() { return false; }
            @Override public void reset() {}
            @Override public void resetBuffer() {}
        };
    }

    /**
     * Create a general-purpose mock HttpResponse.
     */
    public static HttpResponse mockResponse(final HttpStatus[] statusHolder,
                                             final String[] contentTypeHolder,
                                             final byte[][] bodyHolder,
                                             final String[] captureKey,
                                             final String[] captureValue) {
        return new HttpResponse() {
            @Override public HttpResponse status(HttpStatus s) { if (statusHolder != null) statusHolder[0] = s; return this; }
            @Override public HttpResponse status(int code) { if (statusHolder != null) statusHolder[0] = HttpStatus.of(code); return this; }
            @Override public void setStatus(HttpStatus status) { if (statusHolder != null) statusHolder[0] = status; }
            @Override public void setStatusAndText(HttpStatus s) { if (statusHolder != null) statusHolder[0] = s; }
            @Override public HttpStatus getStatus() { return statusHolder != null ? statusHolder[0] : null; }
            @Override public HttpResponse contentType(String ct) { if (contentTypeHolder != null) contentTypeHolder[0] = ct; return this; }
            @Override public void setContentType(String ct) { if (contentTypeHolder != null) contentTypeHolder[0] = ct; }
            @Override public String getContentType() { return contentTypeHolder != null ? contentTypeHolder[0] : null; }
            @Override public void setContentLength(long len) {}
            @Override public long getContentLength() { return 0; }
            @Override public HttpResponse contentLength(long len) { return this; }
            @Override public void setCharacterEncoding(String ce) {}
            @Override public String getCharacterEncoding() { return null; }
            @Override public HttpResponse header(String key, Serializable value) {
                if (captureKey != null) captureKey[0] = key;
                if (captureValue != null) captureValue[0] = String.valueOf(value);
                return this;
            }
            @Override public void addHeader(String key, Serializable value) {}
            @Override public void removeHeader(String key) {}
            @Override public void removeHeader(String key, Serializable value) {}
            @Override public void setHeader(String key, Serializable value) {}
            @Override public String getHeader(String key) { return null; }

            @Override
            public List<String> getHeaders(String name) {
                return Collections.emptyList();
            }

            @Override
            public Set<String> getHeaderNames() {
                return Collections.emptySet();
            }

            @Override public HttpResponse body(byte[] body) { if (bodyHolder != null) bodyHolder[0] = body; return this; }
            @Override public HttpResponse body(byte[] body, int offset, int len) { return this; }
            @Override public HttpResponse body(String body) { return this; }
            @Override public void write(byte[] buf) throws java.io.IOException {
                if (bodyHolder != null) bodyHolder[0] = buf;
            }
            @Override public void write(byte[] buf, int off, int len) throws java.io.IOException {}
            @Override public HttpResponse chunked() { return this; }
            @Override public HttpResponse chunked(boolean c) { return this; }
            @Override public void setChunked(boolean chunked) {}
            @Override public boolean isChunked() { return false; }
            @Override public void setChunkedEncoding() {}
            @Override public void removeChunkedEncoding() {}
            @Override public void writeChunked(byte[] data) throws java.io.IOException {}
            @Override public void writeChunked(byte[] data, int offset, int count) throws java.io.IOException {}
            @Override public HttpVersion getHttpVersion() { return HttpVersion.HTTP_1_1; }
            @Override public boolean isKeepAlive() { return false; }
            @Override public void setKeepAlive(boolean keepAlive) {}
            @Override public void setLastModified(long millis) {}
            @Override public void setAutoCommit(boolean ac) {}
            @Override public boolean isAutoCommit() { return true; }
            @Override public OutputStream outputStream() { return null; }
            @Override public boolean isGzipSupported() { return false; }
            @Override public void sendFile(java.io.File file) throws java.io.IOException {
                if (file != null && file.exists() && file.isFile()) {
                    if (statusHolder != null) statusHolder[0] = HttpStatus.OK;
                }
            }
            @Override public void sendFile(java.io.File file, boolean cacheEnabled, int maxAge) throws java.io.IOException {
                if (file != null && file.exists() && file.isFile()) {
                    if (statusHolder != null) statusHolder[0] = HttpStatus.OK;
                }
            }
            @Override public void sendFile(java.io.File file, boolean cacheEnabled, String cacheControl, String mimeType) throws java.io.IOException {
                if (file != null && file.exists() && file.isFile()) {
                    if (statusHolder != null) statusHolder[0] = HttpStatus.OK;
                }
            }
            @Override public void flush() throws java.io.IOException {}
            @Override public void commit() throws java.io.IOException {}
            @Override public HttpResponse sse(String data) throws java.io.IOException { return this; }
            @Override public HttpResponse sse(String event, String data) throws java.io.IOException { return this; }
            @Override public HttpResponse sse(String event, String data, String id, long retry) throws java.io.IOException { return this; }
            @Override public boolean isCorrupted() { return false; }
            @Override public void earlyHints(String linkHeader) throws java.io.IOException {}
            @Override public int getBufferSize() { return 0; }
            @Override public boolean isCommitted() { return false; }
            @Override public void reset() {}
            @Override public void resetBuffer() {}
        };
    }

    // ==================== HttpRoute noop mock ====================

    /**
     * Create a no-op HttpRoute that does nothing on handle().
     */
    public static HttpRoute noopRoute() {
        return new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) {
            }
        };
    }
}
