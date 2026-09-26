package io.github.wycst.wastnet.http;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * protocol layer response abstraction
 *
 * @author wangyc
 * @since 2024-1-20
 */
public interface HttpResponse /*extends HttpMessage*/ {

    /**
     * Get the HTTP version of this response
     *
     * @return the HTTP version
     */
    HttpVersion getHttpVersion();

    /**
     * Set the HTTP status code for this response
     *
     * @param status the HTTP status to set
     */
    void setStatus(HttpStatus status);

    /**
     * Set the HTTP status code for this response with chain call support
     *
     * @param status the HTTP status to set
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse status(HttpStatus status);

    /**
     * Set the HTTP status code for this response with chain call support
     *
     * @param code the HTTP status code to set
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse status(int code);

    /**
     * Set the HTTP status code and text for this response
     *
     * @param httpStatus the HTTP status to set
     */
    void setStatusAndText(HttpStatus httpStatus);

    /**
     * Add a header to the response
     * If the header already exists, it will be stored as a list of values
     * Header keys are processed using standard HTTP header key format
     * The value type is limited to Number, Boolean or CharSequence; other
     * Serializable types are not guaranteed to be written as a valid header value.
     *
     * @param key   the header key
     * @param value the header value, only Number / Boolean / CharSequence are supported
     */
    void addHeader(String key, Serializable value);

    /**
     * Set a header, replacing any existing value(s) for the same key
     * The value type is limited to Number, Boolean or CharSequence; other
     * Serializable types are not guaranteed to be written as a valid header value.
     *
     * @param key   the header key
     * @param value the header value, only Number / Boolean / CharSequence are supported
     */
    void setHeader(String key, Serializable value);

    /**
     * Get a header value by key
     *
     * @param key the header key
     * @return the header value, or null if not found
     */
    String getHeader(String key);

    /**
     * Get all values for the given header key.
     * Returns the list of values stored for the key (typically added via
     * {@link #addHeader(String, Serializable)}). If the key holds a single
     * value it is returned as a one-element list; if the key is absent null
     * is returned.
     *
     * @param name the header key (case-insensitive)
     * @return the header values, or null if the key is not present
     */
    List<String> getHeaders(String name);

    /**
     * Get the names of all headers set on this response.
     * The returned names are in the normalized (lower-case) form used
     * internally; callers should not rely on the original casing.
     *
     * @return the header names, never null
     */
    Set<String> getHeaderNames();

    /**
     * Add a header to the response with chain call support
     * If the header already exists, it will be stored as a list of values
     * Header keys are processed using standard HTTP header key format
     *
     * @param key   the header key
     * @param value the header value
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse header(String key, Serializable value);

    /**
     * Remove a header completely by key
     * This removes all values associated with the given header key
     * Updates CONTENT-related status flags accordingly
     *
     * @param key the header key to remove
     */
    void removeHeader(String key);

    /**
     * Remove specific value from header with given key
     * If the key corresponds to a List and only one element remains after removal,
     * it will be automatically converted to String type.
     * If the List becomes empty, the key will be completely removed.
     * Maintains CONTENT-related status flags consistency.
     *
     * @param key   the header key
     * @param value the header value to remove
     */
    void removeHeader(String key, Serializable value);

    /**
     * Set the Content-Length header value
     *
     * @param contentLength the content length in bytes
     */
    void setContentLength(long contentLength);

    /**
     * Set the Content-Length header value with chain call support
     *
     * @param contentLength the content length in bytes
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse contentLength(long contentLength);

    /**
     * Get the Content-Length header value
     *
     * @return the content length in bytes
     */
    long getContentLength();

    /**
     * Set the Content-Type header value
     *
     * @param contentType the MIME type of the content
     */
    void setContentType(String contentType);

    /**
     * Set the Content-Type header value with chain call support
     *
     * @param contentType the MIME type of the content
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse contentType(String contentType);

    /**
     * Set response body data with chain call support.
     * This method REPLACES any existing body data, not appends to it.
     * For appending data, use write() methods instead.
     *
     * @param body the response body data
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse body(byte[] body);

    /**
     * Set a portion of response body data with chain call support.
     * This method REPLACES any existing body data, not appends to it.
     * For appending data, use write() methods instead.
     *
     * @param body   the response body data
     * @param offset the starting offset in the body array
     * @param len    the length of the portion to set
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse body(byte[] body, int offset, int len);

    /**
     * Set response body from string with chain call support.
     * The string is encoded using UTF-8 charset.
     * This method REPLACES any existing body data, not appends to it.
     * For appending data, use write() methods instead.
     *
     * @param body the response body as string
     * @return this HttpResponse instance for chain calling
     */
    HttpResponse body(String body);

    /**
     * Send file as response body.
     * This method reads the file content and sends it to the client.
     * Automatically sets Content-Length header based on file size.
     * After calling this method, the response is completed immediately and
     * no further modifications are allowed (all subsequent operations are silently ignored).
     * If the file cannot be read, returns 404 Not Found.
     * <p>
     * Uses zero-copy transfer (FileChannel.transferTo) for better performance.
     * No size limit - can send files of any size.
     * File content is not loaded into memory, avoiding OOM for large files.
     *
     * @param file the file to send as response body
     * @throws IOException if an I/O error occurs while reading the file
     */
    void sendFile(File file) throws IOException;

    /**
     * Send a file with cache control support.
     *
     * @param file         the file to send
     * @param cacheEnabled whether cache is enabled
     * @param maxAge       max-age in seconds, negative means skip Cache-Control
     * @throws IOException if an I/O error occurs
     */
    void sendFile(File file, boolean cacheEnabled, int maxAge) throws IOException;

    /**
     * Send a file with cache control support and a pre-resolved MIME type.
     * <p>
     * This overload accepts an already-resolved MIME type and a full Cache-Control
     * value to avoid redundant lookups when the caller already knows the MIME and
     * cache policy (e.g. after matching per-MIME expires rules).
     * <p>
     * When {@code cacheControl} is non-null, it is used as the {@code Cache-Control}
     * header value; an empty string skips the header entirely; {@code null} defaults
     * to {@code "public, no-cache"} (always validate via 304).
     *
     * @param file         the file to send
     * @param cacheEnabled whether cache is enabled
     * @param cacheControl Cache-Control header value, or null for default no-cache
     * @param mimeType     pre-resolved MIME type for the file
     * @throws IOException if an I/O error occurs
     */
    void sendFile(File file, boolean cacheEnabled, String cacheControl, String mimeType) throws IOException;

    /**
     * Get the Content-Type header value
     *
     * @return the MIME type of the content
     */
    String getContentType();

    /**
     * Set the character encoding for the response content
     * This will be added to the Content-Type header if not already present
     *
     * @param characterEncoding the character encoding (e.g., UTF-8, GBK)
     */
    void setCharacterEncoding(String characterEncoding);

    /**
     * Get the character encoding for the response content
     *
     * @return the character encoding, or null if not set
     */
    String getCharacterEncoding();

    /**
     * Flush the response to the client
     * Writes headers and body content to the underlying channel
     * Headers are sent only once, subsequent calls only flush body content
     *
     * @throws IOException if an I/O error occurs during flushing
     */
    void flush() throws IOException;

    /**
     * Commit the response and complete transmission
     * For chunked encoding, sends the final chunk marker (0\r\n\r\n)
     * This method should be called to properly finish the HTTP response
     *
     * @throws IOException if an I/O error occurs during committing
     */
    void commit() throws IOException;

    /**
     * Write chunked data for Transfer-Encoding: chunked responses
     * This method handles the chunked transfer encoding format automatically
     *
     * @param data   the data to write as a chunk
     * @throws IOException           if an I/O error occurs
     * @throws IllegalStateException if chunked encoding is not supported or enabled
     */
    default void writeChunked(byte[] data) throws IOException {
        if (data == null) return;
        writeChunked(data, 0, data.length);
    }

    /**
     * Write chunked data for Transfer-Encoding: chunked responses
     * This method handles the chunked transfer encoding format automatically
     *
     * @param data   the data to write as a chunk
     * @param offset the starting position in the data
     * @param count  the number of bytes to write
     * @throws IOException           if an I/O error occurs
     * @throws IllegalStateException if chunked encoding is not supported or enabled
     */
    void writeChunked(byte[] data, int offset, int count) throws IOException;

    /**
     * Write bytes to response body
     * Appends the given byte array to the response body buffer
     *
     * @param buf the byte array to write
     * @throws IOException if an I/O error occurs during flush
     */
    void write(byte[] buf) throws IOException;

    /**
     * Write bytes to response body with offset and length
     * Appends a portion of the given byte array to the response body buffer
     *
     * @param buf    the byte array to write
     * @param offset the starting position in the array
     * @param count  the number of bytes to write
     * @throws IOException if an I/O error occurs during flush
     */
    void write(byte[] buf, int offset, int count) throws IOException;

    /**
     * Get the OutputStream for writing response body
     * Returns a cached OutputStream instance for this response
     * Provides convenient way to write streaming content
     *
     * @return the OutputStream for writing response body
     */
    OutputStream outputStream();

    /**
     * Get the current HTTP status
     *
     * @return the HTTP status code and message
     */
    HttpStatus getStatus();

    /**
     * Set Last-Modified header for caching purposes
     * Adds the Last-Modified header with the given timestamp
     *
     * @param lastModifiedMillis last modified timestamp in milliseconds
     */
    void setLastModified(long lastModifiedMillis);

    /**
     * Get chunked transfer encoding status
     *
     * @return true if chunked encoding is enabled, false otherwise
     */
    boolean isChunked();

    /**
     * Set chunked transfer encoding status
     *
     * @param chunked true to enable chunked encoding, false to disable
     * @throws IllegalStateException if Content-Length is already set when enabling chunked
     */
    void setChunked(boolean chunked);

    /**
     * Quick method to set Transfer-Encoding: chunked header
     * Automatically enables chunked transfer encoding mode
     *
     * @throws IllegalStateException if Content-Length is already set
     */
    void setChunkedEncoding();

    /**
     * Quick method to remove chunked transfer encoding
     * Automatically disables chunked mode and removes related headers
     */
    void removeChunkedEncoding();

    /**
     * Set chunked transfer encoding with chain call support
     * Enables Transfer-Encoding: chunked header
     *
     * @return this HttpResponse instance for chain calling
     * @throws IllegalStateException if Content-Length is already set
     */
    HttpResponse chunked();

    /**
     * Set chunked transfer encoding status with chain call support
     *
     * @param chunked true to enable chunked encoding, false to disable
     * @return this HttpResponse instance for chain calling
     * @throws IllegalStateException if Content-Length is already set when enabling chunked
     */
    HttpResponse chunked(boolean chunked);

    /**
     * Get keep-alive status
     *
     * @return true if connection should be kept alive, false otherwise
     */
    boolean isKeepAlive();

    /**
     * Set keep-alive status
     *
     * @param keepAlive true to keep connection alive, false to close connection
     */
    void setKeepAlive(boolean keepAlive);

    /**
     * Set auto-commit mode for response.
     * When auto-commit is enabled (default), framework automatically commits response after handler completes.
     * When disabled, application must call response.commit() manually after writing all response data.
     * This is useful for async scenarios where response data is written in separate thread.
     * <p>
     * Note: In async threads, only body data writing is supported, header settings are invalid.
     * Therefore, make sure to set all required headers before entering async thread.
     *
     * @param autoCommit true for automatic commit, false for manual commit
     */
    void setAutoCommit(boolean autoCommit);

    /**
     * Get auto-commit mode status
     *
     * @return true if auto-commit is enabled, false if manual commit is required
     */
    boolean isAutoCommit();

    /**
     * Check if client supports GZIP compression
     * Checks the Accept-Encoding header of the request
     * <p>
     * This method allows the application layer to determine whether the client
     * can handle GZIP compressed responses before calling bodyCompressed() or compressedOutputStream()
     *
     * @return true if client supports gzip, false otherwise
     */
    boolean isGzipSupported();

    /**
     * Reserved method: check if the response stream is corrupted.
     * <p>
     * Returns true when response transmission failed and data may be incomplete.
     * <b>Reserved for internal framework use</b> — fine-grained control is not yet
     * implemented, and external application layers are advised not to call it.
     *
     * @return true if response is corrupted, false otherwise
     */
    boolean isCorrupted();

    /**
     * Returns the underlying write buffer size in bytes.
     *
     * @return the write buffer size, or 0 if none is allocated
     */
    int getBufferSize();

    /**
     * Returns whether the response has been committed, i.e. its status code and
     * headers have already been sent to the client.
     *
     * @return true if the response headers have been sent, false otherwise
     */
    boolean isCommitted();

    /**
     * Reset the response to its initial state, clearing the status code, all headers,
     * and the buffered body. No-op equivalent to undoing everything written so far.
     * Throws {@link IllegalStateException} if the response headers have already been
     * sent (committed).
     */
    void reset();

    /**
     * Clear only the buffered response body, keeping the status code and headers.
     * Throws {@link IllegalStateException} if the response headers have already been
     * sent (committed).
     */
    void resetBuffer();

    /**
     * Send a Server-Sent Event with the given data.
     * <p>
     * Automatically sets {@code Content-Type: text/event-stream}, {@code Cache-Control: no-cache},
     * and enables chunked transfer encoding on first call. Each event is flushed immediately.
     * <p>
     * Note: The handler must NOT return while SSE is active (enter a loop), as the connection
     * must stay open for continuous events. The framework will not auto-commit or close the
     * response until the handler returns.
     * <p>
     * Usage:
     * <pre>{@code
     * router.get("/events", (req, res) -> {
     *     while (!closed) {
     *         res.sse("{\"time\":" + System.currentTimeMillis() + "}");
     *         Thread.sleep(1000);
     *     }
     * });
     * }</pre>
     *
     * @param data the event data (must not contain \n\n as it ends the event)
     * @return this response for chain calling
     * @throws IOException if an I/O error occurs
     */
    HttpResponse sse(String data) throws IOException;

    /**
     * Send a Server-Sent Event with a custom event type.
     * <p>
     * Produces: {@code event: <event>\ndata: <data>\n\n}
     * Automatically enables chunked encoding and flushes after each event.
     *
     * @param event the event type name
     * @param data  the event data
     * @return this response for chain calling
     * @throws IOException if an I/O error occurs
     */
    HttpResponse sse(String event, String data) throws IOException;

    /**
     * Send a Server-Sent Event with full control over event type, data, ID and retry.
     * <p>
     * Produces:
     * {@code id: <id>\nretry: <retry>\nevent: <event>\ndata: <data>\n\n}
     * Fields with null/zero values are omitted.
     *
     * @param event the event type name (null to omit)
     * @param data  the event data
     * @param id    the event ID for reconnection recovery (null to omit)
     * @param retry the reconnection time in milliseconds (<= 0 to omit)
     * @return this response for chain calling
     * @throws IOException if an I/O error occurs
     */
    HttpResponse sse(String event, String data, String id, long retry) throws IOException;

    /**
     * Send 103 Early Hints response (RFC 8297).
     * <p>
     * Informs the client about resources that will likely be needed by the final response,
     * allowing the client to start preloading them before the main response arrives.
     * Must be called before writing any response headers or body.
     * <p>
     * Only effective for HTTP/1.1 and HTTP/2 connections.
     *
     * @param linkHeader Link header value, e.g. "&lt;/style.css&gt;; rel=preload, &lt;/app.js&gt;; rel=preload"
     * @throws IOException if an I/O error occurs
     */
    void earlyHints(String linkHeader) throws IOException;
}
