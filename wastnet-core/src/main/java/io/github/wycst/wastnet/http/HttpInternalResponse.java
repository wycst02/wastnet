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
package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.h2.Http2Response;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Abstract base class for HTTP responses, implementing {@link HttpResponse}.
 * <p>
 * Provides shared functionality for both HTTP/1.x ({@link HttpDefaultResponse})
 * and HTTP/2 ({@link Http2Response}) implementations:
 * <ul>
 *   <li>Status, Content-Type, Content-Length, encoding management</li>
 *   <li>Chain API ({@link #status(int)}, {@link #contentType(String)}, etc.)</li>
 *   <li>Body buffering and streaming output</li>
 *   <li>GZIP compression (auto-compression + streaming compression)</li>
 *   <li>File sending skeleton with 304 cache validation, MIME resolution, compression decision</li>
 *   <li>ETag generation and conditional request handling (If-None-Match / If-Modified-Since)</li>
 * </ul>
 * <p>
 * Wire format differences are delegated to subclasses via:
 * <ul>
 *   <li>{@link #notFound()} — send 404 response</li>
 * </ul>
 *
 * @since 2024-1-23
 * @author wangyc
 */
public abstract class HttpInternalResponse implements HttpResponse {

    /**
     * Channel context for writing data to the client
     */
    protected final ChannelContext ctx;

    /**
     * HTTP request object
     */
    protected final HttpRequest request;

    /**
     * Body buffer
     */
    protected final HttpBuf bodyBuf;

    /**
     * Response status, default 200 OK
     */
    protected HttpStatus status = HttpStatus.OK;

    /**
     * Content-Type value
     */
    protected String contentType;

    /**
     * Content-Length value, -1 means unset
     */
    protected long contentLength = -1;

    /**
     * Character encoding
     */
    protected String characterEncoding;

    /**
     * Auto-commit flag (default true, framework commits after handler returns)
     */
    protected boolean autoCommit = true;

    /**
     * Whether response headers have been sent to the client
     */
    protected boolean headersSent;

    /**
     * Whether the response has been fully committed
     */
    protected boolean committed;

    /**
     * Output stream cache (lazily created via {@link #outputStream()})
     */
    protected OutputStream os;

    /**
     * Constructor
     *
     * @param request request object (null disables certain methods like keep-alive/304)
     * @param ctx     channel context
     */
    protected HttpInternalResponse(HttpRequest request, ChannelContext ctx) {
        this.ctx = ctx;
        this.request = request;
        this.bodyBuf = HttpBuf.of(128);
    }

    /**
     * Returns whether the "Server" response header should be exposed,
     * resolved from the connection-level configuration (see {@link HttpOptions#EXPOSE_SERVER_HEADER}).
     */
    protected final boolean exposeServerHeader() {
        return ctx.option(HttpOptions.EXPOSE_SERVER_HEADER);
    }

    /**
     * Returns the body memory threshold in bytes, resolved from the connection-level
     * configuration (see {@link HttpOptions#BODY_MEMORY_THRESHOLD}).
     * <p>When buffered body data would exceed this threshold, the data is flushed to the channel
     * instead of being held in memory.</p>
     */
    protected final int bodyMemoryThreshold() {
        return ctx.option(HttpOptions.BODY_MEMORY_THRESHOLD);
    }

    /**
     * Returns whether GZIP auto-compression is enabled globally,
     * resolved from the connection-level configuration (see {@link HttpOptions#GZIP}).
     */
    protected final boolean gzipEnabled() {
        return ctx.option(HttpOptions.GZIP);
    }

    /**
     * Returns the minimum body size in bytes required before GZIP auto-compression is applied,
     * resolved from the connection-level configuration (see {@link HttpOptions#GZIP_MIN_SIZE}).
     */
    protected final int gzipMinSize() {
        return ctx.option(HttpOptions.GZIP_MIN_SIZE);
    }

    /**
     * Returns the shared, non-copying "Date" header line (37 bytes); the value
     * lives at offset 6, length 29. Caller must not mutate the array.
     */
    protected final byte[] getCurrentDateHeaderLineBytes() {
        return HttpDate.getCurrentDateHeaderLineBytes();
    }

    // ==================== Status ====================

    @Override
    public final void setStatus(HttpStatus status) {
        this.status = status;
    }

    @Override
    public final HttpStatus getStatus() {
        return status;
    }

    @Override
    public final HttpResponse status(HttpStatus s) {
        setStatus(s);
        return this;
    }

    @Override
    public final HttpResponse status(int code) {
        setStatus(HttpStatus.of(code));
        return this;
    }

    /**
     * Set status and write status text to body buffer
     *
     * @param s HTTP status
     */
    @Override
    public final void setStatusAndText(HttpStatus s) {
        setStatus(s);
        bodyBuf.replace(s.text.getBytes());
    }

    // ==================== Content-Type ====================

    @Override
    public final void setContentType(String ct) {
        this.contentType = ct;
        if (ct != null) {
            applyCharsetFromContentType(ct);
        }
    }

    /**
     * Extract the charset parameter from a Content-Type value and sync it to the
     * character encoding, so that {@code body(String)} encodes with the declared
     * charset. e.g. {@code "application/json;charset=gbk"} sets encoding to "gbk".
     *
     * @param ct the Content-Type value (must be non-null)
     */
    protected final void applyCharsetFromContentType(String ct) {
        int idx = ct.indexOf(HttpHeaderValues.CHARSET);
        if (idx > -1) {
            int eq = ct.indexOf('=', idx);
            if (eq > -1) {
                int end = ct.indexOf(';', eq);
                String cs = ct.substring(eq + 1, end > -1 ? end : ct.length()).trim();
                if (!cs.isEmpty()) characterEncoding = cs;
            }
        }
    }

    @Override
    public final String getContentType() {
        return contentType;
    }

    @Override
    public final HttpResponse contentType(String ct) {
        setContentType(ct);
        return this;
    }

    // ==================== Content-Length ====================

    @Override
    public void setContentLength(long len) {
        this.contentLength = len;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public final HttpResponse contentLength(long len) {
        setContentLength(len);
        return this;
    }

    // ==================== Character Encoding ====================

    @Override
    public final void setCharacterEncoding(String ce) {
        this.characterEncoding = ce;
    }

    @Override
    public final String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public final int getBufferSize() {
        return ctx.getWriteBufferSize();
    }

    // ==================== Body ====================

    @Override
    public final HttpResponse body(byte[] body) {
        return body(body, 0, body.length);
    }

    @Override
    public final HttpResponse body(byte[] body, int offset, int len) {
        bodyBuf.replace(body, offset, len);
        return this;
    }

    @Override
    public final HttpResponse body(String body) {
        if (body == null) {
            bodyBuf.replace(new byte[0]);
            return this;
        }
        try {
            String charset = characterEncoding != null ? characterEncoding : "UTF-8";
            bodyBuf.replace(body.getBytes(charset));
        } catch (UnsupportedEncodingException e) {
            bodyBuf.replace(body.getBytes());
        }
        return this;
    }

    @Override
    public final void write(byte[] buf) throws IOException {
        write(buf, 0, buf.length);
    }

    // ==================== Chunked (H2 treats as no-op) ====================

    @Override
    public final HttpResponse chunked() {
        setChunked(true);
        return this;
    }

    @Override
    public final HttpResponse chunked(boolean c) {
        setChunked(c);
        return this;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public void setChunked(boolean chunked) {
    }

    @Override
    public void setChunkedEncoding() {
    }

    @Override
    public void removeChunkedEncoding() {
    }

    @Override
    public void writeChunked(byte[] data, int offset, int count) throws IOException {
        write(data, offset, count);
    }

    // ==================== Keep-Alive ====================

    @Override
    public final boolean isKeepAlive() {
        HttpVersion httpVersion = request.getHttpVersion();
        if (httpVersion == HttpVersion.HTTP_2) return true;

        String conn = request.getHeader(HttpHeaderNormalized.getConnection(), true);
        if (conn != null) return !conn.equalsIgnoreCase(HttpHeaderValues.CLOSE);
        return httpVersion == HttpVersion.HTTP_1_1;
    }

    @Override
    public void setKeepAlive(boolean keepAlive) {
    }

    // ==================== Last-Modified ====================

    @Override
    public void setLastModified(long millis) {
        addHeader(HttpHeaderNames.LAST_MODIFIED, HttpHeaderUtils.getDateHeaderValue(millis));
    }

    // ==================== Auto-Commit ====================

    @Override
    public final void setAutoCommit(boolean ac) {
        this.autoCommit = ac;
    }

    @Override
    public final boolean isAutoCommit() {
        return autoCommit;
    }

    // ==================== OutputStream ====================

    @Override
    public OutputStream outputStream() {
        if (os != null) return os;
        final HttpInternalResponse self = this;
        return os = new OutputStream() {
            public void write(int b) throws IOException {
                self.write(new byte[]{(byte) b});
            }

            public void write(byte[] b) throws IOException {
                self.write(b);
            }

            public void write(byte[] b, int off, int len) throws IOException {
                self.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                self.flush();
            }
        };
    }

    // ==================== Header methods ====================
    @Override
    public final void setHeader(String key, Serializable value) {
        removeHeader(key);
        addHeader(key, value);
    }

    /**
     * Write the header into the underlying store.
     * Both key and value are guaranteed to be non-null Strings (already
     * converted by the caller via String.valueOf).
     *
     * @param key   the header key, non-null String
     * @param value the header value, non-null String
     */
    protected abstract void doAddHeader(String key, String value);

    @Override
    public final void addHeader(String key, Serializable value) {
        validateHeaderBase(key = String.valueOf(key), value);
        validateHeaderByProtocol(key, value);
        doAddHeader(key, String.valueOf(value));
    }

    /**
     * Protocol-independent header validation: CRLF injection defense plus value type whitelist.
     */
    private void validateHeaderBase(String key, Serializable value) {
        if (key.indexOf('\r') > -1 || key.indexOf('\n') > -1) {
            throw new IllegalArgumentException("Header key must not contain CR or LF");
        }
        if (value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof CharSequence) {
            String valueStr = value.toString();
            if (valueStr.indexOf('\r') > -1 || valueStr.indexOf('\n') > -1) {
                throw new IllegalArgumentException("Header value must not contain CR or LF");
            }
            return;
        }
        throw new IllegalArgumentException("Header value only supports Number, Boolean or CharSequence");
    }

    /**
     * Protocol-specific header rules hook (e.g. HTTP/2 rejects pseudo-headers); no-op by default.
     */
    protected void validateHeaderByProtocol(String key, Serializable value) {
    }

    @Override
    public final HttpResponse header(String key, Serializable value) {
        addHeader(key, value);
        return this;
    }

    @Override
    public void removeHeader(String key, Serializable value) {
        removeHeader(key);
    }

    /**
     * Extract all values for the given key from a header map whose entry value
     * is either a single {@code String} or a {@code List<String>} (multi value).
     *
     * @param map the header map
     * @param key the header key, must already be normalized
     * @return the header values, or null if the key is absent
     */
    @SuppressWarnings("unchecked")
    protected static List<String> headerValues(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value.getClass() == String.class) {
            List<String> single = new ArrayList<>(1);
            single.add((String) value);
            return single;
        }
        return (List<String>) value;
    }

    /**
     * Extract the first value for the given key from a header map whose entry value
     * is either a single {@code String} or a {@code List<String>} (multi value).
     *
     * @param map the header map
     * @param key the header key, must already be normalized
     * @return the first header value, or null if the key is absent
     */
    @SuppressWarnings("unchecked")
    protected static String headerFirstValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value.getClass() == String.class) {
            return (String) value;
        }
        return ((List<String>) value).get(0);
    }

    // ==================== GZIP ====================

    /**
     * Check if the client supports gzip compression (via Accept-Encoding header)
     *
     * @return true if client supports gzip
     */
    @Override
    public final boolean isGzipSupported() {
        String acceptEncoding = request.getHeader(HttpHeaderNormalized.getAcceptEncoding(), true);
        return acceptEncoding != null && acceptEncoding.contains(HttpHeaderValues.GZIP);
    }

    // ==================== Lifecycle ====================

    /**
     * Complete the response (framework internal use).
     * <p>
     * Called by {@code HttpServerChannelHandler} after the business handler returns.
     * When autoCommit is true, attempts auto GZIP first, falls back to {@link #commit()};
     * when false, only flushes and resets the body buffer.
     *
     * @throws IOException if an I/O error occurs
     */
    public void complete() throws IOException {
        if (autoCommit) {
            if (!attemptAutoGzipAndSend()) {
                commit();
            }
        } else {
            flush();
            resetInternal();
        }
    }

    /**
     * Hand over response control to internal logic (framework internal use only).
     * <p>
     * After calling this method, the framework will skip auto-commit and response flushing
     * on handler return. The response content must be written directly via {@link #ctx}.
     * Used by proxy request forwarding, resource downloads, and other scenarios where
     * the response sending process is fully managed by internal logic.
     */
    public abstract void handover() ;

    /**
     * Reset the response body buffer to its initial state.
     * <p>
     * Called after response completion to clear buffered body data
     * and prepare for potential reuse. Does not affect headers or status.
     */
    protected void resetInternal() {
        bodyBuf.reset();
    }

    /**
     * Returns whether the response has been committed, i.e. its headers have been sent.
     */
    @Override
    public boolean isCommitted() {
        return headersSent;
    }

    /**
     * Reset the response to its initial state, clearing the status code, headers and
     * buffered body. Throws {@link IllegalStateException} if headers have already been sent.
     */
    @Override
    public void reset() {
        if (headersSent) {
            throw new IllegalStateException("Response has already been committed");
        }
        status = HttpStatus.OK;
        contentType = null;
        contentLength = -1;
        characterEncoding = null;
        committed = false;
        autoCommit = true;
        os = null;
        resetInternal();
    }

    /**
     * Clear only the buffered response body, keeping the status code and headers.
     * Throws {@link IllegalStateException} if headers have already been sent.
     */
    @Override
    public void resetBuffer() {
        if (headersSent) {
            throw new IllegalStateException("Response has already been committed");
        }
        bodyBuf.reset();
    }

    /**
     * Returns whether the response stream is in a corrupted state.
     * <p>
     * Base implementation always returns {@code false}.
     * Subclasses may override to track transmission failures.
     *
     * @return false (not corrupted) by default
     */
    @Override
    public boolean isCorrupted() {
        return false;
    }

    // ==================== SSE ====================

    private boolean sseHeadersSet;

    @Override
    public HttpResponse sse(String data) throws IOException {
        initSSE();
        write(("data: " + data + "\n\n").getBytes(Utils.UTF_8));
        flush();
        return this;
    }

    @Override
    public HttpResponse sse(String event, String data) throws IOException {
        return sse(event, data, null, -1);
    }

    @Override
    public HttpResponse sse(String event, String data, String id, long retry) throws IOException {
        initSSE();
        if (id != null) write(("id: " + id + "\n").getBytes(Utils.UTF_8));
        if (retry > 0) write(("retry: " + retry + "\n").getBytes(Utils.UTF_8));
        if (event != null) write(("event: " + event + "\n").getBytes(Utils.UTF_8));
        write(("data: " + data + "\n\n").getBytes(Utils.UTF_8));
        flush();
        return this;
    }

    public SseEmitter sseEmitter() throws IOException {
        initSSE();
        flush();
        return new SseEmitter(this, ctx);
    }

    private void initSSE() {
        if (!sseHeadersSet) {
            contentType(HttpHeaderValues.TEXT_EVENT_STREAM_UTF8);
            setHeader(HttpHeaderNormalized.getCacheControl(), HttpHeaderValues.NO_CACHE);
            chunked();
            sseHeadersSet = true;
        }
    }

    // ==================== GZIP utilities ====================

    /**
     * GZIP compress byte array data
     *
     * @param data original data
     * @return compressed data
     * @throws IOException if compression fails
     */
    protected final byte[] gzipCompress(byte[] data) throws IOException {
        return gzipCompress(data, 0, data == null ? 0 : data.length);
    }

    /**
     * GZIP compress byte array with offset and length
     *
     * @param data   original data array
     * @param offset starting offset
     * @param len    length to compress
     * @return compressed data
     * @throws IOException if compression fails
     */
    protected final byte[] gzipCompress(byte[] data, int offset, int len) throws IOException {
        if (len == 0) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream((len >> 2) + 32);
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos, 8192)) {
            gzip.write(data, offset, len);
            gzip.finish();
        }
        return bos.toByteArray();
    }

    /**
     * Check if the given MIME type should be compressed
     *
     * @param mimeType the MIME type to check
     * @return true if the MIME type should be compressed, false otherwise
     */
    protected final boolean shouldCompressMimeType(String mimeType) {
        if (mimeType == null) return false;
        // Compressible MIME types
        return mimeType.startsWith("text/") ||
                mimeType.contains("json") ||
                mimeType.contains("javascript") ||
                mimeType.contains("xml") ||
                mimeType.contains("css");
    }

    /**
     * Generate ETag string in format "{hexLastModified}-{hexFileSize}"
     *
     * @param fileSize     file size in bytes
     * @param lastModified file last modified timestamp
     * @return ETag string (quoted)
     */
    protected final String generateETag(long fileSize, long lastModified) {
        return "\"" + Long.toHexString(lastModified) + "-" + Long.toHexString(fileSize) + "\"";
    }

    // ==================== Auto GZIP ====================

    /**
     * Attempt automatic GZIP compression on buffered body data and send.
     * <p>
     * Only applies when: global GZIP enabled, client supports gzip,
     * headers not sent, body size >= GZIP_MIN_SIZE. Delegates to
     *
     * @return true if auto-compress and send succeeded
     * @throws IOException if compression or send fails
     */
    protected abstract boolean attemptAutoGzipAndSend() throws IOException;

    /**
     * Check if auto-compress is applicable
     *
     * @return true if auto-compress is applicable
     */
    protected final boolean shouldApplyAutoGzip() {
        return gzipEnabled() && isGzipSupported() && !headersSent && bodyBuf.size() >= gzipMinSize();
    }

    // ==================== sendFile skeleton ====================

    /**
     * Resolve MIME type from filename
     *
     * @param filename the filename
     * @return MIME type string, defaults to application/octet-stream
     */
    protected final String resolveMimeType(String filename) {
        return HttpHeaderUtils.getMimeTypeByFilename(filename, HttpHeaderValues.APPLICATION_OCTET_STREAM);
    }

    /**
     * Check conditional request (304 Not Modified).
     * <p>
     * Supports both ETag (If-None-Match) and Last-Modified (If-Modified-Since) validation.
     * If-None-Match takes precedence over If-Modified-Since (RFC 7232 §6).
     * Returns true if 304 was sent, caller should return immediately.
     *
     * @param fileSize     file size in bytes
     * @param lastModified file last modified timestamp
     * @return true if 304 Not Modified was sent
     * @throws IOException if an I/O error occurs
     */
    protected final boolean checkNotModified(long fileSize, long lastModified) throws IOException {
        String ifNoneMatch = request.getHeader(HttpHeaderNormalized.getIfNoneMatch(), true);
        if (ifNoneMatch != null) {
            String hexFileSize = Long.toHexString(fileSize);
            String hexLastModified = Long.toHexString(lastModified);
            int hexLen = hexLastModified.length() + hexFileSize.length() + 3;
            if (ifNoneMatch.length() == hexLen
                    && ifNoneMatch.charAt(0) == '"'
                    && ifNoneMatch.charAt(hexLen - 1) == '"'
                    && ifNoneMatch.charAt(hexLastModified.length() + 1) == '-'
                    && ifNoneMatch.regionMatches(1, hexLastModified, 0, hexLastModified.length())
                    && ifNoneMatch.regionMatches(hexLastModified.length() + 2, hexFileSize, 0, hexFileSize.length())) {
                sendNotModified(ifNoneMatch, null);
                return true;
            }
            return false;
        }

        String ifModifiedSince = request.getHeader(HttpHeaderNormalized.getIfModifiedSince(), true);
        if (ifModifiedSince != null && ifModifiedSince.equals(HttpHeaderUtils.getDateHeaderValue(lastModified))) {
            sendNotModified(null, ifModifiedSince);
            return true;
        }

        return false;
    }

    /**
     * Send 304 Not Modified response
     *
     * @param etag            ETag value (written to ETag header if non-null)
     * @param lastModifiedStr Last-Modified value (used when etag is null)
     * @throws IOException if an I/O error occurs
     */
    private void sendNotModified(String etag, String lastModifiedStr) throws IOException {
        status(HttpStatus.NOT_MODIFIED);
        if (etag != null) addHeader(HttpHeaderNames.ETAG, etag);
        else addHeader(HttpHeaderNames.LAST_MODIFIED, lastModifiedStr);
        setContentLength(0);
        commit();
    }

    /**
     * Read entire file content into a byte array
     *
     * @param file     the file
     * @param fileSize file size
     * @return file content bytes
     * @throws IOException if read fails
     */
    protected byte[] readFileContent(File file, int fileSize) throws IOException {
        byte[] content = new byte[fileSize];
        try (FileInputStream fis = new FileInputStream(file)) {
            int off = 0, read;
            while ((read = fis.read(content, off, fileSize - off)) > 0) off += read;
        }
        return content;
    }

    /**
     * Send a file to the client.
     * <p>
     * Subclasses should override {@link #sendFile(File, boolean, int)} to provide custom behavior.
     *
     * @param file the file to send
     * @throws IOException if an I/O error occurs
     */
    @Override
    public final void sendFile(File file) throws IOException {
        sendFile(file, true, -1);
    }

    /**
     * Send a file with max-age cache control.
     *
     * @param file         the file to send
     * @param cacheEnabled whether to enable cache negotiation
     * @param maxAge       maxAge in seconds; &le;0 means no-cache
     * @throws IOException if an I/O error occurs
     */
    @Override
    public final void sendFile(File file, boolean cacheEnabled, int maxAge) throws IOException {
        if (file == null) { notFound(); return; }
        sendFile(file, cacheEnabled, maxAge > 0 ? "public, max-age=" + maxAge : "public, no-cache", resolveMimeType(file.getName()));
    }

    /**
     * Send a file with explicit cache control and a pre-resolved MIME type.
     * <p>
     * When {@code cacheControl} is non-null it is used as the {@code Cache-Control}
     * header; an empty string skips the header entirely; {@code null} defaults to
     * {@code "public, no-cache"}.
     */
    public final void sendFile(File file, boolean cacheEnabled, String cacheControl, String mimeType) throws IOException {
        if (headersSent || committed) return;
        if (file == null || !file.exists() || file.isDirectory() /* || !file.canRead() */) {
            notFound();
            return;
        }
        status(HttpStatus.OK);
        setContentType(mimeType);
        long fileSize = file.length();
        if (cacheEnabled) {
            long lastModified = file.lastModified();
            if (checkNotModified(fileSize, lastModified)) return;
            addCacheHeaders(fileSize, lastModified);
            if (cacheControl != null && !cacheControl.isEmpty()) {
                addHeader(HttpHeaderNames.CACHE_CONTROL, cacheControl);
            }
        }
        if (fileSize == 0) {
            setContentLength(0);
            commit();
            return;
        }

        // Check if we should apply GZIP compression for this file
        boolean shouldCompress = gzipEnabled() &&
                isGzipSupported() &&
                fileSize >= gzipMinSize() &&
                shouldCompressMimeType(mimeType);

        // Send file
        sendFile0(file, fileSize, mimeType, shouldCompress);
    }

    protected void addCacheHeaders(long fileSize, long lastModified) {
    }

    protected abstract void sendFile0(File file, long fileSize, String mimeType, boolean shouldCompress) throws IOException;

    /**
     * Send 404 Not Found response.
     *
     * @throws IOException if an I/O error occurs
     */
    protected abstract void notFound() throws IOException;
}
