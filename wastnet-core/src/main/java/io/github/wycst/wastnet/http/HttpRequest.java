package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketConnection;
import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * protocol layer request abstraction
 *
 * @since 2024-1-20
 * @author wangyc
 */
public interface HttpRequest extends HttpMessage {

    byte[] EMPTY_BODY = new byte[0];

    /**
     * Get request ID (auto-increment, unique per request).
     *
     * @return Request ID
     */
    long getRequestId();

    /**
     * Get HTTP protocol version
     *
     * @return HTTP version
     */
    HttpVersion getHttpVersion();

    /**
     * Get request body as input stream
     *
     * @return Request body input stream
     */
    InputStream bodyStream();

    /**
     * Get request scheme
     *
     * @return Request scheme
     */
    String getScheme();

    /**
     * Retrieve the raw URI that has not been decoded
     *
     * @return Raw request URI as string
     */
    String getUri();

    /**
     * Decoded request URI (Does not include query parameters)
     *
     * @return Decoded request URI
     */
    String getRequestUri();

    /**
     * Check if request is completed
     *
     * @return true if request is completed, false otherwise
     */
    boolean isCompleted();

    /**
     * Complete the request if the request is stream request.
     * Framework automatically checks and calls this method when request-response processing ends.
     * Application layer should NOT call this method directly.
     */
    void complete();

    /**
     * Get content length of request body
     *
     * @return Content length in bytes
     */
    long getContentLength();

    /**
     * Get content type of request
     *
     * @return Content type string
     */
    String getContentType();

    /**
     * Get HTTP request method
     *
     * @return HTTP method enum
     */
    HttpMethod getMethod();

    /**
     * Get all header names in request
     *
     * @return Set of header names
     */
    Set<String> getHeaderNames();

    /**
     * Get header value by name (case-insensitive)
     *
     * @param name Header name
     * @return Header value, null if not found
     */
    String getHeader(String name);

    /**
     * Check if request contains header
     *
     * @param name Header name
     * @return true if request contains header, false otherwise
     */
    boolean containsHeader(String name);

    /**
     * Get header value by name with case sensitivity option.
     * <p>
     * Note: In HTTP/2, all header names are lowercase and caseSensitive parameter is ignored.
     *
     * @param name          Header name
     * @param caseSensitive Whether to perform case-sensitive lookup
     * @return Header value, null if not found
     */
    String getHeader(String name, boolean caseSensitive);

    /**
     * Get raw header value by name (case-insensitive).
     * Returns the raw stored value (String for single value, List&lt;String&gt; for multiple).
     *
     * @param name Header name
     * @return raw header value (String or List&lt;String&gt;), or null if not found
     */
    Object getRawHeader(String name);

    /**
     * Get full header value by name (case-insensitive), always returns a List.
     *
     * @param name Header name
     * @return Header values as List, or null if header not found
     */
    List<String> getFullHeader(String name);

    /**
     * Get request parameter value by name
     *
     * @param name Parameter name
     * @return Parameter value, null if not found
     */
    String getParameter(String name);

    /**
     * Get URI parameter value by name
     *
     * @param name URI parameter name
     * @return URI parameter value, null if not found
     */
    String getUriParameter(String name);

    /**
     * Get body parameter value by name (from multipart or form-urlencoded body).
     *
     * @param name body parameter name
     * @return body parameter value, null if not found
     */
    String getBodyParameter(String name);

    /**
     * Get all parameter names
     *
     * @return Set of parameter names
     */
    Set<String> getParameterNames();

    /**
     * Get query string from URI (the part after '?').
     * Returns the raw undecoded query string.
     *
     * @return query string, or null if no query string
     */
    String getQueryString();

    /**
     * Get request URL (scheme://host:port/path, without query string).
     * Servlet spec &sect;3.4: the returned URL must not include query parameters.
     *
     * @return request URL as StringBuffer
     */
    StringBuffer getRequestURL();

    /**
     * Get decoded full request URL.
     * Percent-encoded characters are decoded.
     *
     * @return decoded full request URL
     */
    String getDecodedRequestURL();

    /**
     * Get request body data
     *
     * @return Request body data
     */
    byte[] getBodyData();

    /**
     * Set request attribute
     *
     * @param key   Attribute key
     * @param value Attribute value
     */
    void setAttribute(String key, Object value);

    /**
     * Get request attribute by key
     *
     * @param key Attribute key
     * @return Attribute value, null if not found
     */
    Object getAttribute(String key);

    /**
     * Remove request attribute by key
     *
     * @param key Attribute key
     */
    void removeAttribute(String key);

    /**
     * Get all attribute names
     *
     * @return Enumeration of attribute names
     */
    Enumeration<String> getAttributeNames();

    /**
     * Get client address information
     *
     * @return Client InetSocketAddress, returns null if unavailable
     */
    InetSocketAddress getRemoteAddress();

    /**
     * Get client hostname or IP address
     *
     * @return Client hostname/IP, returns null if unavailable
     */
    String getRemoteHost();

    /**
     * Get client port number
     *
     * @return Client port number, returns -1 if unavailable
     */
    int getRemotePort();

    /**
     * Get server address information
     *
     * @return Server InetSocketAddress, returns null if unavailable
     */
    InetSocketAddress getServerAddress();

    /**
     * Get server hostname or IP address
     *
     * @return Server hostname/IP, returns null if unavailable
     */
    String getServerHost();

    /**
     * Get server port number
     *
     * @return Server port number, returns -1 if unavailable
     */
    int getServerPort();

    /**
     * Check if request is a stream request
     *
     * @return true if request is a stream request, false otherwise
     */
    boolean isStream();

    /**
     * Check if request is a bad request.
     * <p>
     * Note: Once a request reaches the application layer (business handler),
     * this method will always return false. BadHttpRequest instances are
     * intercepted and handled by the framework before reaching application code.
     *
     * @return true if request is a bad request, false otherwise
     */
    boolean isBad();

    /**
     * Check if request is a multipart request.
     * Returns true for requests with multipart/form-data content type.
     *
     * @return true if request is multipart, false otherwise
     */
    boolean isMultipart();

    /**
     * Check if request content type is application/x-www-form-urlencoded.
     *
     * @return true if content type is form-urlencoded, false otherwise
     */
    boolean isFormUrlencoded();

    /**
     * Check if request content type is JSON (application/json or text/json).
     *
     * @return true if content type is JSON, false otherwise
     */
    boolean isJson();

    /**
     * Get charset from content-type header.
     * Defaults to UTF-8 if not specified.
     *
     * @return charset
     */
    Charset getCharset();

    /**
     * Get multipart field by name.
     *
     * @param name field name
     * @return MultipartField, or null if not found or not multipart
     */
    MultipartField getMultipartField(String name);

    /**
     * Get all multipart fields by name.
     * Supports multiple fields with the same name (e.g., multiple file uploads).
     *
     * @param name field name
     * @return list of MultipartField, or null if not found or not multipart
     */
    List<MultipartField> getMultipartFields(String name);

    /**
     * Get a multipart field value as string by name.
     *
     * @param name field name
     * @return field value as string, or null if not found or is a file field
     */
    String getMultipartFieldValue(String name);

    /**
     * Get all multipart field values as string by name.
     * File fields are ignored, only returns values of form fields.
     *
     * @param name field name
     * @return list of field values, empty list if not found
     */
    List<String> getMultipartFieldValues(String name);

    /**
     * Get all multipart field names.
     *
     * @return set of field names, empty set if not multipart
     */
    Set<String> getMultipartFieldNames();

    /**
     * Get all multipart fields in request body order.
     *
     * @return list of all MultipartField instances, empty list if not multipart
     */
    List<MultipartField> getMultipartFields();

    /**
     * Get all parameter values by name (supports both multipart and form-urlencoded).
     *
     * @param name parameter name
     * @return list of parameter values, empty list if not found
     */
    List<String> getParameterValues(String name);

    /**
     * Get all parameters as an immutable map.
     * Merges URI query parameters and body parameters (form-urlencoded and
     * multipart form fields, excluding file uploads).
     * Aligned with Servlet spec {@code ServletRequest.getParameterMap()}.
     *
     * @return immutable map of parameter name to value array
     */
    Map<String, String[]> getParameterMap();

    /**
     * Get response object associated with request
     *
     * @return Response object
     */
    HttpResponse getResponse();

    /**
     * Get the host value from the request.
     * <p>
     * For HTTP/1.x, returns the value of the {@code Host} header.
     * For HTTP/2, returns the value of the {@code :authority} pseudo-header.
     *
     * @return the host string (may include port), or {@code null} if not present
     */
    String getHost();

    /**
     * Get connection ID
     *
     * @return Connection ID
     */
    long getConnectionId();

    /**
     * Check if request is a secure (HTTPS) request.
     *
     * @return true if request is a secure request, false otherwise
     */
    boolean isSSL();

    /**
     * Delegate the request to a target channel context.
     * This method first writes the response headers to the target context,
     * then writes the request body data to the target context.
     *
     * @param targetCtx the target channel context to delegate to
     * @throws Throwable if delegation fails
     */
    void delegate(ChannelContext targetCtx) throws Throwable;

    /**
     * Programmatic upgrade (no resource): upgrade using the default shared
     * resource (broadcast disabled) and return the connection directly.
     * <p>
     * Unlike {@code router.ws(path, resource)} (declarative, route-registered,
     * broadcast-capable), this path is end-to-end: the caller owns the
     * connection and drives data exchange itself. HTTP/2 throws an exception.
     *
     * @return the websocket connection, or null for h2c / non-upgrade / rejected
     * @throws Exception if the upgrade fails or is not supported
     */
    Object upgrade() throws Exception;

    /**
     * Programmatic upgrade with a custom resource.
     * <p>
     * Same end-to-end model as {@link #upgrade()}, but lets the caller supply a
     * personalized {@link WebSocketResource}. Broadcast must be disabled, since
     * programmatic mode has no group capability.
     *
     * @param ws the websocket resource carrying user hooks and configuration
     * @return the websocket connection, or null if the handshake fails
     * @throws Exception if the upgrade fails or the resource enables broadcast
     */
    WebSocketConnection upgrade(WebSocketResource ws) throws Exception;
}
