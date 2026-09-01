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

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP/2 response implementation.
 * <p>
 * Sends response via HTTP/2 binary frames (HEADERS + DATA).
 * HPACK encoding is used for header compression.
 *
 * @author wangyc
 */
public class Http2Response extends HttpInternalResponse {

    static final String SERVER_VALUE = HTTPServer.SERVER + "/" + HTTPServer.VERSION;

    // Pre-encoded HPACK Never Indexed (D) header name references (RFC 7541 §6.2.3):
    //   ≤15: 0x10 | tableIndex
    //   >15: 0x1F (0001|1111, all 4 bits = 1 = extended) + varint(tableIndex - 15)
    private static final byte[] _H2_CONTENT_TYPE_PREFIX  = {0x1F, 0x10};             // D, name=content-type (idx 31, ext 16)
    private static final byte[] _H2_CONTENT_LENGTH_PREFIX = {0x1F, 0x0D};            // D, name=content-length (idx 28, ext 13)
    private static final byte[] _H2_DATE_PREFIX           = {0x1F, 0x12};            // D, name=date (idx 33, ext 18)
    private static final byte[] _H2_SERVER_PREFIX         = {0x1F, 0x27};            // D, name=server (idx 54, ext 39)

    // Pre-encoded HPACK prefix for 103 Early Hints:
    //   never-indexed ref to index 8 (":status") + "103" + never-indexed inline name "link"
    //   Hex: 18 03 31 30 33 10 04 6c 69 6e 6b
    private static final byte[] _H2_EARLY_HINTS_PREFIX = {
        0x18,                                    // never-indexed, name index 8 (":status")
        0x03,                                    // value length = 3
        0x31, 0x30, 0x33,                       // "103"
        0x10,                                    // never-indexed, inline name
        0x04,                                    // name length = 4
        0x6c, 0x69, 0x6e, 0x6b                  // "link"
    };

    final Http2Stream stream;
    final Map<String, Object> h2Headers = new HashMap<String, Object>();

    public Http2Response(Http2Request request, Http2Stream stream, ChannelContext ctx) {
        super(request, ctx);
        this.stream = stream;
    }

    // ==================== HttpResponse interface ====================

    @Override
    public HttpVersion getHttpVersion() {
        return HttpVersion.HTTP_2;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void doAddHeader(String key, String value) {
        String normalizedKey = key.toLowerCase();
        Object existing = h2Headers.get(normalizedKey);
        if (existing == null) {
            h2Headers.put(normalizedKey, value);
        } else if (existing.getClass() == String.class) {
            List<String> list = new ArrayList<>();
            list.add((String) existing);
            list.add(value);
            h2Headers.put(normalizedKey, list);
        } else {
            ((List<String>) existing).add(value);
        }
    }

    @Override
    public String getHeader(String key) {
        return headerFirstValue(h2Headers, key.toLowerCase());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getHeaders(String name) {
        return headerValues(h2Headers, name.toLowerCase());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<String> getHeaderNames() {
        return h2Headers.keySet();
    }

    @Override
    public void removeHeader(String key) {
        h2Headers.remove(key.toLowerCase());
    }

    @Override
    public void write(byte[] buf, int offset, int count) throws IOException {
        if (committed || count <= 0) return;
        if (!headersSent) writeHeaders(false);
        if (bodyBuf.size() + count > bodyMemoryThreshold()) {
            sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size(), false);
            bodyBuf.clear();
            sendChunkedData(buf, offset, count, false);
        } else {
            bodyBuf.write(buf, offset, count);
        }
    }

    @Override
    public void flush() throws IOException {
        if (committed) return;
        if (!headersSent) writeHeaders(false);
        if (!bodyBuf.isEmpty()) {
            sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size(), false);
            bodyBuf.clear();
        }
        stream.flushCtx();
    }

    @Override
    public void commit() throws IOException {
        if (committed) return;
        committed = true;
        if (headersSent) {
            // Headers already sent; flush remaining body with END_STREAM.
            if (!bodyBuf.isEmpty()) {
                sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size(), true);
                bodyBuf.clear();
            }
            stream.flushCtx();
            return;
        }
        writeFullResponse();                       // headers not sent yet; write headers and full body together
    }

    private void sendChunkedData(byte[] buf, int offset, int len, boolean endStream) throws IOException {
        sendChunkedData(buf, offset, len, endStream, true);
    }

    private void sendChunkedData(byte[] buf, int offset, int len, boolean endStream, boolean flush) throws IOException {
        if (len <= 0) return;
        int chunkSize = stream.sendChunkSize();
        int remaining = len;
        int off = offset;
        // Reusable buffer: create once, reuse for all chunks
        ByteBuffer frame = stream.createFrameBuffer(9 + chunkSize, chunkSize, Http2Frame.FRAME_TYPE_DATA, 0);
        while (remaining > 0) {
            int sendSize = Math.min(remaining, chunkSize);
            int actual = stream.acquirePartialSendWindow(sendSize);
            if (actual <= 0) return;
            boolean isLast = endStream && remaining == actual;
            int flags = isLast ? Http2Frame.END_STREAM : 0;
            frame.put(0, (byte) (actual >> 16))
                 .put(1, (byte) (actual >> 8))
                 .put(2, (byte) actual)
                 .put(4, (byte) flags)
                 .position(9)
                 .limit(9 + actual);
            frame.put(buf, off, actual);
            frame.flip();
            stream.writeFrame(frame, flush);
            off += actual;
            remaining -= actual;
        }
    }

    @Override
    protected void resetInternal() {
        super.resetInternal();
        h2Headers.clear();
    }

    @Override
    public void handover() {
        committed = true;
        autoCommit = false;
        headersSent = true;
        stream.handover();
    }

    @Override
    public void earlyHints(String linkHeader) throws IOException {
        if (headersSent) return;
        HttpBuf buf = HttpBuf.of(64);
        // Pre-encoded HPACK prefix: :status: 103 + link header name
        buf.write(_H2_EARLY_HINTS_PREFIX);
        // HPACK-encoded link header value
        Http2Helper.writeHpackString(buf, linkHeader);

        ByteBuffer frame = stream.createFrameBuffer(
                9 + buf.size(), buf.size(),
                Http2Frame.FRAME_TYPE_HEADERS, Http2Frame.END_HEADERS);
        frame.put(buf.toBytes());
        frame.flip();
        stream.writeFrame(frame);
    }

    // ==================== sendFile / 404 / GZIP ====================
    @Override
    protected void addCacheHeaders(long fileSize, long lastModified) throws IOException {
        addHeader(HttpHeaderNames.LAST_MODIFIED, HttpHeaderUtils.getDateHeaderValue(lastModified));
        addHeader(HttpHeaderNames.ETAG, generateETag(fileSize, lastModified));
    }

    @Override
    protected void sendFile0(File file, long fileSize, String mimeType, boolean shouldCompress) throws IOException {
        if (shouldCompress) {
            doStreamingCompressAndSend(file);
        } else {
            setContentLength(fileSize);
            doSendFileContent(file);
        }
    }

    @Override
    protected void notFound() throws IOException {
        status(HttpStatus.NOT_FOUND);
        bodyBuf.clear();
        commit();
    }

    protected void doSendFileContent(File file) throws IOException {
        long fileSize = file.length();
        // Small file (incl. empty): read into bodyBuf and reuse commit()'s merged HEADERS+DATA one-shot write.
        if (fileSize <= stream.sendChunkSize()) {
            bodyBuf.replace(readFileContent(file, (int) fileSize));
            commit();
        } else {
            // Large file: HEADERS without END_STREAM, then stream the file in framed chunks;
            // the final chunk (pos == fileSize) carries END_STREAM.
            writeHeaders(false);
            sendFileFramed(file);
            committed = true;
        }
    }

    /**
     * Attempt automatic GZIP compression on buffered body data and send.
     * <p>
     * Only applies when: global GZIP enabled, client supports gzip,
     * headers not sent, body size >= GZIP_MIN_SIZE. Delegates to
     *
     * @return true if auto-compress and send succeeded
     * @throws IOException if compression or send fails
     */
    protected boolean attemptAutoGzipAndSend() throws IOException {
        if (!shouldApplyAutoGzip()) return false;
        addHeader(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);
        bodyBuf.replace(gzipCompress(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyBuf.size()));
        commit();
        return true;
    }

    protected void doStreamingCompressAndSend(File file) throws IOException {
        final long fileSize = file.length();
        // Set Content-Encoding header for all compressed responses
        addHeader(HttpHeaderNames.CONTENT_ENCODING, HttpHeaderValues.GZIP);

        // For small files, use in-memory compression (same as Http1)
        if (fileSize <= bodyMemoryThreshold()) {
            bodyBuf.replace(gzipCompress(readFileContent(file, (int) fileSize)));
            commit();
            return;
        }

        // Send headers
        writeHeaders(false);
        // For large files, use streaming compression with HTTP/2 DATA frames (same as Http1 streamingGzipCompress)
        int chunkSize = stream.sendChunkSize();
        final ByteBuffer gzipFrame = ByteBuffer.allocate(9 + chunkSize);
        gzipFrame.putInt(5, stream.streamId);
        try (FileInputStream fis = new FileInputStream(file); GZIPOutputStream gzip = new GZIPOutputStream(
                new OutputStream() {
                    public void write(int b) throws IOException {
                        throw new IOException("single-byte write not supported");
                    }

                    public void write(byte[] b, int off, int len) throws IOException {
                        gzipFrame.put(0, (byte) (len >> 16))
                                .put(1, (byte) (len >> 8))
                                .put(2, (byte) len)
                                .clear().position(9);
                        gzipFrame.put(b, off, len);
                        gzipFrame.flip();
                        stream.writeDataFrame(gzipFrame, len);
                    }
                }, chunkSize)) {
            byte[] buffer = new byte[chunkSize];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) > 0) {
                gzip.write(buffer, 0, bytesRead);
            }
            gzip.finish();
        }
        committed = true;
        stream.flushCtx();
    }

    // The 'file' argument is guaranteed to already exist (caller/pre-check ensures it),
    // so we open and read it directly without an extra existence check.
    private void sendFileFramed(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        FileChannel fc = raf.getChannel();
        long fileSize = file.length();
        long pos = 0;
        int chunkSize = stream.sendChunkSize();
        // Reusable buffer: pre-fill streamId (the only non-zero header field)
        ByteBuffer frame = ByteBuffer.allocate(9 + chunkSize)
                .putInt(5, stream.streamId);
        try {
            while (pos < fileSize) {
                frame.position(9).limit(9 + chunkSize);
                int read = fc.read(frame);
                if (read <= 0) break;
                pos += read;
                // Patch length and END_STREAM flag (only these change per iteration)
                frame.put(0, (byte) (read >> 16))
                     .put(1, (byte) (read >> 8))
                     .put(2, (byte) read)
                     .put(4, (byte) (pos == fileSize ? Http2Frame.END_STREAM : 0))
                     .flip();
                stream.writeDataFrame(frame, read);
            }
        } finally {
            try {
                raf.close();
                fc.close();
            } catch (IOException ignored) {}
        }
    }

    // ==================== Frame construction ====================

    // private void writeHeaders() throws IOException {
    //     writeHeaders(bodyBuf.isEmpty() && contentLength <= 0);
    // }

    private void writeHeaders(boolean endStream) throws IOException {
        headersSent = true;
        // streaming path: never-indexed content-type, no ctx lock needed
        HttpBuf buf = buildStaticHeaderBlock();
        writeDynamicHeaders(buf, false);
        stream.writeHeadersFrame(buf.toBytes(), endStream);
    }

    // Send HEADERS (inside the ctx lock) then the body as merged / single-frame / chunked.
    // The single-frame and chunked body paths run outside the lock to keep it short.
    private void writeFullResponse() throws IOException {
        // Recompute content-length from the buffered body to prevent length/body mismatch.
        int bodyLen = bodyBuf.size();
        this.contentLength = bodyLen;
        HttpBuf hb = buildStaticHeaderBlock();             // frameBuf cleared inside
        boolean hasBody = bodyLen > 0;
        boolean windowReady = hasBody && bodyLen <= stream.sendChunkSize()
                && stream.acquireSendWindow(bodyLen);
        boolean mergeable;                                // assigned inside the lock below
        synchronized (stream.ctx) {
            writeDynamicHeaders(hb, true);                // dynamic-table state, atomic with write
            int headerLen = hb.size();
            mergeable = windowReady && headerLen <= stream.sendChunkSize();
            if (mergeable) {
                // One allocation: HEADERS(header+block) + DATA(header+body) written contiguously.
                ByteBuffer frame = ByteBuffer.allocate(9 + headerLen + 9 + bodyLen);
                frame.put((byte) (headerLen >> 16)).put((byte) (headerLen >> 8)).put((byte) headerLen)
                        .put(Http2Frame.FRAME_TYPE_HEADERS).put((byte) Http2Frame.END_HEADERS)
                        .putInt(stream.streamId);
                frame.put(hb.getBuf(), hb.getBegin(), headerLen);
                frame.put((byte) (bodyLen >> 16)).put((byte) (bodyLen >> 8)).put((byte) bodyLen)
                        .put(Http2Frame.FRAME_TYPE_DATA).put((byte) Http2Frame.END_STREAM)
                        .putInt(stream.streamId);
                frame.put(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyLen).flip();
                // prepareFrame applies the end-stream guard + DEBUG log (reads HEADERS flags only;
                // END_STREAM lives on the DATA frame, so mark it explicitly after the write).
                stream.prepareFrame(frame);
                stream.ctx.write(frame);                  // lock already held; skip re-sync
                stream.endStreamSent = true;              // DATA frame carried END_STREAM
            } else {
                // HEADERS always sent inside the lock (END_STREAM only when no body follows)
                stream.writeHeadersFrame(hb.toBytes(), !hasBody, false);
            }
        }
        // Body emission outside the lock: keep the critical section short and avoid
        // blocking on flow control while holding the ctx lock (deadlock with reader lock).
        if (!mergeable && hasBody) {
            if (windowReady) {
                // Single-frame body; window reserved by windowReady, flush deferred to signalFlush.
                ByteBuffer data = stream.createFrameBuffer(9 + bodyLen, bodyLen,
                        Http2Frame.FRAME_TYPE_DATA, Http2Frame.END_STREAM);
                data.put(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyLen).flip();
                stream.writeFrame(data, false);           // outside the lock; END_STREAM sets endStreamSent
            } else {
                // Window not ready or body too large; chunked send with flow-control wait.
                sendChunkedData(bodyBuf.getBuf(), bodyBuf.getBegin(), bodyLen, true, false);
            }
        }
        // Flush once after the lock so buffered bytes go out as one unit (order already guaranteed).
        stream.reader.signalFlush(stream.ctx);
        headersSent = true;
        bodyBuf.clear();
    }

    // Encode all headers except content-type (static refs / never-indexed, no dynamic table use).
    // Reuses the stream's frameBuf, which is free once the request body has been consumed.
    private HttpBuf buildStaticHeaderBlock() {
        HttpBuf buf = stream.frameBuf; // always cleared
        Http2Helper.writeHpackStatus(buf, status.code);
        if (contentLength > 0) {
            buf.write(_H2_CONTENT_LENGTH_PREFIX);
            Http2Helper.writeHpackString(buf, String.valueOf(contentLength));
        }
        buf.write(_H2_DATE_PREFIX);
        Http2Helper.writeHpackString(buf, HttpHeaderUtils.getDateHeaderValue(System.currentTimeMillis()));
        if (exposeServerHeader() && !h2Headers.containsKey(HttpHeaderNames.SERVER)) {
            buf.write(_H2_SERVER_PREFIX);
            Http2Helper.writeHpackString(buf, SERVER_VALUE);
        }
        for (Map.Entry<String, Object> entry : h2Headers.entrySet()) {
            String key = entry.getKey();
            if (HttpHeaderNames.CONTENT_TYPE.equals(key) || HttpHeaderNames.CONTENT_LENGTH.equals(key)) continue;
            Object val = entry.getValue();
            if (val.getClass() == String.class) {
                buf.write((byte) 0x10);
                Http2Helper.writeHpackString(buf, key);
                Http2Helper.writeHpackString(buf, (String) val);
            } else {
                for (String v : (List<String>) val) {
                    buf.write((byte) 0x10);
                    Http2Helper.writeHpackString(buf, key);
                    Http2Helper.writeHpackString(buf, v);
                }
            }
        }
        return buf;
    }

    // Write response headers: content-type via the HPACK dynamic table, the rest as
    // never-indexed literals. writeIndex=false (streaming) skips indexing; writeIndex=true
    // (inside the ctx lock) may seed a dynamic-table entry and reference it later.
    private void writeDynamicHeaders(HttpBuf buf, boolean writeIndex) {
        // peer-disabled dynamic-table indexing is handled inside indexOfValue
        if (contentType != null) {
            int k;
            if (writeIndex && (k = stream.reader.indexOfValue(contentType)) > -1) {
                int idx = stream.reader.headerIndex.get(k);
                if (idx > 0) {
                    buf.write((byte) (0x80 | idx)); // indexed reference (name + value from dynamic table)
                } else {
                    buf.write((byte) (0x40 | 31)); // incremental indexing, name=content-type (static idx 31)
                    Http2Helper.writeHpackString(buf, contentType);
                    stream.reader.fillHeaderIndex(k);
                } 
            } else {
                buf.write(_H2_CONTENT_TYPE_PREFIX); // never-indexed literal
                Http2Helper.writeHpackString(buf, contentType);
            }
            // additional headers appended here as sibling blocks, not blocked by content-type
        }
    }


}
