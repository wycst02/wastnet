package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.InputStream;
import java.io.Serializable;
import java.util.*;

/**
 * Abstract base class for decoded HTTP requests.
 * <p>
 * This class is constructed by decoding raw HTTP request data, parsing the request line,
 * headers, URI parameters, and request body. The decoding is performed by
 * {@link HttpRequestDecoder} which parses HTTP/1.0 and HTTP/1.1 request bytes into
 * structured request objects.
 * <p>
 * Concrete decoding implementations are provided by subclasses, including:
 * <ul>
 *   <li>{@link HttpDefaultRequest} - Default HTTP request decoding implementation</li>
 *   <li>{@link HttpStreamRequest} - Streaming HTTP request decoding implementation</li>
 * </ul>
 *
 * @since 2024-2-9
 * @author wangyc
 */
public abstract class HttpDecodedRequest extends HttpInternalRequest {

    private final byte[] rawUriBytes;
    protected byte[] uriAsciiBytes;
    protected String uri;
    protected final HttpMethod method;
    protected final String requestUri;
    protected final Map<String, List<String>> parameters;
    protected final HttpVersion httpVersion;
    protected final Map<String, Object> headers;
    protected final byte[] bodyData;
    protected final long contentLength;
    protected final String contentType;

    private String queryStringCache;

    public HttpDecodedRequest(HttpMethod method, byte[] uriAsciiBytes, String requestUri, Map<String, List<String>> parameters, HttpVersion httpVersion, Map<String, Object> headers, byte[] bodyData, long contentLength, String contentType, ChannelContext ctx) {
        super(ctx);
        this.uri = HttpUnsafe.createAsciiString(this.uriAsciiBytes = this.rawUriBytes = uriAsciiBytes);
        this.requestUri = requestUri;
        this.parameters = parameters;
        this.method = method;
        this.httpVersion = httpVersion;
        this.bodyData = bodyData;
        this.headers = new LinkedHashMap<String, Object>(headers);
        this.contentLength = contentLength;
        this.contentType = contentType;
        this.response = new HttpDefaultResponse(this, ctx);
    }

    HttpDecodedRequest() {
        super(null);
        this.uriAsciiBytes = this.rawUriBytes = null;
        this.uri = null;
        this.requestUri = null;
        this.parameters = null;
        this.httpVersion = null;
        this.headers = null;
        this.method = null;
        this.bodyData = EMPTY_BODY;
        this.contentLength = 0;
        this.contentType = null;
        this.response = null;
    }

    /**
     * Constructor for subclasses that only need HttpVersion and ChannelContext.
     * Other fields will be set to default values.
     *
     * @param httpVersion HTTP version
     * @param headers     headers
     * @param ctx         Channel context
     */
    HttpDecodedRequest(HttpVersion httpVersion, Map<String, Object> headers, ChannelContext ctx) {
        super(ctx);
        this.uriAsciiBytes = this.rawUriBytes = null;
        this.uri = null;
        this.requestUri = null;
        this.parameters = null;
        this.httpVersion = httpVersion;
        this.headers = new HashMap<String, Object>(headers);
        this.method = null;
        this.bodyData = EMPTY_BODY;
        this.contentLength = 0;
        this.contentType = null;
        this.response = ctx != null ? new HttpDefaultResponse(this, ctx) : null;
    }

    /**
     * Rewrite URI.
     * <p>
     * Only non-safe characters are percent-encoded,
     * URI structural characters ({@code / ? = & #}) are preserved as-is.
     *
     * @param newUri New URI
     */
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
        this.uri = newUri;
        this.uriAsciiBytes = newUri.getBytes();
    }

    // ==================== HttpRequest interface implementation ====================

    @Override
    public final String getScheme() {
        return isSSL() ? "https" : "http";
    }

    @Override
    public final String getUri() {
        return uri;
    }

    @Override
    public String getRequestUri() {
        return requestUri;
    }

    @Override
    public InputStream bodyStream() {
        return null;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public final HttpVersion getHttpVersion() {
        return httpVersion;
    }

    @Override
    public Set<String> getHeaderNames() {
        return new LinkedHashSet<String>(headers.keySet());
    }

    @Override
    public final String getHeader(String name) {
        return getHeader(name, false);
    }

    @Override
    public final boolean containsHeader(String name) {
        return headers.containsKey(HttpHeaderUtils.normalizeHeaderKey(String.valueOf(name)));
    }

    @Override
    public final String getHeader(String name, boolean caseSensitive) {
        Object value = caseSensitive ? headers.get(name) : getRawHeader(name);
        if (value == null) return null;
        return value.getClass() == String.class ? (String) value : ((List<String>) value).get(0);
    }

    /**
     * Set header directly (overwrite mode, no multi-value merge)
     *
     * @param key   the header key
     * @param value the header value
     */
    @Override
    public void setHeader(String key, Serializable value) {
        headers.put(HttpHeaderUtils.normalizeHeaderKey(key), String.valueOf(value));
    }

    /**
     * Remove header by key
     *
     * @param key the header key
     */
    @Override
    public void removeHeader(String key) {
        headers.remove(HttpHeaderUtils.normalizeHeaderKey(key));
    }

    @Override
    public byte[] getBodyData() {
        return bodyData;
    }

    @Override
    public String getUriParameter(String name) {
        List<String> values = parameters.get(name);
        return values == null ? null : values.get(0);
    }

    @Override
    protected List<String> getUriParameterValues(String name) {
        return parameters.get(name);
    }

    @Override
    protected Set<String> getUriParameterNames() {
        if (parameters == null) return Collections.emptySet();
        return parameters.keySet();
    }

    @Override
    public String getQueryString() {
        if (rawUriBytes == null) return null;
        if (queryStringCache == null) {
            for (int i = requestUri.length(); i < rawUriBytes.length; ++i) {
                if (rawUriBytes[i] == '?') {
                    return queryStringCache = new String(rawUriBytes, i + 1, rawUriBytes.length - i - 1);
                }
            }
        }
        return queryStringCache;
    }

    @Override
    public final StringBuffer getRequestURL() {
        if (uriAsciiBytes == null) return null;
        StringBuffer sb = new StringBuffer(128);
        // Scheme
        sb.append(getScheme());
        sb.append("://");
        // Host
        String host = getServerHost();
        if (host != null) {
            sb.append(host);
        }
        // Port (skip default ports)
        int port = getServerPort();
        int defaultPort = ctx.isSSL() ? 443 : 80;
        if (port > 0 && port != defaultPort) {
            sb.append(':').append(port);
        }
        // Path (without query string)
        int qIdx = -1;
        for (int i = 0; i < uriAsciiBytes.length; i++) {
            if (uriAsciiBytes[i] == '?') {
                qIdx = i;
                break;
            }
        }
        sb.append(new String(uriAsciiBytes, 0, qIdx > -1 ? qIdx : uriAsciiBytes.length));
        return sb;
    }

    // ==================== Delegate (request forwarding) ====================
    // Get protocol first line bytes
    protected byte[] getRequestProtocolPreface() {
        byte[] methodBytes = method.name().getBytes();
        byte[] versionBytes = httpVersion.toString().getBytes();
        byte[] bytes = new byte[methodBytes.length + 1 + uriAsciiBytes.length + 1 + versionBytes.length + 2];
        int offset = 0;
        System.arraycopy(methodBytes, 0, bytes, offset, methodBytes.length);
        offset += methodBytes.length;
        bytes[offset++] = ' ';
        System.arraycopy(uriAsciiBytes, 0, bytes, offset, uriAsciiBytes.length);
        offset += uriAsciiBytes.length;
        bytes[offset++] = ' ';
        System.arraycopy(versionBytes, 0, bytes, offset, versionBytes.length);
        offset += versionBytes.length;
        bytes[offset++] = '\r';
        bytes[offset] = '\n';
        return bytes;
    }

    @Override
    public void delegate(ChannelContext targetCtx) throws Throwable {
        // Check if the target channel context is null or the same as the current channel context
        if (targetCtx == null || targetCtx == ctx) return;
        // Write request protocol preface (request line)
        targetCtx.write(getRequestProtocolPreface());
        // Write headers
        writeHeadersTo(targetCtx);
        // Write body
        delegateBody(targetCtx);
        targetCtx.flush();
    }

    /**
     * Write headers to target channel context.
     *
     * @param targetCtx the target channel context
     * @throws Throwable if writing fails
     */
    protected void writeHeadersTo(ChannelContext targetCtx) throws Throwable {
        StringBuilder headerBuilder = new StringBuilder();
        Set<String> headerNames = getHeaderNames();
        for (String headerName : headerNames) {
            String value = getHeader(headerName);
            if (value != null) {
                headerBuilder.append(headerName).append(": ").append(value).append("\r\n");
            }
        }
        headerBuilder.append("\r\n");
        targetCtx.write(headerBuilder.toString().getBytes());
    }

    /**
     * Delegate the request body to a target channel context.
     * Default implementation writes the body data as bytes.
     * Subclasses can override this method to handle streaming body data
     * to prevent OOM for large requests.
     *
     * @param targetCtx the target channel context to delegate to
     * @throws Throwable if delegation fails
     */
    protected void delegateBody(ChannelContext targetCtx) throws Throwable {
        byte[] body = getBodyData();
        if (body != null && body.length > 0) {
            targetCtx.write(body);
        }
    }

    // ==================== Internal helpers ====================

    @Override
    public Object getRawHeader(String name) {
        if (name == null) return null;
        Set<Map.Entry<String, Object>> entrySet = headers.entrySet();
        for (Map.Entry<String, Object> entry : entrySet) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Create a duplicate {@link HttpDefaultResponse} bound to the given channel context.
     * <p>
     * Used by proxy adapters to create a response for writing back to the original client
     * while forwarding the original request to an upstream server.
     *
     * @return a new HttpDefaultResponse bound to targetCtx
     */
    public HttpDefaultResponse newResponse() {
        return new HttpDefaultResponse(this, ctx);
    }
}
