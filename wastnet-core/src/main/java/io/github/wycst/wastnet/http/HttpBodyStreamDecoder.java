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

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Stream-based HTTP body decoder implementation.
 * Decodes body data from InputStream with memory-efficient multipart handling.
 *
 * @author wangyc
 * @since 2024/3/4
 */
final class HttpBodyStreamDecoder extends HttpBodyDecoder {
    private final InputStream bodyStream;
    private final ChannelContext ctx;
    private final int bufferSize;
    private final int compactThreshold;
    private final int maxBufferSize;

    HttpBodyStreamDecoder(String contentType, InputStream bodyStream) {
        this(contentType, bodyStream, ChannelContext.EMPTY_CONTEXT);
    }

    HttpBodyStreamDecoder(String contentType, InputStream bodyStream, ChannelContext ctx) {
        super(contentType);
        this.bodyStream = bodyStream;
        this.ctx = ctx;
        this.bufferSize = Math.max(8192, ctx.option(HttpOptions.MAX_BODY_IN_MEMORY));
        this.compactThreshold = this.bufferSize >> 1;
        this.maxBufferSize = this.bufferSize << 1;
    }

    @Override
    protected void doDecodeMultipartFields(byte[] boundaryBytes) throws IOException {
        if (bodyStream == null) {
            multipartFields = Collections.emptyMap();
            return;
        }

        Map<String, List<MultipartField>> result = new HashMap<>(8);
        int fieldIndex = 0;
        try {
            byte[] buffer = new byte[bufferSize];
            int pos, limit;
            int[] badChar = buildBadCharTable(boundaryBytes);

            // Read initial data
            if ((limit = bodyStream.read(buffer)) == -1) return;

            // Verify first boundary
            if (limit < boundaryBytes.length || !startsWithBoundary(buffer, boundaryBytes)) return;
            pos = boundaryBytes.length;

            while (pos < limit) {
                // Check for CRLF after boundary
                if (buffer[pos] == '\r' && pos + 1 < limit && buffer[pos + 1] == '\n') {
                    pos += 2;
                } else {
                    // End marker (--boundary--) or format error
                    break;
                }

                // Parse headers
                int headerEnd = HttpUnsafe.findCRLFCRLF(buffer, pos, limit);
                if (headerEnd == -1) {
                    // Multipart headers are typically small. If CRLFCRLF not found in a full buffer,
                    // it's likely malformed data. We expand buffer once as a fallback.
                    if (limit < buffer.length || buffer.length == maxBufferSize) break;
                    int oldLimit = limit;
                    buffer = Arrays.copyOf(buffer, maxBufferSize);
                    int n = bodyStream.read(buffer, oldLimit, maxBufferSize - oldLimit);
                    if (n == -1) break;
                    limit += n;
                    // Search from oldLimit - 3 (CRLFCRLF may cross boundary)
                    headerEnd = HttpUnsafe.findCRLFCRLF(buffer, oldLimit - 3, limit);
                    if (headerEnd == -1) break;  // Still not found = malformed, terminate
                }

                int headerLen = headerEnd - pos + 4;
                byte[] headerData = Arrays.copyOfRange(buffer, pos, pos + headerLen);
                HttpBuf[] headerValues = parseMultipartHeaders(headerData, 0, headerLen - 2);
                String fieldName = headerValues[0] != null ? headerValues[0].toString(charset) : null;
                boolean hasFieldName = fieldName != null && !fieldName.isEmpty();
                pos = headerEnd + 4;

                // Compact buffer if pos exceeds half capacity.
                // This ensures the in-memory field size threshold is between BUFFER_SIZE/2 and BUFFER_SIZE:
                // - When pos <= half: no compact, search range is ~half buffer -> threshold ~BUFFER_SIZE/2
                // - When pos > half: compact + refill, search range is full buffer -> threshold ~BUFFER_SIZE
                if (pos > compactThreshold) {
                    limit -= pos;
                    if (limit > 0) {
                        System.arraycopy(buffer, pos, buffer, 0, limit);
                    }
                    pos = 0;
                    // Refill buffer to maximize search range
                    int n = bodyStream.read(buffer, limit, buffer.length - limit);
                    if (n != -1) {
                        limit += n;
                    }
                }

                // Find next boundary
                int nextBoundary = findBytes(buffer, pos, limit, boundaryBytes, badChar);

                MultipartField field = null;
                if (nextBoundary > -1) {
                    // Boundary found in buffer - use memory
                    if (hasFieldName) {
                        int contentEnd = nextBoundary;
                        if (contentEnd > pos && buffer[contentEnd - 1] == '\n') {
                            --contentEnd;
                            if (contentEnd > pos && buffer[contentEnd - 1] == '\r') {
                                --contentEnd;
                            }
                        }
                        field = createField(fieldName, headerValues, buffer, pos, contentEnd - pos, charset);
                    }
                    // Move data after boundary to buffer start
                    int remaining = limit - nextBoundary - boundaryBytes.length;
                    if (remaining > 0) {
                        System.arraycopy(buffer, nextBoundary + boundaryBytes.length, buffer, 0, remaining);
                    }
                    limit = remaining;
                } else {
                    // Boundary not in buffer - use temp file
                    int[] limitHolder = new int[1];
                    field = readFieldToFile(bodyStream, buffer, pos, limit, boundaryBytes, badChar, fieldName, headerValues, limitHolder);
                    limit = limitHolder[0];
                    if (limit == -1) return;
                }

                addField(result, fieldName, field, fieldIndex++);

                // Refill buffer if not full
                if (limit < buffer.length) {
                    int n = bodyStream.read(buffer, limit, buffer.length - limit);
                    if (n > 0) {
                        limit += n;
                    }
                }
                pos = 0;
            }
        } finally {
            multipartFields = result;
        }
    }

    @SuppressWarnings("all")
    private void addField(Map<String, List<MultipartField>> result, String fieldName, MultipartField field, int index) {
        if (field != null) {
            field.setIndex(index);
            List<MultipartField> fieldList = result.get(fieldName);
            if (fieldList == null) {
                result.put(fieldName, fieldList = new ArrayList<MultipartField>(1));
            }
            fieldList.add(field);
        }
    }

    /**
     * Read field content to temp file until boundary is found.
     * <p>
     * Processing flow (caller has confirmed no boundary in buffer[pos, limit)):
     * <ol>
     *   <li>Write safe portion (limit - boundaryLen), keep overlap for boundary detection</li>
     *   <li>Read new data, search for boundary</li>
     *   <li>If found: write content before boundary (strip CRLF), move remaining to buffer start, return</li>
     *   <li>If not found: write safe portion, keep overlap, repeat from step 2</li>
     * </ol>
     *
     * @param limitHolder output: new limit in buffer (or -1 if stream ended)
     * @return MultipartFieldFile, or null if fieldName is null or stream ended
     */
    private MultipartField readFieldToFile(InputStream in, byte[] buffer, int pos, int limit,
                                           byte[] boundaryBytes, int[] badChar, String fieldName, HttpBuf[] headerValues,
                                           int[] limitHolder) throws IOException {
        File tempFile = null;
        FileChannel channel = null;
        boolean success = false;
        boolean skipContent = fieldName == null || !ctx.option(HttpOptions.ENABLE_TEMP_FILE);
        int boundaryLen = boundaryBytes.length;
        try {
            if (!skipContent) {
                tempFile = createTempFile();
                channel = new FileOutputStream(tempFile).getChannel();
            }

            // Tail reserve: bytes kept at buffer end to handle boundary crossing buffer boundary
            final int tailReserve = boundaryLen + 2;
            int writePos = pos;
            // ByteBuffer directBuffer = ByteBuffer.allocateDirect(buffer.length);
            while (true) {
                int safeEnd = limit - tailReserve;
                // Note: safeEnd > writePos is theoretically always true in normal cases (limit ~4096, tailReserve ~72).
                // The check remains as defensive programming for malformed input or edge cases.
                if (safeEnd > writePos) {
                    if (!skipContent) {
                        writeFully(channel, buffer, writePos, safeEnd - writePos);
                    }
                    System.arraycopy(buffer, safeEnd, buffer, 0, tailReserve);
                    limit = tailReserve;
                }

                // Read more data
                int n = in.read(buffer, limit, buffer.length - limit);
                if (n == -1) {
                    // Stream ended without boundary - malformed data
                    limitHolder[0] = -1;
                    return null;
                }
                limit += n;

                // Search for boundary
                int boundaryPos = findBytes(buffer, 0, limit, boundaryBytes, badChar);

                if (boundaryPos > -1) {
                    // Found boundary - write content up to CRLF before it
                    if (!skipContent && boundaryPos > 0) {
                        int contentEnd = boundaryPos;
                        if (buffer[contentEnd - 1] == '\n') {
                            --contentEnd;
                            if (contentEnd > 0 && buffer[contentEnd - 1] == '\r') {
                                --contentEnd;
                            }
                        }
                        if (contentEnd > 0) {
                            writeFully(channel, buffer, 0, contentEnd);
                        }
                    }

                    int remaining = limit - boundaryPos - boundaryLen;
                    if (remaining > 0) {
                        System.arraycopy(buffer, boundaryPos + boundaryLen, buffer, 0, remaining);
                    }
                    limitHolder[0] = remaining;
                    break;
                }

                writePos = 0;
            }
            success = true;
            return skipContent ? null : new MultipartFieldFile(fieldName, headerValues[1], headerValues[2], tempFile, charset);
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            if (!success && tempFile != null && tempFile.exists()) {
                if (!tempFile.delete()) tempFile.deleteOnExit();
            }
        }
    }

    /**
     * Write all bytes from buffer to channel, handling partial writes.
     */
    private static void writeFully(FileChannel channel, byte[] data, int offset, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        int zeroWriteCount = 0;
        while (buf.hasRemaining()) {
            if (channel.write(buf) == 0) {
                if (++zeroWriteCount > 100) {
                    throw new IOException("Channel write returned 0 repeatedly");
                }
            } else {
                zeroWriteCount = 0;
            }
        }
    }

    private static MultipartField createField(String name, HttpBuf[] headerValues, byte[] buffer, int start, int len, Charset charset) {
        return new MultipartFieldData(name, headerValues[1], headerValues[2],
                Arrays.copyOfRange(buffer, start, start + len), charset);
    }

    private File createTempFile() throws IOException {
        return File.createTempFile(ctx.option(HttpOptions.TEMP_FILE_PREFIX), ".tmp", new File(ctx.option(HttpOptions.TEMP_FILE_DIR)));
    }

    /**
     * Decode application/x-www-form-urlencoded body from stream.
     * <p>
     * Processing logic based on stream type:
     * <ul>
     *   <li><b>Non-chunked stream (HttpBodyInputStream)</b>: The request body size is known via Content-Length header.
     *       If content-length exceeds MAX_BODY_IN_MEMORY, this stream-based decoder is used instead of
     *       HttpBodyDefaultDecoder. Such large form data is rejected immediately with an exception.</li>
     *   <li><b>Chunked stream (HttpChunkedStream)</b>: The total size is unknown until fully read.
     *       We read incrementally and track byte count, rejecting if exceeds MAX_BODY_IN_MEMORY.</li>
     * </ul>
     * <p>
     * Note: form-urlencoded is not designed for large data. For large uploads, use multipart/form-data instead.
     *
     * @throws IllegalStateException if body size exceeds MAX_BODY_IN_MEMORY
     */
    @Override
    protected void decodeFormUrlencoded() {
        if (!isFormUrlencoded() || bodyStream == null) {
            urlencodedParams = Collections.emptyMap();
            return;
        }

        // Non-chunked stream: content-length already exceeds MAX_BODY_IN_MEMORY, reject immediately
        if (!(bodyStream instanceof HttpChunkedStream)) {
            throw new IllegalStateException("Form data exceeds maximum size: " + ctx.option(HttpOptions.MAX_BODY_IN_MEMORY));
        }

        // Chunked stream: read incrementally and check size limit
        HttpUriDecoder decoder = new HttpUriDecoder(false, true);
        byte[] tmp = new byte[8192];
        int n, total = 0;
        int maxSize = ctx.option(HttpOptions.MAX_BODY_IN_MEMORY);
        try {
            while ((n = bodyStream.read(tmp)) != -1) {
                total += n;
                if (total > maxSize) {
                    throw new IllegalStateException("Form data exceeds maximum size: " + maxSize);
                }
                decoder.codec(tmp, 0, n);
            }
            decoder.endCodec();
            urlencodedParams = decoder.getParameters();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read form data", e);
        }
    }
}
