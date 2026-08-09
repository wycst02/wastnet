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

import io.github.wycst.wastnet.http.HttpConf;

import java.io.IOException;
import java.io.InputStream;

/**
 * HTTP/2 body input stream for streaming request body consumption.
 * <p>
 * Single-producer single-consumer lock-free circular buffer.
 * {@code read()} and {@code feed()} operate concurrently on non-overlapping
 * buffer regions, with {@code wait/notify} only when the buffer is empty
 * or the stream has ended.
 * <p>
 * Buffer capacity is fixed at construction time and should be pre-allocated
 * to {@link HttpConf#MAX_BODY_IN_MEMORY}
 * for streaming scenarios.
 * <p>
 * Implementation notes:
 * <ul>
 *   <li>{@code bodyPos} and {@code feedPos} are actual indexes in {@code [0, capacity)}</li>
 *   <li>A {@code full} flag disambiguates the case {@code bodyPos == feedPos}:
 *       {@code !full} means empty, {@code full} means the buffer is full</li>
 * </ul>
 *
 * @author wangyc
 */
public class Http2BodyInputStream extends InputStream {

    /**
     * Singleton for empty body streams (no body in request)
     */
    static final Http2BodyInputStream EMPTY = new Http2BodyInputStream();

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

    /**
     * Disambiguator for {@code bodyPos == feedPos}:
     * <ul>
     *   <li>{@code false} and {@code bodyPos == feedPos} → buffer empty</li>
     *   <li>{@code true}  and {@code bodyPos == feedPos} → buffer full</li>
     * </ul>
     * When {@code bodyPos != feedPos}, this flag is ignored.
     */
    volatile boolean full;

    long consumed = 0;
    final byte[] singleByte = new byte[1];

    /**
     * Private constructor for EMPTY singleton: capacity=0, always returns -1
     */
    private Http2BodyInputStream() {
        this.stream = null;
        this.bodyData = new byte[0];
        this.capacity = 0;
        this.feedPos = 0;
        this.totalLength = 0;
        this.full = false;
    }

    /**
     * Create a circular buffer pre-filled with {@code bodyData[0..bodyData.length)}.
     * <p>
     * The initial data occupies all {@code capacity} slots; the buffer starts in
     * the {@code full} state. Reading consumes data and transitions to normal
     * circular operation once {@code full} is cleared.
     */
    public Http2BodyInputStream(byte[] bodyData, Http2Stream stream) {
        this.stream = stream;
        this.bodyData = bodyData;
        this.capacity = bodyData.length;
        this.feedPos = 0;
        this.totalLength = bodyData.length;
        this.full = capacity > 0;
    }

    @Override
    public int read() throws IOException {
        int n = read(singleByte, 0, 1);
        return n == -1 ? -1 : singleByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;
        // Empty singleton: no body to read
        if (capacity == 0) return -1;

        while (true) {
            boolean f = full;
            int pos = bodyPos;
            int feed = feedPos;
            int avail = f ? capacity : (pos <= feed ? feed - pos : capacity - pos + feed);
            if (avail > 0) {
                int toRead = Math.min(avail, len);
                int cap = capacity;
                if (pos + toRead <= cap) {
                    // Linear read [pos, pos + toRead)
                    System.arraycopy(bodyData, pos, b, off, toRead);
                } else {
                    // Wrapped read: [pos, cap) then [0, ...)
                    int firstSeg = cap - pos;
                    System.arraycopy(bodyData, pos, b, off, firstSeg);
                    System.arraycopy(bodyData, 0, b, off + firstSeg, toRead - firstSeg);
                }

                int newPos = pos + toRead;
                if (newPos >= cap) {
                    newPos -= cap;
                }
                if (full) {
                    full = false;
                }
                bodyPos = newPos;
                consumed += toRead;
                // After read, send WU to update client's send window
                if (stream != null) {
                    stream.notifyConsumed(toRead);
                }
                return toRead;
            }
            if (ended) return -1;
            synchronized (this) {
                boolean fullSnapshot = full;
                int p = bodyPos;
                int fPos = feedPos;
                int a = fullSnapshot ? capacity : (p <= fPos ? fPos - p : capacity - p + fPos);
                if (a > 0) continue;
                if (ended) return -1;
                try {
                    wait();
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
     * Lock-free hot path: uses volatile read/write on {@code bodyPos},
     * {@code feedPos} and {@code full} for thread-safe SPSC operation.
     * Only acquires the monitor briefly for {@code notifyAll()} when new
     * data is available. If buffer is full, marks stream as ended to
     * signal back-pressure.
     *
     * @return true if normal, false if protocol violation (buffer full but more data sent)
     */
    public boolean feed(byte[] buf, int offset, int len) {
        if (ended || len == 0) return true;

        int cap = capacity;
        int pos = bodyPos;
        int feed = feedPos;
        int free;
        if (full) {
            free = 0;
        } else if (pos <= feed) {
            free = cap - feed + pos;
        } else {
            free = pos - feed;
        }

        if (free < len) {
            ended = true;
            synchronized (this) {
                notifyAll();
            }
            return false;  // protocol violation: buffer full but client sent more
        }

        int untilEnd = cap - feed;
        if (len <= untilEnd) {
            System.arraycopy(buf, offset, bodyData, feed, len);
        } else {
            System.arraycopy(buf, offset, bodyData, feed, untilEnd);
            System.arraycopy(buf, offset + untilEnd, bodyData, 0, len - untilEnd);
        }

        int newFeed = feed + len;
        if (newFeed >= cap) {
            newFeed -= cap;
        }
        // Must read bodyPos fresh (volatile) to correctly detect full:
        // the reader may have advanced bodyPos between our earlier cached read and here.
        if (newFeed == bodyPos) {
            full = true;
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
