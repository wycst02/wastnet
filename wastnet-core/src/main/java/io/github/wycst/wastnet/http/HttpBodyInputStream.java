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

import io.github.wycst.wastnet.socket.conf.SocketConf;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * <p>HTTP body stream handler.</p>
 * <p>Channel based input stream, only processing completed without closing.</p>
 *
 * @author wangyc
 * @since 2024/2/8 15:17
 */
public class HttpBodyInputStream extends InputStream {

    protected final ChannelContext ctx;
    protected final long bodyLength;
    protected final byte[] readedBytes;

    protected long readedLength;
    protected long pos;
    protected boolean completed;

    protected byte[] fullBytes;

    /**
     * Static shared buffer for discarding unread data (fallback when transferTo unavailable).
     * Used to avoid repeated allocations during complete() operations.
     */
    protected static final byte[] DISCARD_BUF = new byte[8192];

    /**
     * Timeout for discarding unread data in complete0() (5 seconds per read operation).
     */
    protected static final long DISCARD_TIMEOUT_MS = 5000;

    /**
     * Cumulative timeout for complete0() operation (30 seconds total).
     * Prevents DoS attacks by limiting total time spent discarding data.
     */
    protected static final long COMPLETE_CUMULATIVE_TIMEOUT_MS = 30000;

    /**
     * Buffer size for direct buffer discard (8KB).
     */
    protected static final int DIRECT_BUFFER_SIZE = 8 * 1024;

    /**
     * Threshold for using direct buffer (512KB).
     * Direct buffer allocation is expensive, only worthwhile for larger data.
     */
    private static final int DIRECT_BUFFER_THRESHOLD = 512 * 1024;

    public HttpBodyInputStream(long bodyLength, byte[] readedBytes, ChannelContext ctx) {
        this.bodyLength = bodyLength;
        this.readedBytes = readedBytes;
        this.readedLength = readedBytes.length;
        this.ctx = ctx;
    }

    protected final int next() throws IOException {
        return next(SocketConf.READ_TIMEOUT_MS);
    }

    protected final int next(long timeoutMs) throws IOException {
        if (pos < readedBytes.length) {
            return readedBytes[(int) pos++] & 0xFF;
        }
        byte[] buf = new byte[1];
        int size = readFully(buf, 0, 1, timeoutMs);
        return size == -1 ? -1 : buf[0] & 0xFF;
    }

    /**
     * <p>Read a single byte from the stream.</p>
     * <p><b>Blocking operation</b>: This method blocks until data is available or timeout occurs.
     * Timeout is controlled by {@link SocketConf#READ_TIMEOUT_MS}.</p>
     * <p>Performance warning: This method is inefficient as it creates a new byte array for each call.
     * Not recommended for bulk reading operations.</p>
     * <p>Recommended: Use {@link #read(byte[], int, int)} for better performance when reading multiple bytes.</p>
     *
     * @return the byte read, or -1 if completed of stream
     * @throws IOException if an I/O error occurs or read timeout
     */
    @Override
    public int read() throws IOException {
        return next(SocketConf.READ_TIMEOUT_MS);
    }

    /**
     * <p>Read bytes from the stream with custom timeout.</p>
     * <p><b>Blocking operation</b>: This method blocks until the specified length is read or timeout occurs.</p>
     * <p>Use this method when you need a timeout different from {@link SocketConf#READ_TIMEOUT_MS}.</p>
     *
     * @param buf       the buffer into which the data is read
     * @param off       the start offset in array {@code buf}
     * @param len       the maximum number of bytes to read
     * @param timeoutMs timeout in milliseconds, 0 or negative means unlimited wait
     * @return the total number of bytes read, or -1 if stream is completed
     * @throws IOException if an I/O error occurs or read timeout
     */
    public final int readFully(byte[] buf, int off, int len, long timeoutMs) throws IOException {
        if (completed) {
            return -1;
        }
        int rtnLen = -1;
        int byteLen = readedBytes.length;
        if (pos < byteLen) {
            int pos0 = (int) pos, rem = byteLen - pos0;
            if (rem >= len) {
                System.arraycopy(readedBytes, pos0, buf, off, len);
                pos += len;
                return len;
            } else {
                System.arraycopy(readedBytes, pos0, buf, off, rem);
                pos = byteLen;
                off += rem;
                len -= rem;
                rtnLen = rem;
            }
        }
        if (ctx.isChannelClosed()) {
            markCompleted();
            return rtnLen;
        }
        int channelSize = readChannel(buf, off, len, timeoutMs);
        if (channelSize == -1) {
            markCompleted();
            return rtnLen;
        }
        return rtnLen == -1 ? channelSize : rtnLen + channelSize;
    }

    /**
     * <p>Read bytes from the stream.</p>
     * <p><b>Blocking operation</b>: This method blocks until data is available or timeout occurs.
     * Timeout is controlled by {@link SocketConf#READ_TIMEOUT_MS}.</p>
     *
     * @param buf the buffer into which the data is read
     * @param off the start offset in array {@code buf} at which the data is written
     * @param len the maximum number of bytes to read
     * @return the total number of bytes read into the buffer, or -1 if there is no more data
     * @throws IOException if an I/O error occurs or read timeout
     */
    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        return readFully(buf, off, len, SocketConf.READ_TIMEOUT_MS);
    }

    /**
     * <p>Read bytes from the channel.</p>
     *
     * @param buf       the buffer to read into
     * @param off       the start offset in array {@code buf}
     * @param len       the maximum number of bytes to read
     * @param timeoutMs the read timeout in milliseconds
     * @return the number of bytes read, or -1 if completed of stream
     * @throws IOException if an I/O error occurs
     */
    protected int readChannel(byte[] buf, int off, int len, long timeoutMs) throws IOException {
        long remSize = bodyLength - readedLength;
        if (remSize == 0) {
            this.completed = true;
            return -1;
        }
        if (len > remSize) {
            len = (int) remSize;
        }
        // continue read from channel
        int channelSize = ctx.readFully(buf, off, len, timeoutMs);
        if (channelSize == -1) {
            markCompleted();
            return -1;
        }
        pos += channelSize;
        readedLength += channelSize;
        checkCompleted();
        return channelSize;
    }

    void markCompleted() {
        this.completed = true;
    }

    @Override
    public int available() throws IOException {
        return (int) bodyLength;
    }

    public boolean checkCompleted() {
        if (completed) return true;
        return completed = bodyLength == readedLength;
    }

    public final void complete() {
        if (completed) {
            return;
        }
        complete0();
    }

    private void completeAndClose() {
        markCompleted();
        ctx.close();
    }

    @SuppressWarnings("all")
    protected void complete0() {
        if (completed) {
            return;
        }
        long startTime = System.currentTimeMillis();
        try {
            // Calculate remaining bytes to discard
            long remaining = bodyLength - readedLength;
            if (remaining <= 0) {
                markCompleted();
                return;
            }

            /*// Use direct buffer for efficient discard (non-SSL only, large data only)
            if (!ctx.isSSL() && remaining > DIRECT_BUFFER_THRESHOLD) {
                java.nio.ByteBuffer directBuffer = java.nio.ByteBuffer.allocateDirect(DIRECT_BUFFER_SIZE);
                long lastReadTime = System.currentTimeMillis();
                final long ZERO_READ_TIMEOUT_MS = 2000;  // 2 seconds grace period for zero reads
                try {
                    while (remaining > 0) {
                        if (System.currentTimeMillis() - startTime > COMPLETE_CUMULATIVE_TIMEOUT_MS) {
                            completeAndClose();
                            return;
                        }
                        directBuffer.clear().limit((int) Math.min(remaining, DIRECT_BUFFER_SIZE));

                        // Read from socket, data will be discarded
                        int read = ctx.channel().read(directBuffer);
                        if (read < 0) {
                            // Connection closed by client
                            completeAndClose();
                            return;
                        }
                        if (read == 0) {
                            // Check if zero read timeout exceeded
                            if (System.currentTimeMillis() - lastReadTime > ZERO_READ_TIMEOUT_MS) {
                                completeAndClose();
                                return;
                            }
                            continue;
                        }
                        lastReadTime = System.currentTimeMillis();

                        remaining -= read;
                        readedLength += read;
                    }
                    markCompleted();
                    return;
                } catch (IOException e) {
                    // Direct buffer method failed, try fallback
                }
            }*/

            // Fallback: buffer-based blocking discard (works for SSL)
            do {
                readChannel(DISCARD_BUF, 0, DISCARD_BUF.length, DISCARD_TIMEOUT_MS);
                // Check cumulative timeout to prevent DoS attacks
                if (System.currentTimeMillis() - startTime > COMPLETE_CUMULATIVE_TIMEOUT_MS) {
                    completeAndClose();
                    return;
                }
            } while (!completed && !ctx.isChannelClosed());
        } catch (Exception e) {
            completeAndClose();
        }
    }


    @Override
    public final void close() throws IOException {
        complete();
    }

    /**
     * <p>Read all bytes from the stream.</p>
     * <p>Performance warning: This method loads all data into memory at once.
     * Not recommended for large request bodies.</p>
     *
     * @return the complete byte array containing all stream data
     * @throws IOException if an I/O error occurs
     */
    protected byte[] readFullBytes0() throws IOException {
        final int byteLen = readedBytes.length;
        if (pos > byteLen) {
            // The channel stream has been partially read, and calling readFullBytes is not allowed
            throw new IllegalStateException("The channel has already read the data, it is not supported here");
        }
        final int len = (int) bodyLength;
        byte[] buf = Arrays.copyOf(readedBytes, len);
        final int remSize = len - byteLen;
        pos = byteLen;
        if (read(buf, byteLen, remSize) != remSize) {
            throw new IllegalStateException("data error");
        }
        return buf;
    }

    final byte[] readFullBytes() {
        if (fullBytes != null) {
            return fullBytes;
        }
        try {
            return this.fullBytes = readFullBytes0();
        } catch (IOException e) {
            throw new IllegalStateException("data error", e);
        }
    }
}