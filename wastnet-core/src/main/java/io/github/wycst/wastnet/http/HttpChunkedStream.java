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
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.rmi.UnexpectedException;
import java.util.Arrays;

/**
 * <p>HTTP chunked request body stream handler.</p>
 * <p>Handles chunked transfer encoding for HTTP request bodies.</p>
 *
 * @author wangyc
 * @since 2024/2/8 15:17
 */
final class HttpChunkedStream extends HttpBodyInputStream {

    byte[] chunkData;
    int chunkRemSize;
    boolean errorChunked;
    long totalReadedSize;

    public HttpChunkedStream(byte[] readedBytes, ChannelContext ctx) {
        super(0, readedBytes, ctx);
        this.totalReadedSize = readedBytes.length;
    }

    @Override
    public int read() throws IOException {
        throw new UnsupportedOperationException("Not supported");
    }

    void readCRLF() throws IOException {
        int cr = next(), lf = next();
        if (cr != '\r' || lf != '\n') {
            markErrorChunked();
            if (cr == -1 || lf == -1) {
                throw new UnexpectedException("Unexpected channel closure");
            }
            throw new UnexpectedException("CRLF expected, but found " + (byte) lf + " and " + (byte) cr);
        }
    }

    /**
     * Consume end-of-chunk marker after last chunk (size=0).
     * Normal case: consume final CRLF.
     * EOF case: connection closed after last chunk, still valid.
     * Trailer case: best-effort non-blocking drain, no decoding.
     */
    void readEndChunked() throws IOException {
        int cr = next(), lf = next();
        if ((cr == '\r' && lf == '\n') || cr == -1 || lf == -1) {
            return;
        }
        // Trailer headers may follow the end chunk; do a best-effort non-blocking drain.
        // HTTP/1.1 has no pipelining here, so residual bytes are dropped after the request
        // completes and cannot be smuggled into a subsequent request.
        try {
            ctx.read(ByteBuffer.wrap(DISCARD_BUF));
        } catch (IOException ignored) {
        }
    }

    void markErrorChunked() {
        errorChunked = true;
        markCompleted();
    }

    int readChunkSize(long timeoutMs) throws Exception {
        int c;
        long size = 0;
        while ((c = next(timeoutMs)) != '\r') {
            if (c == -1) {
                markErrorChunked();
                throw new UnexpectedException("Unexpected channel closure");
            }
            int h = Utils.hexNibble(c);
            if (h != -1) {
                size = size << 4 | h;
                if (size > Integer.MAX_VALUE) {
                    markErrorChunked();
                    throw new IllegalStateException("chunk size is too large");
                }
            } else {
                markErrorChunked();
                throw new UnexpectedException("byte token " + (byte) c + " is not hex digit");
            }
        }
        if ((c = next(timeoutMs)) != '\n') {
            markErrorChunked();
            throw new UnexpectedException("error byte token " + (byte) c);
        }
        return (int) size;
    }

    /**
     * <p>Read bytes from the chunked stream.</p>
     * <p>This method performs blocking reads, typically returning the parameter len.
     * If the return value is less than len, it indicates the reading is complete.</p>
     *
     * @param buf the buffer into which the data is read
     * @param off the start offset in array {@code buf} at which the data is written
     * @param len the maximum number of bytes to read
     * @return the total number of bytes read into the buffer, or -1 if there is no more data
     * @throws IOException if an I/O error occurs
     */
    @Override
    public int read(byte[] buf, int off, final int len) throws IOException {
        if (completed) {
            return -1;
        }
        int rem = len;
        try {
            while (true) {
                if (chunkRemSize > 0) {
                    int copyMinSize = Math.min(rem, chunkRemSize);
                    System.arraycopy(chunkData, chunkData.length - chunkRemSize, buf, off, copyMinSize);
                    off += copyMinSize;
                    chunkRemSize -= copyMinSize;
                    if ((rem -= copyMinSize) == 0) {
                        return len;
                    }
                }
                // 1. read chunk size
                int chunkSize = readChunkSize(readTimeoutMs);
                if (chunkSize == 0) {
                    readEndChunked();
                    markCompleted();
                    return len - rem;
                } else {
                    // Check total size limit for chunked encoding
                    if (totalReadedSize + chunkSize > ctx.option(HttpOptions.BODY_MAX_SIZE)) {
                        markErrorChunked();
                        throw new IllegalStateException("Chunked body size exceeds limit " + ctx.option(HttpOptions.BODY_MAX_SIZE));
                    }
                    // 2. read chunk data
                    readFully(chunkData = new byte[chunkSize], 0, chunkSize, readTimeoutMs);
                    readCRLF();
                    // 3. update total size and reset chunkRemSize
                    totalReadedSize += chunkSize;
                    chunkRemSize = chunkSize;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("chunk data error", e);
        }
    }

    /**
     * <p>Override readChannel method for chunked transfer encoding.</p>
     * <p>In chunked mode, we don't know the total content length in advance.
     * So we read data until the channel is closed or chunked end marker is reached.</p>
     *
     * @param buf       target byte array
     * @param off       starting offset
     * @param len       number of bytes to read
     * @param timeoutMs the read timeout in milliseconds
     * @return number of bytes actually read, -1 if end of stream
     * @throws IOException if read operation fails
     */
    @Override
    protected int readChannel(byte[] buf, int off, int len, long timeoutMs) throws IOException {
        // For chunked requests, read directly from channel without content-length limitation
        // Continue reading until channel is closed or explicit end marker is encountered
        int channelSize = ctx.readFully(buf, off, len, timeoutMs);
        if (channelSize == -1) {
            markCompleted();
            return -1;
        }
        return channelSize;
    }

    @Override
    protected byte[] readFullBytes0() throws IOException {
        try {
            byte[] tmp = new byte[1024];
            int len = tmp.length;
            int count, readedCount = 0;
            while ((count = read(tmp, readedCount, len)) > -1) {
                readedCount += count;
                if (count < len) {
                    len -= count;
                } else {
                    // 3x expansion
                    len = tmp.length << 1;
                    tmp = Arrays.copyOf(tmp, tmp.length + len);
                }
            }
            return Arrays.copyOf(tmp, readedCount);
        } finally {
            markCompleted();
        }
    }

    @Override
    protected void complete0() {
        if (completed) return;
        long startTime = System.currentTimeMillis();
        try {
            int chunkSize;
            while ((chunkSize = readChunkSize(DISCARD_TIMEOUT_MS)) != 0) {
                // Check cumulative timeout to prevent DoS attacks
                if (System.currentTimeMillis() - startTime > COMPLETE_CUMULATIVE_TIMEOUT_MS) {
                    markErrorChunked();
                    ctx.close();
                    return;  // Silent handling: just close and return
                }
                int rem = (int) (readedBytes.length - pos);
                if (rem >= chunkSize) {
                    pos += chunkSize;
                    readCRLF();
                    continue;
                }
                if (rem > 0) {
                    pos += rem;
                    chunkSize -= rem;
                }
                // Discard declared chunk with fixed buffer; never allocate by attacker-declared size.
                int remaining = chunkSize;
                while (remaining > 0) {
                    int n = Math.min(remaining, DISCARD_BUF.length);
                    int r = readChannel(DISCARD_BUF, 0, n, DISCARD_TIMEOUT_MS);
                    if (r == -1) {
                        return;
                    }
                    remaining -= r;
                }
                readCRLF();
            }
            readEndChunked();
            markCompleted();
        } catch (Exception e) {
            markErrorChunked();
            ctx.close();
            // Silent handling: just close the channel, don't throw exception
        }
    }
}