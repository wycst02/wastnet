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

import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * HTTP/2 body input stream for streaming request body consumption.
 * <p>
 * Single-producer single-consumer lock-free circular buffer.
 * {@code read()} and {@code feed()} operate concurrently on non-overlapping
 * buffer regions, with {@code wait/notify} only when the buffer is empty
 * or the stream has ended.
 * <p>
 * Buffer capacity is fixed at construction time and should be pre-allocated
 * for streaming scenarios.
 * <p>
 * Implementation notes:
 * <ul>
 *   <li>{@code bodyPos} and {@code feedPos} are actual indexes in {@code [0, capacity)}</li>
 *   <li>The buffered byte count is {@code totalLength - consumed} (monotonic counters),
 *       so free space is {@code capacity - (totalLength - consumed)}; no empty/full
 *       ambiguity on {@code bodyPos == feedPos} arises.</li>
 * </ul>
 *
 * @author wangyc
 */
public class Http2BodyInputStream extends InputStream {

    /**
     * Singleton for empty body streams (no body in request)
     */
    static final Http2BodyInputStream EMPTY = new Http2BodyInputStream();

    static final Log log = LogFactory.getLog(Http2BodyInputStream.class);
    final byte[] bodyData;
    final int capacity;
    /**
     * Reference to stream context for WU updates after read
     */
    final Http2Stream stream;
    /**
     * Read cursor in {@code [0, capacity)}
     */
    volatile int bodyPos;
    /**
     * Write cursor in {@code [0, capacity)}
     */
    volatile int feedPos;
    /**
     * Total bytes fed into this stream since creation.
     * Used by external window management for flow control.
     */
    long totalLength;
    volatile boolean ended;

    volatile long consumed = 0;
    final byte[] singleByte = new byte[1];

    /**
     * Private constructor for EMPTY singleton: capacity=0, always returns -1
     */
    private Http2BodyInputStream() {
        this.stream = null;
        this.bodyData = new byte[0];
        capacity = feedPos = bodyPos = 0;
    }

    /**
     * Create a circular buffer pre-filled with {@code bodyData[0..bodyData.length)}.
     * <p>
     * The initial data occupies all {@code capacity} slots; the buffer starts
     * with {@code totalLength - consumed == capacity} (i.e. full).
     */
    public Http2BodyInputStream(byte[] bodyData, Http2Stream stream) {
        this.stream = stream;
        this.bodyData = bodyData;
        this.capacity = bodyData.length;
        this.feedPos = 0;
        this.totalLength = bodyData.length;
    }

    @Override
    public int read() throws IOException {
        int n = read(singleByte, 0, 1);
        return n == -1 ? -1 : singleByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;
        // Empty singleton: no body to read
        if (capacity == 0) return -1;
        final int bodyReadTimeoutMs = stream == null ? HttpOptions.HTTP2_BODY_READ_TIMEOUT_MS.value : stream.ctx.option(HttpOptions.HTTP2_BODY_READ_TIMEOUT_MS);
        long deadline = bodyReadTimeoutMs > 0
                ? System.currentTimeMillis() + bodyReadTimeoutMs : Long.MAX_VALUE;
        while (true) {
            int pos = bodyPos, feed = feedPos;
            // avail from cursors: linear (pos<feed) or wrapped (pos>feed); pos==feed is empty(0)/full(capacity),
            // distinguished by counter totalLength-consumed.
            int avail = pos < feed ? feed - pos : (pos > feed ? capacity - pos + feed : (int) (totalLength - consumed));
            if (avail > 0) {
                int toRead = Math.min(avail, len);
                int firstSeg = capacity - pos, newPos;
                if (toRead <= firstSeg) {
                    // Linear read [pos, pos + toRead)
                    System.arraycopy(bodyData, pos, b, off, toRead);
                    newPos = toRead == firstSeg ? 0 : pos + toRead;
                } else {
                    // Wrapped read: [pos, cap) then [0, ...)
                    System.arraycopy(bodyData, pos, b, off, firstSeg);
                    System.arraycopy(bodyData, 0, b, off + firstSeg, newPos = toRead - firstSeg);
                }
                bodyPos = newPos;
                consumed += toRead;
                // After read, send WU to update client's send window
                if(stream != null) {
                    stream.notifyConsumed(toRead);
                }
                return toRead;
            }
            if (ended) return -1;
            if (System.currentTimeMillis() >= deadline) {
                throw new SocketTimeoutException("HTTP/2 body read timeout after " + bodyReadTimeoutMs + "ms");
            }
            synchronized (this) {
                if (totalLength > consumed) continue;
                if (ended) return -1;
                try {
                    wait(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Read interrupted", e);
                }
            }
        }
    }

    /**
     * Feed DATA frame payload into the ring buffer.
     * <p>
     * Lock-free hot path: uses volatile read/write on {@code bodyPos} and
     * {@code feedPos}, with free space derived from the monotonic counters
     * {@code totalLength - consumed}. A full buffer is a correct state: when
     * the application layer does not consume, flow control must stop the peer
     * from sending more. Only if the peer violates flow control (sends more
     * while buffer is full) is it a protocol violation.
     *
     * @return true if normal, false if protocol violation (buffer full but more data sent)
     */
    public boolean feed(byte[] buf, int offset, int len) {
        if (ended || len == 0) return true;
        int cap = capacity, feed = feedPos;
        long free = cap - (totalLength - consumed);
        if (free < len) {
            // A full buffer is expected; the peer must not send more. If it does,
            // that is a flow-control violation.
            log.warn("[FEED-FAIL] free={} len={} cap={} bodyPos={} feedPos={} totalLength={} consumed={} recvWindow={}",
                    free, len, cap, bodyPos, feed, totalLength, consumed, (stream != null ? stream.receiveWindow : -1));
            ended = true;
            synchronized (this) {
                notifyAll();
            }
            return false;
        }
        int untilEnd = cap - feed, newFeed;
        if (len <= untilEnd) {
            System.arraycopy(buf, offset, bodyData, feed, len);
            newFeed = len == untilEnd ? 0 : feed + len;
        } else {
            System.arraycopy(buf, offset, bodyData, feed, untilEnd);
            System.arraycopy(buf, offset + untilEnd, bodyData, 0, newFeed = len - untilEnd);
        }
        feedPos = newFeed;
        totalLength += len;
        synchronized (this) {
            notifyAll();
        }
        return true;  // normal
    }

    /**
     * Mark the stream as ended (no more DATA frames expected).
     * Wakes up any reader blocked in {@link #read(byte[], int, int)}.
     */
    public void endStream() {
        ended = true;
        synchronized (this) {
            notifyAll();
        }
    }

    /**
     * Peek total bytes consumed
     */
    public long getConsumed() {
        return consumed;
    }

    @Override
    public int available() throws IOException {
        throw new IOException("available() is not supported on HTTP/2 body stream");
    }
}
