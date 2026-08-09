package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstract base class for HTTP response implementations
 * Contains common constants and shared functionality
 *
 * @since 2024-1-23
 * @author wangyc
 */
abstract class HttpGenerativeResponse extends HttpInternalResponse {

    // ==================== CONST'S ====================
    /**
     * Response not started yet
     */
    protected static final int STATE_NOT_STARTED = 0;
    /**
     * Response completed successfully
     */
    protected static final int STATE_COMPLETED = 1;
    /**
     * Response corrupted - data transmission incomplete, connection may be unstable
     */
    protected static final int STATE_CORRUPTED = 2;

    protected static final byte SPACE = ' ';
    protected static final byte[] COLON_SPACE_BYTES = ": ".getBytes();
    protected static final byte[] CRLF_BYTES = "\r\n".getBytes();
    protected static final byte[] SEMICOLON_CHARSET_BYTES = ";charset=".getBytes();
    protected static final byte[] COMMA_SPACE_BYTES = ", ".getBytes();
    protected static final byte[] CHUNKED_END_MARKER = "0\r\n\r\n".getBytes();

    /**
     * Buffer size for GZIP compression and streaming I/O operations
     */
    static final int GZIP_BUFFER_SIZE = 8192;

    // ==================== Final FIELDS ====================
    protected final HttpVersion httpVersion;
    final Map<String, Object> headers;
    protected final HttpBuf headerBuf;
    protected boolean chunked;
    protected boolean hasExplicitContentType;
    protected boolean hasExplicitContentLength;

    protected HttpGenerativeResponse(HttpRequest request, ChannelContext ctx) {
        super(request, ctx);
        this.httpVersion = request.getHttpVersion();
        this.headers = new HashMap<String, Object>(8);
        this.headerBuf = HttpBuf.of(192);
    }

    /**
     * Get connection header value for response - mirror request or use default
     */
    protected String getConnectionHeaderValue() {
        // Mirror request's Connection header if present
        String requestConnection = request.getHeader(HttpHeaderNormalized.getConnection(), true);
        if (requestConnection != null) {
            return requestConnection;
        }

        // Default based on HTTP version
        return httpVersion == HttpVersion.HTTP_1_1 ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE;
    }

    /**
     * Set header directly (overwrite mode, no multi-value merge)
     *
     * @param key   the header key
     * @param value the header value
     */
    protected final void putHeader(String key, Serializable value) {
        headers.put(HttpHeaderUtils.normalizeHeaderKey(String.valueOf(key)), String.valueOf(value));
    }

    protected final void directlyPutHeader(String key, Serializable value) {
        headers.put(key, String.valueOf(value));
    }

    /**
     * Get header directly without key normalization
     *
     * @param key the normalized header key
     * @return header value or null if not exists
     */
    protected final String directlyGetHeader(String key) {
        Object value = headers.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getHeaders(String name) {
        return headerValues(headers, HttpHeaderUtils.normalizeHeaderKey(String.valueOf(name)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getHeaderNames() {
        return headers.keySet();
    }

    /**
     * Write single header line
     */
    protected final void writeHeaderLine(String key, String value) {
        headerBuf.writeISO_8859_1String(key);
        headerBuf.write(COLON_SPACE_BYTES);
        headerBuf.writeISO_8859_1String(value);
        headerBuf.write(CRLF_BYTES);
    }

    /**
     * Write multiple independent header lines
     */
    protected void writeMultipleHeaderLines(String key, List<String> values) {
        for (String value : values) {
            writeHeaderLine(key, value);
        }
    }

    /**
     * Write comma-separated header values
     */
    protected void writeCommaSeparatedHeader(String key, List<String> values) {
        headerBuf.writeISO_8859_1String(key);
        headerBuf.write(COLON_SPACE_BYTES);
        for (int i = 0; i < values.size(); ++i) {
            if (i > 0) {
                headerBuf.write(COMMA_SPACE_BYTES);
            }
            headerBuf.writeISO_8859_1String(values.get(i));
        }
        headerBuf.write(CRLF_BYTES);
    }

    /**
     * Write all default HTTP response headers directly
     * Including standard headers required by RFC 7231 such as Date, Server and Connection
     */
    protected final void writeDefaultHeaders() {
        // Write Date header first (RFC 7231 requirement) if not exists
        if (!headers.containsKey(HttpHeaderNormalized.getDate())) {
            headerBuf.write(HttpDate.getCurrentDateHeaderLineBytes());
        }

        // Write Server header if not exists and exposure is enabled
        if (HttpConf.EXPOSE_SERVER_HEADER && !headers.containsKey(HttpHeaderNormalized.getServer())) {
            headerBuf.write(HttpHeaderUtils.getServerHeaderLineBytes());
        }

        // Write Connection header if not exists
        String connectionKey = HttpHeaderNormalized.getConnection();
        if (!headers.containsKey(HttpHeaderNormalized.getConnection())) {
            writeHeaderLine(connectionKey, getConnectionHeaderValue());
        }
    }

    /**
     * Flush body buffer content as a single chunk with chunked encoding and flush.
     * Delegates to {@link #flushBodyAsChunk(boolean)} with {@code networkFlush=true}.
     *
     * @throws IOException if an I/O error occurs during writing
     */
    protected final void flushBodyAsChunk() throws IOException {
        flushBodyAsChunk(true);
    }

    /**
     * Flush body buffer content as a single chunk with chunked encoding.
     *
     * @param flush whether to flush the channel after writing
     * @throws IOException if an I/O error occurs during writing
     */
    void flushBodyAsChunk(boolean flush) throws IOException {
        writeChunk(bodyBuf.byteBuffer(), bodyBuf.size(), flush);
        bodyBuf.clear();
    }

    /**
     * Write data as a chunk in chunked transfer encoding format.
     *
     * @param buf          the ByteBuffer containing the chunk data
     * @param length       the length of data to write
     * @param flush whether to flush the channel after writing
     * @throws IOException if an I/O error occurs
     */
    void writeChunk(ByteBuffer buf, int length, boolean flush) throws IOException {
        byte[] hexBuf = headerBuf.getBuf();
        int hlen = Utils.intToHexBytes(length, hexBuf, 0);
        ctx.write(ByteBuffer.wrap(hexBuf, 0, hlen));
        ctx.write(CRLF_BYTES);
        ctx.write(buf);
        ctx.write(CRLF_BYTES);
        if (flush) ctx.flush();
    }

    /**
     * Write data as a chunk in chunked transfer encoding format with immediate flush.
     *
     * @param buf    the ByteBuffer containing the chunk data
     * @param length the length of data to write
     * @throws IOException if an I/O error occurs
     */
    protected final void writeFlushChunk(ByteBuffer buf, int length) throws IOException {
        writeChunk(buf, length, true);
    }

    @Override
    public final HttpVersion getHttpVersion() {
        return httpVersion;
    }

    // ==================== H1-specific overrides ====================

    @Override
    public final void setKeepAlive(boolean keepAlive) {
        String connectionValue = keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE;
        directlyPutHeader(HttpHeaderNormalized.getConnection(), connectionValue);
    }

    @Override
    public final void setLastModified(long lastModifiedMillis) {
        directlyPutHeader(HttpHeaderNormalized.getLastModified(), HttpHeaderUtils.getDateHeaderValue(lastModifiedMillis));
    }

    /**
     * Streaming GZIP compress and send as chunked response
     * Used for large files to prevent OOM
     *
     * @param fis the input stream to compress
     * @throws IOException if an I/O error occurs
     */
    void streamingGzipCompress(java.io.InputStream fis) throws IOException {
        java.util.zip.GZIPOutputStream gzip = null;
        try {
            gzip = new java.util.zip.GZIPOutputStream(new ChunkedOutputStream(), GZIP_BUFFER_SIZE);
            byte[] buffer = new byte[GZIP_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) > 0) {
                gzip.write(buffer, 0, bytesRead);
            }
            gzip.finish();
        } finally {
            if (gzip != null) {
                gzip.close();
            }
        }
    }

    /**
     * OutputStream wrapper that writes data as chunks immediately
     * Used for streaming GZIP compression with chunked encoding
     */
    private class ChunkedOutputStream extends java.io.OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > 0) {
                writeFlushChunk(ByteBuffer.wrap(b, off, len), len);
            }
        }

        @Override
        public void flush() throws IOException {
            ctx.flush();
        }
    }

    // ==================== H1 header construction (moved from HttpDefaultResponse) ====================

    /**
     * Ensure HTTP headers are sent to the client
     * This method handles the header sending logic and should only be called once
     */
    @Override
    protected void resetInternal() {
        super.resetInternal();
        headers.clear();
        headerBuf.reset();
        chunked = false;
        hasExplicitContentType = false;
        hasExplicitContentLength = false;
    }

    protected void ensureHeadersSent() throws IOException {
        if (!headersSent) {
            writeHeaders();
            ctx.write(headerBuf.byteBuffer());
            headerBuf.clear();
            headersSent = true;
        }
    }

    void writeHeaders() {
        // Write status line first
        writeStatusLine();

        // add default headers
        if (HttpConf.WRITE_DEFAULT_HEADERS) {
            writeDefaultHeaders();
        }

        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value.getClass() == String.class) {
                writeHeaderLine(key, (String) value);
            } else {
                // Multi-value header (List<String>, framework controlled)
                List<String> valueList = (List<String>) value;
                if (HttpHeaderUtils.isCommaSeparated()) {
                    writeCommaSeparatedHeader(key, valueList);
                } else {
                    writeMultipleHeaderLines(key, valueList);
                }
            }
        }
        if (!hasExplicitContentType && contentType != null && !contentType.isEmpty()) {
            writeContentTypeHeader();
        }

        if (!chunked && !hasExplicitContentLength) {
            int bodySize = bodyBuf.size();
            if (contentLength < bodySize) {
                this.contentLength = bodySize;
            }
            writeContentLengthHeader();
        }
        headerBuf.write(CRLF_BYTES);
    }

    /**
     * Write status line
     */
    private void writeStatusLine() {
        headerBuf.writeISO_8859_1String(httpVersion.toString());
        headerBuf.write(SPACE);
        headerBuf.writeISO_8859_1String(String.valueOf(status.code));
        headerBuf.write(SPACE);
        headerBuf.writeISO_8859_1String(status.text);
        headerBuf.write(CRLF_BYTES);
    }

    /**
     * Write Content-Type header
     */
    private void writeContentTypeHeader() {
        headerBuf.writeISO_8859_1String(HttpHeaderNormalized.getContentType());
        headerBuf.write(COLON_SPACE_BYTES);
        headerBuf.writeISO_8859_1String(contentType);
        if (characterEncoding != null && !characterEncoding.isEmpty() && !contentType.contains("charset")) {
            headerBuf.write(SEMICOLON_CHARSET_BYTES);
            headerBuf.writeISO_8859_1String(characterEncoding);
        }
        headerBuf.write(CRLF_BYTES);
    }

    /**
     * Write Content-Length header
     */
    private void writeContentLengthHeader() {
        headerBuf.writeISO_8859_1String(HttpHeaderNormalized.getContentLength());
        headerBuf.write(COLON_SPACE_BYTES);
        headerBuf.writeISO_8859_1String(String.valueOf(contentLength));
        headerBuf.write(CRLF_BYTES);
    }

}
