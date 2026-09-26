package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.util.DirectBufferPool;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP response design implementation
 *
 * @since 2024-1-23
 * @author wangyc
 */
public class HttpDefaultResponse extends HttpGenerativeResponse {

    static final Log log = LogFactory.getLog(HttpDefaultResponse.class);

    private static final byte[] _EARLY_HINTS_MID = " 103 Early Hints\r\nLink: ".getBytes();
    private static final byte[] _CRLF_CRLF = "\r\n\r\n".getBytes();

    private long flushCount;
    int responseState = STATE_NOT_STARTED;

    /**
     * Main constructor with request context for keep-alive support
     * HTTP version is derived from the request
     *
     * @param ctx     the channel context
     * @param request the HTTP request (non-null)
     */
    protected HttpDefaultResponse(HttpRequest request, ChannelContext ctx) {
        super(request, ctx);
        this.status = HttpStatus.OK;
        // No keepAlive instance variable needed - will determine dynamically when writing headers
    }

    // ==================== Private Helper Methods ====================

    /**
     * Parse and validate Content-Length value from header
     *
     * @param value the header value to parse
     * @return parsed content length value
     * @throws IllegalArgumentException if value is invalid or negative
     * @throws IllegalStateException    if chunked encoding is enabled
     */
    long toContentLength(Serializable value) {
        if (chunked) {
            throw new IllegalStateException("Cannot set Content-Length header when chunked encoding is enabled");
        }
        long length;
        if (value instanceof Integer || value instanceof Long) {
            length = ((Number) value).longValue();
        } else {
            try {
                length = Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid Content-Length value: " + value + ", must be a valid number");
            }
        }

        if (length < 0) {
            throw new IllegalArgumentException("Content-Length must be non-negative, got: " + length);
        }
        return length;
    }

    /**
     * Update CONTENT-related status flags
     *
     * @param normalizedKey normalized header key
     * @param value         the header value (used for Content-Length validation)
     * @param isAdding      true means adding header, false means removing header
     * @return true if header is Content-Length or Content-Type (use single-value overwrite mode), false otherwise
     */
    protected boolean updateContentFlags(String normalizedKey, Serializable value, boolean isAdding) {
        if (normalizedKey.startsWith(HttpHeaderNormalized.getContentPrefix())) {
            if (headersSent) {
                return false;
            }

            if (normalizedKey.equals(HttpHeaderNormalized.getContentLength())) {
                if (isAdding) {
                    this.contentLength = toContentLength(value);
                    hasExplicitContentLength = true;
                } else {
                    this.contentLength = 0;
                    hasExplicitContentLength = false;
                }
                return true;
            } else if (normalizedKey.equals(HttpHeaderNormalized.getContentType())) {
                hasExplicitContentType = isAdding;
                if (isAdding) applyCharsetFromContentType(String.valueOf(value));
                return true;
            }
        }
        return false;
    }

    // ==================== Public Methods ====================

    @Override
    @SuppressWarnings("unchecked")
    protected void doAddHeader(String key, String value) {
        String normalizedKey = HttpHeaderUtils.normalizeHeaderKey(key);
        boolean overwriteMode = updateContentFlags(normalizedKey, value, true);
        Object existingValue;
        if (overwriteMode || ((existingValue = headers.get(normalizedKey)) == null)) {
            headers.put(normalizedKey, value);
        } else if (existingValue.getClass() == String.class) {
            List<String> valueList = new ArrayList<>();
            valueList.add((String) existingValue);
            valueList.add(value);
            headers.put(normalizedKey, valueList);
        } else {
            ((List<String>) existingValue).add(value);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public String getHeader(String key) {
        Object value = headers.get(HttpHeaderUtils.normalizeHeaderKey(String.valueOf(key)));
        if (value == null || value.getClass() == String.class) {
            return (String) value;
        }
        // Multi-value header: List type always has at least 2 elements
        List<String> valueList = (List<String>) value;
        return valueList.get(0);
    }

    /**
     * Remove header with given key
     *
     * @param key the header key
     */
    @Override
    public void removeHeader(String key) {
        String normalizedKey = HttpHeaderUtils.normalizeHeaderKey(String.valueOf(key));
        updateContentFlags(normalizedKey, null, false);
        headers.remove(normalizedKey);
    }

    /**
     * Remove specific value from header with given key
     * If the key corresponds to a List and only one element remains after removal,
     * it will be automatically converted to String type.
     * If the List becomes empty, the key will be completely removed.
     *
     * @param key   the header key
     * @param value the header value to remove
     */
    @SuppressWarnings("unchecked")
    public void removeHeader(String key, Serializable value) {
        String normalizedKey = HttpHeaderUtils.normalizeHeaderKey(String.valueOf(key));
        String stringValue = String.valueOf(value);

        Object existingValue = headers.get(normalizedKey);
        if (existingValue == null) {
            return;
        }

        if (existingValue.getClass() == String.class) {
            if (existingValue.equals(stringValue)) {
                updateContentFlags(normalizedKey, null, false);
                headers.remove(normalizedKey);
            }
        } else {
            // Multi-value header: List always has at least 2 elements before removal
            List<String> valueList = (List<String>) existingValue;
            valueList.remove(stringValue);

            // After removal, convert to String if only 1 element remains
            if (valueList.size() == 1) {
                headers.put(normalizedKey, valueList.get(0));
            }
        }
    }

    @Override
    public void setContentLength(long contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("Content-Length must be non-negative, got: " + contentLength);
        }

        if (chunked || headersSent) {
            return;
        }

        this.contentLength = contentLength;
    }

    /**
     * Get chunked transfer encoding status
     *
     * @return true if chunked encoding is enabled, false otherwise
     */
    @Override
    public boolean isChunked() {
        return chunked;
    }

    /**
     * Set chunked transfer encoding status
     *
     * @param chunked true to enable chunked encoding, false to disable
     * @throws IllegalStateException if Content-Length is already set when enabling chunked
     */
    @Override
    public void setChunked(boolean chunked) {
        if (headersSent) return;
        if (chunked) {
            setChunkedEncoding();
        } else {
            removeChunkedEncoding();
        }
    }

    /**
     * Quick method to set Transfer-Encoding: chunked header
     * Automatically enables chunked transfer encoding mode
     *
     * @throws IllegalStateException if Content-Length is already set
     */
    @Override
    public void setChunkedEncoding() {
        if (headersSent) return;
        if (hasExplicitContentLength) {
            throw new IllegalStateException("Cannot set chunked encoding when Content-Length is already set");
        }
        this.chunked = true;
        directlyPutHeader(HttpHeaderNormalized.getTransferEncoding(), HttpHeaderValues.CHUNKED);
    }

    /**

     * Quick method to remove chunked transfer encoding
     * Automatically disables chunked mode and removes related headers
     */
    @Override
    public void removeChunkedEncoding() {
        if (headersSent) return;
        this.chunked = false;
        headers.remove(HttpHeaderNormalized.getTransferEncoding());
    }

    @Override
    protected void addCacheHeaders(long fileSize, long lastModified) {
        // Set Last-Modified header for caching
        directlyPutHeader(HttpHeaderNormalized.getLastModified(), HttpHeaderUtils.getDateHeaderValue(lastModified));
        // Set ETag header for caching
        directlyPutHeader(HttpHeaderNormalized.getETag(), generateETag(fileSize, lastModified));
    }

    @Override
    protected void sendFile0(File file, long fileSize, String mimeType, boolean shouldCompress) throws IOException {
        if (shouldCompress) {
            try {
                compressAndSendFile(file);
            } catch (IOException e) {
                log.warn("Failed to compress file {}: {}", file, e.getMessage());
                responseState = STATE_CORRUPTED;
                throw e;
            }
        } else {
            // Original logic for non-compressed file sending
            setContentLength(fileSize);

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long estimatedTotalSize = fileSize + (ctx.option(HttpOptions.WRITE_DEFAULT_HEADERS) ? 141 : 56) + mimeType.length();
                if (ctx.isSSL() || estimatedTotalSize < ctx.getWriteBufferSize()) {
                    // SSL or small file: use buffered write, single flush at end
                    ensureHeadersSent();
                    sendFileBuffered(raf, fileSize);
                } else {
                    // Large non-SSL file: use zero-copy transfer, flush headers first
                    flush();
                    sendFileZeroCopy(raf, fileSize);
                }
            } catch (IOException e) {
                log.warn("Failed to send file {}: {}", file, e.getMessage());
                responseState = STATE_CORRUPTED;
                throw e;
            }
        }

        // Mark response as completed
        responseState = STATE_COMPLETED;
        resetInternal();
    }

    /**
     * Compress and send the file content with GZIP compression
     * For large files, uses streaming compression with chunked encoding to prevent OOM
     *
     * @param file the file to compress and send
     * @throws IOException if an I/O error occurs
     */
    private void compressAndSendFile(File file) throws IOException {
        final long fileSize = file.length();

        // Set Content-Encoding header for all compressed responses
        directlyPutHeader(HttpHeaderNormalized.getContentEncoding(), HttpHeaderValues.GZIP);

        // For small files, use in-memory compression
        if (fileSize <= bodyMemoryThreshold()) {
            byte[] fileContent = readFileContent(file, (int) fileSize);
            byte[] compressedContent = gzipCompress(fileContent);
            setContentLength(compressedContent.length);
            bodyBuf.replace(compressedContent);
            commit();
            return;
        }

        // For large files, use streaming compression with chunked encoding
        setChunkedEncoding();
        ensureHeadersSent();

        try (FileInputStream fis = new FileInputStream(file)) {
            streamingGzipCompress(fis);
        }

        // Send chunked end marker
        ctx.write(CHUNKED_END_MARKER);
        ctx.flush();
    }


    /**
     * Send file using zero-copy transfer (FileChannel.transferTo)
     * Only works for non-SSL connections
     *
     * @param raf      RandomAccessFile to read from
     * @param fileSize file size in bytes
     * @throws IOException if transfer fails
     */
    private void sendFileZeroCopy(RandomAccessFile raf, final long fileSize) throws IOException {
        FileChannel fileChannel = raf.getChannel();
        long position = 0;
        final long maxTransfer = (1L << 30) - 8;  // ~1GB, avoid platform transferTo limit
        final long writeTimeMs = ctx.option(SocketOptions.WRITE_TIMEOUT_MS);
        final long writeTimeoutMs = writeTimeMs > 0 ? writeTimeMs : 30000;
        int zeroTransferredCount = 0;
        while (position < fileSize) {
            long transferLength = Math.min(maxTransfer, fileSize - position);
            long transferred = fileChannel.transferTo(position, transferLength, ctx.channel());
            if (transferred > 0) {
                zeroTransferredCount = 0;
                position += transferred;
                continue;
            }
            if (++zeroTransferredCount >= 2048) { // transferTo returned 0: buffer full. Wait for writable
                throw new IOException("File transfer failed: transferred 0 bytes after 2048 retries, remain: " + (fileSize - position) + " bytes");
            }
            if (!ctx.waitForWrite(writeTimeoutMs)) { // wait for writable (sleep/OP_WRITE)
                throw new IOException("File transfer timeout after " + writeTimeoutMs + "ms waiting, remain: " + (fileSize - position) + " bytes");
            }
        }
        ctx.flush();
    }

    /**
     * Send file using buffered write (for SSL/TLS connections)
     * Reads file in chunks and writes through ctx.write()
     *
     * @param raf      RandomAccessFile to read from
     * @param fileSize file size in bytes
     * @throws IOException if transfer fails
     */
    private void sendFileBuffered(RandomAccessFile raf, long fileSize) throws IOException {
        FileChannel fileChannel = raf.getChannel();
        DirectBufferPool.PooledBuffer pooledBuffer =
                DirectBufferPool.acquireBuffer();
        ByteBuffer buffer = pooledBuffer.get();
        try {
            long position = 0;
            while (position < fileSize) {
                buffer.clear();
                int read = fileChannel.read(buffer);
                if (read == -1) {
                    break;
                }
                buffer.flip();
                ctx.write(buffer);
                position += read;
            }
            ctx.flush();
        } finally {
            pooledBuffer.release();
        }
    }

    /**
     * Send 404 Not Found response and complete transmission
     */
    protected void notFound() throws IOException {
        status(HttpStatus.NOT_FOUND);
        bodyBuf.clear();
        commit();
        os = null;
        bodyBuf.reset();
    }

    /**
     * Flush buffered response data to the network.
     * Delegates to {@link #writeBuffered(boolean)} with {@code flush=true}.
     */
    @Override
    public void flush() throws IOException {
        writeBuffered(true);
    }

    /**
     * Write headers and body to the underlying channel buffer.
     * When {@code flush} is true, additionally calls {@link ChannelContext#flush()}
     * to push buffered data to the network.
     *
     * @param flush whether to perform a network-level flush
     */
    private void writeBuffered(boolean flush) throws IOException {
        if (isSilentlyUnavailable()) {
            return;  // silently ignore
        }
        ensureHeadersSent();

        // Send body
        if (bodyBuf.hasRemaining()) {
            if (!chunked) {
                if (contentLength >= 0) {
                    long totalSize = flushCount + bodyBuf.size();
                    if (totalSize > contentLength) {
                        log.warn("Data truncated: total size {} exceeds Content-Length {}, sending only {} bytes",
                                totalSize, contentLength, contentLength - flushCount);
                        int bytesToTruncate = (int) (totalSize - contentLength);
                        bodyBuf.deleteTail(bytesToTruncate);
                    }
                }
                flushCount += bodyBuf.size();
                ctx.write(bodyBuf.byteBuffer());
                bodyBuf.clear();
            } else {
                flushBodyAsChunk(flush);
            }
        }

        if (flush) {
            ctx.flush();
        }
    }

    /**
     * Commit the response and complete transmission
     * For chunked encoding, sends the final chunk marker (0\r\n\r\n)
     * This method should be called to properly finish the HTTP response
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void commit() throws IOException {
        if (isSilentlyUnavailable()) {
            return;  // silently ignore, prevent duplicate submission
        }
        writeBuffered(false);
        // For chunked encoding, send end marker (outer decode() line 111 handles the final flush)
        if (chunked) {
            ctx.write(CHUNKED_END_MARKER);
        }
        responseState = STATE_COMPLETED;
        resetInternal();
    }

    @Override
    protected void resetInternal() {
        super.resetInternal();
        os = null;
    }

    /**
     * Buffers into bodyBuf, or flushes and writes straight to the channel once the
     * threshold is exceeded, so bodyBuf stays bounded.
     */
    @Override
    public void write(byte[] bytes, int offset, int count) throws IOException {
        if (bytes == null || count <= 0)
            return;

        // If either buffer size or incoming data exceeds threshold, flush and write directly
        if (Math.max(bodyBuf.size(), count) > bodyMemoryThreshold()) {
            flush();

            if (chunked) {
                // For chunked encoding, write as a separate chunk
                writeFlushChunk(ByteBuffer.wrap(bytes, offset, count), count);
            } else {
                // For non-chunked, write directly
                ctx.write(ByteBuffer.wrap(bytes, offset, count));
                flushCount += count;
            }
        } else {
            bodyBuf.write(bytes, offset, count);
        }
    }

    /**
     * Emits one chunk immediately; null or non-positive count is ignored.
     */
    @Override
    public void writeChunked(byte[] data, int offset, int count) throws IOException {
        if (data == null) return;
        if (isSilentlyUnavailable()) {
            return;  // silently ignore
        }
        if (!chunked) {
            throw new IllegalStateException("Chunked encoding not enabled - call setChunked(true) or chunked() first");
        }
        if (count <= 0) return;  // empty data: skip after the chunked-enabled check

        // Ensure headers are sent before writing chunked data
        ensureHeadersSent();

        // Flush any remaining data in bodyBuf first
        if (bodyBuf.hasRemaining()) {
            flushBodyAsChunk();
        }

        // Write the new chunk data
        writeFlushChunk(ByteBuffer.wrap(data, offset, count), count);
    }

    @Override
    public void earlyHints(String linkHeader) throws IOException {
        if (isSilentlyUnavailable() || headersSent) return;
        // {httpVersion} 103 Early Hints\r\nLink: {linkHeader}\r\n\r\n
        HttpBuf buf = HttpBuf.of(96);
        buf.write(httpVersion.toString().getBytes());
        buf.write(_EARLY_HINTS_MID);
        buf.write(linkHeader.getBytes());
        buf.write(_CRLF_CRLF);
        ctx.writeFlush(buf.byteBuffer());
    }

    /**
     * Check if the response stream is corrupted
     * Returns true when response transmission failed and data may be incomplete
     * Upper layer should close the connection in this case
     *
     * @return true if response is corrupted, false otherwise
     */
    @Override
    public boolean isCorrupted() {
        return responseState == STATE_CORRUPTED;
    }

    /**
     * Hand over response control to internal logic (framework internal use only).
     * <p>
     * After calling this method, the framework will skip auto-commit and response flushing
     * on handler return. The response content must be written directly via the raw channel context.
     * Used by proxy request forwarding, resource downloads, and other scenarios where
     * the response sending process is fully managed by internal logic.
     */
    @Override
    public void handover() {
        responseState = STATE_COMPLETED;
    }

    /**
     * Check if response is silently unavailable (completed or corrupted)
     * Used to prevent further operations on finished responses
     *
     * @return true if response is silently unavailable, false otherwise
     */
    private boolean isSilentlyUnavailable() {
        return responseState != STATE_NOT_STARTED;
    }

    /**
     * Attempt to apply auto GZIP compression and send the response
     * Checks if response meets auto-compression criteria (enabled, client support, size threshold)
     * If conditions are met, compresses bodyBuf content, sets proper headers (Content-Encoding, Content-Length),
     * sends the compressed data, and marks response as completed.
     * If conditions are not met, does nothing and returns false to let caller handle normal commit.
     *
     * @return true if auto GZIP was applied and response sent, false otherwise
     * @throws IOException if compression or I/O error occurs
     */
    protected boolean attemptAutoGzipAndSend() throws IOException {
        boolean isShouldApplyAutoGzip = shouldApplyAutoGzip();
        if (isShouldApplyAutoGzip) {
            // Apply GZIP compression (zero-copy: directly access bodyBuf internal array)
            byte[] compressed = gzipCompress(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size());
            // Set Content-Encoding header
            directlyPutHeader(HttpHeaderNormalized.getContentEncoding(), HttpHeaderValues.GZIP);
            directlyPutHeader(HttpHeaderNormalized.getContentLength(), compressed.length);
            hasExplicitContentLength = true;
            ensureHeadersSent();
            ctx.write(compressed);
            ctx.flush();
            responseState = STATE_COMPLETED;
            resetInternal();
        }
        return isShouldApplyAutoGzip;
    }

}