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

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.util.Utils;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Auto-growing byte buffer for HTTP message assembly.
 *
 * <p>Designed for efficient HTTP header/body construction with the following capabilities:
 *
 * <ul>
 *   <li><b>Auto-growth</b> — starts at a configurable initial size and grows
 *       dynamically as data is written, up to an optional {@code maxCapacity} bound.
 *       Growth uses a 50% expansion factor (x1.5) to amortize reallocation costs.</li>
 *
 *   <li><b>Zero-copy wrap / replace</b> — {@link #wrap(byte[], int, int)} and
 *       {@link #replace(byte[], int, int)} adopt an external byte array as the
 *       internal buffer without copying. The view is defined by the {@code begin}
 *       offset and {@code count} fields.</li>
 *
 *   <li><b>Head-deletion without copy</b> — {@link #deleteHead(int)} advances the
 *       {@code begin} pointer, avoiding data movement. When front space accumulates,
 *       {@link #compact()} shifts remaining data to offset 0.</li>
 *
 *   <li><b>Bound-checked writing</b> — {@link #write(byte[], int, int)} clamps to
 *       {@code maxCapacity} and never throws; returns actual bytes written.
 *       {@link #append(byte[], int, int)} is an unchecked fast path for internal use.</li>
 *
 *   <li><b>Optimized ISO-8859-1 string support</b> — {@link #toISO_8859_1_string()}
 *       uses {@link io.github.wycst.wastnet.http.HttpUnsafe} for zero-copy string
 *       creation on JDK 9+. Trimmed variants handle HTTP OWS (optional whitespace)
 *       without allocation.</li>
 *
 *   <li><b>ByteBuffer bridge</b> — {@link #byteBuffer()} wraps the valid region
 *       for integration with NIO channels.</li>
 *
 *   <li><b>Reset to initial state</b> — {@link #reset()} discards all changes and
 *       reuses the original allocation.</li>
 * </ul>
 *
 * @author wangyc
 * @since 2024-11-17
 */
public final class HttpBuf {

    byte[] buf;
    int count;
    int begin;
    int extra;
    final byte[] resetBuf;
    // Maximum length of the buffer array
    final int maxCapacity;

    /**
     * Creates a new HttpBuf with default initial size of 64 bytes.
     */
    public HttpBuf() {
        this(64);
    }

    /**
     * Creates a new HttpBuf with the specified initial size.
     *
     * @param initialSize the initial buffer size
     */
    public HttpBuf(int initialSize) {
        this(initialSize, Integer.MAX_VALUE);
    }

    /**
     * Creates a new HttpBuf with the specified initial size and maximum capacity.
     *
     * @param initialSize the initial buffer size
     * @param maxCapacity the maximum buffer capacity
     */
    public HttpBuf(int initialSize, int maxCapacity) {
        buf = resetBuf = new byte[initialSize];
        this.maxCapacity = Math.max(maxCapacity, initialSize);
    }

    /**
     * Internal constructor for creating a sub-string view of an existing buffer.
     * This constructor is package-private and used internally for zero-copy substring operations.
     *
     * @param buf   the byte array to wrap
     * @param begin the starting index of the valid data
     * @param count the number of valid bytes
     */
    HttpBuf(byte[] buf, int begin, int count) {
        this.buf = resetBuf = buf;
        this.begin = begin;
        this.count = count;
        maxCapacity = Integer.MAX_VALUE;
    }

    /**
     * Returns an empty HttpBuf instance.
     *
     * @return an empty HttpBuf with zero capacity
     */
    public static HttpBuf empty() {
        return new HttpBuf(0);
    }

    /**
     * Creates a new HttpBuf with the specified initial capacity.
     *
     * @param initialCapacity the initial buffer capacity
     * @return a new HttpBuf instance
     */
    public static HttpBuf of(int initialCapacity) {
        return new HttpBuf(initialCapacity);
    }

    /**
     * Creates a new HttpBuf with the specified initial capacity and maximum capacity.
     *
     * @param initialCapacity the initial buffer capacity
     * @param maxCapacity     the maximum buffer capacity
     * @return a new HttpBuf instance
     */
    public static HttpBuf of(int initialCapacity, int maxCapacity) {
        return new HttpBuf(initialCapacity, maxCapacity);
    }

    /**
     * Creates a new HttpBuf with default settings.
     *
     * @return a new HttpBuf instance
     */
    public static HttpBuf create() {
        return new HttpBuf();
    }

    /**
     * Wraps an existing byte array to create a new HttpBuf instance without copying.
     * This is zero-copy buffer creation.
     * <p>
     * <b>Note:</b> Subsequent {@link #write(byte[], int, int)} and
     * {@link #append(byte[], int, int)} auto-grow the backing array on demand,
     * breaking the zero-copy view. Call {@link #compact()} first if the front
     * of the array contains unused space.
     *
     * @param buf   the byte array to wrap
     * @param begin the starting index of the valid data
     * @param count the number of valid bytes
     * @return a new HttpBuf instance wrapping the given array
     */
    public static HttpBuf wrap(byte[] buf, int begin, int count) {
        return new HttpBuf(buf, begin, count);
    }

    /**
     * Compresses the buffer by moving valid data to the beginning.
     * This operation frees up space at the end of the buffer for additional writes.
     *
     * @return this buffer, for chaining
     */
    public HttpBuf compact() {
        if (begin > 0) {
            if (count > 0) {
                System.arraycopy(buf, begin, buf, 0, count);
            }
            begin = 0;
        }
        return this;
    }

    /**
     * Expands the buffer to the specified capacity.
     * Data is copied to the new buffer and the begin offset is reset to 0.
     *
     * @param capacity the new capacity for the buffer
     */
    void expandTo(int capacity) {
        byte[] temp = new byte[capacity];
        System.arraycopy(buf, begin, temp, 0, count);
        buf = temp;
        begin = 0;
    }

    /**
     * Increments the buffer capacity to accommodate additional bytes.
     * First attempts to compact the buffer if there is space at the beginning,
     * then expands the buffer if necessary.
     *
     * @param increment the number of additional bytes needed
     */
    void incrementCapacity(int increment) {
        int required = begin + count + increment;
        if (required <= buf.length) {
            return;
        }

        boolean enough = begin >= increment;
        compact();
        // Front free space is enough, just compact (data move forward)
        if (enough) {
            return;
        }

        // Otherwise expand capacity
        int newCapacity = Math.min(required + (required >> 1), maxCapacity);
        // Avoid ineffective expansion when maxCapacity equals current buffer length
        if (maxCapacity == buf.length) {
            return;
        }
        expandTo(newCapacity);
    }

    /**
     * Write a single byte to the buffer.
     * Note: This method does not check if the buffer is full.
     * If the buffer has reached maxCapacity and is full, an ArrayIndexOutOfBoundsException will be thrown.
     * For bounded writes, use {@link #write(byte[], int, int)} instead.
     *
     * @param b the byte to write
     * @throws ArrayIndexOutOfBoundsException if buffer is full and cannot expand
     */
    public void write(byte b) {
        incrementCapacity(1);
        buf[begin + count++] = b;
    }

    /**
     * Write all bytes from the specified array to the buffer.
     * Equivalent to {@code write(arr, 0, arr.length)}.
     *
     * @param bytes the source byte array
     * @return the actual number of bytes written
     * @see #write(byte[], int, int)
     */
    public int write(byte[] bytes) {
        return write(bytes, 0, bytes.length);
    }

    /**
     * Write bytes from the specified array to the buffer.
     * The actual number of bytes written may be less than requested if the buffer
     * cannot accommodate all the data (bounded by maxCapacity).
     * This method never throws an exception; instead, it returns the actual number
     * of bytes written (0 if the buffer is full).
     *
     * @param bytes  the source byte array
     * @param offset the starting offset in the source array
     * @param len    the maximum number of bytes to write
     * @return the actual number of bytes written, may be less than len
     */
    public int write(byte[] bytes, int offset, int len) {
        if (len <= 0) return 0;
        int writableSize = Math.min(maxCapacity - count, len);
        incrementCapacity(writableSize);
        System.arraycopy(bytes, offset, buf, begin + count, writableSize);
        count += writableSize;
        return writableSize;
    }

    /**
     * Append all bytes from the specified array to the buffer.
     * Equivalent to {@code append(bytes, 0, bytes.length)}.
     *
     * @param bytes the source byte array
     * @return this buffer, for chaining
     * @see #append(byte[], int, int)
     */
    public HttpBuf append(byte[] bytes) {
        return append(bytes, 0, bytes.length);
    }

    /**
     * Equivalent to {@code ByteArrayOutputStream.write(byte[], int, int)}.
     * Fast sequential write without maxCapacity bounds check or return value.
     * <p>
     * <b>Must only be called under trusted conditions (e.g. internal body append):</b>
     * <ul>
     *   <li>{@code begin} is guaranteed to be {@code 0} — if the buffer has been
     *       compacted ({@link #compact()}) or used with non-zero begin,
     *       this method will produce <b>incorrect results</b></li>
     *   <li>Caller ensures enough capacity or handles potential OOM — no
     *       {@code maxCapacity} guard is applied</li>
     *   <li>Sequential append only; random-access writes are not supported</li>
     * </ul>
     *
     * @param bytes  the source byte array
     * @param offset the starting offset in the source array
     * @param len    the number of bytes to write
     * @return this buffer, for chaining
     */
    public HttpBuf append(byte[] bytes, int offset, int len) {
        if (len == 0) return this;
        int newCount = count + len;
        if (newCount > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, newCount));
        }
        System.arraycopy(bytes, offset, buf, count, len);
        count = newCount;
        return this;
    }

    /**
     * Writes an ISO-8859-1 encoded string to the buffer.
     * Uses unsafe operations for better performance on JDK 9+.
     *
     * @param str the string to write
     */
    void writeISO_8859_1String(String str) {
        if (RuntimeEnv.JDK_VERSION >= 9f) {
            write((byte[]) HttpUnsafe.getStringValue(str));
        } else {
            int len = Math.min(maxCapacity - count, str.length());
            incrementCapacity(len);
            int offset = begin + count;
            for (int i = 0; i < len; ++i) {
                buf[offset + i] = (byte) str.charAt(i);
            }
            count += len;
        }
    }

    /**
     * Returns a copy of the valid bytes in this buffer.
     *
     * @return a new byte array containing the buffer content
     */
    public byte[] toBytes() {
        return Arrays.copyOfRange(buf, begin, begin + count);
    }

    /**
     * Returns a copy of the valid bytes and clears this buffer for reuse.
     *
     * @return a new byte array containing the buffer content
     */
    public byte[] toBytesAndClear() {
        try {
           return toBytes();
        } finally {
            clear();
        }
    }

    /**
     * Returns the last byte in the buffer.
     *
     * @return the last byte, or 0 if the buffer is empty
     */
    public byte lastByte() {
        return count == 0 ? 0 : buf[begin + count - 1];
    }

    /**
     * Returns the first byte in the buffer.
     *
     * @return the first byte
     */
    public byte firstByte() {
        return buf[begin];
    }

    /**
     * Checks if the buffer has remaining data.
     *
     * @return true if the buffer contains data, false otherwise
     */
    public boolean hasRemaining() {
        return count > 0;
    }

    /**
     * Checks if the buffer is empty.
     *
     * @return true if the buffer is empty, false otherwise
     */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Returns the number of valid bytes in the buffer.
     *
     * @return the current size of the buffer
     */
    public int size() {
        return count;
    }

    /**
     * Returns the current capacity of the internal buffer.
     *
     * @return the buffer capacity
     */
    public int capacity() {
        return buf.length;
    }

    /**
     * Clears the buffer by resetting the position and count.
     * The internal buffer is retained for reuse.
     */
    public void clear() {
        begin = 0;
        count = 0;
    }

    /**
     * Resets the buffer to its initial state.
     * The internal buffer is replaced with the original reset buffer.
     */
    public void reset() {
        clear();
        buf = resetBuf;
    }

    /**
     * Converts the buffer content to an ISO-8859-1 string.
     *
     * @return the string representation of the buffer
     */
    public String toISO_8859_1_string() {
        return HttpUnsafe.createAsciiString0(buf, begin, count);
    }

    /**
     * Converts the buffer content to an ISO-8859-1 string, optionally trimming
     * leading and trailing space/tab characters (HTTP OWS).
     *
     * @param trim true to remove leading/trailing spaces and tabs
     * @return the trimmed string representation
     */
    public String toISO_8859_1_string(boolean trim) {
        int off = begin;
        int cnt = count;
        if (trim) {
            while (cnt > 0 && buf[off] <= ' ') {
                ++off;
                --cnt;
            }
            while (cnt > 0 && buf[off + cnt - 1] <= ' ') {
                --cnt;
            }
        }
        return HttpUnsafe.createAsciiString0(buf, off, cnt);
    }

    /**
     * Converts a portion of the buffer content to an ISO-8859-1 string.
     *
     * @param offset the offset from the start of valid data
     * @param len    the length of the portion to convert
     * @return the string representation
     */
    public String toISO_8859_1_string(int offset, int len) {
        return HttpUnsafe.createAsciiString0(buf, begin + offset, len);
    }

    /**
     * Converts a portion of the buffer content to a string using the specified charset.
     *
     * @param offset  the offset from the start of valid data
     * @param len     the length of the portion to convert
     * @param charset the charset to use for decoding
     * @return the decoded string
     */
    public String toString(int offset, int len, Charset charset) {
        return new String(buf, begin + offset, len, charset);
    }

    /**
     * Convert the buffer content to a string using the specified charset.
     *
     * @param charset the charset to use for decoding
     * @return the decoded string
     */
    public String toString(Charset charset) {
        return new String(buf, begin, count, charset);
    }

    /**
     * Returns a string representation of the buffer content.
     * <p>
     * If the buffer size is at most 8192 bytes, returns the ISO-8859-1 decoded string;
     * otherwise falls back to {@link Object#toString()} to avoid large allocations.
     *
     * @return the string representation
     */
    public String toString() {
        if(count <= 8192) return toString(Utils.ISO_8859_1);
        return super.toString();
    }

    /**
     * Removes the last byte from the buffer.
     */
    public void backOne() {
        deleteTail(1);
    }

    /**
     * Deletes bytes from the end of the buffer.
     *
     * @param len the number of bytes to delete
     * @throws IndexOutOfBoundsException if len is negative or exceeds buffer size
     */
    public void deleteTail(int len) {
        if (len < 0 || len > count) {
            throw new IndexOutOfBoundsException("delete out of size, " + len);
        }
        count -= len;
    }

    /**
     * Deletes bytes from the beginning of the buffer.
     *
     * @param len the number of bytes to delete
     * @return the remaining size after deletion
     * @throws IndexOutOfBoundsException if len is negative or exceeds buffer size
     */
    public int deleteHead(int len) {
        if (len < 0 || len > count) {
            throw new IndexOutOfBoundsException("delete out of size, " + len);
        }
        begin += len;
        return count -= len;
    }

    /**
     * Sets the starting index of the valid data in the buffer.
     *
     * @param begin the new starting index
     */
    public void setBegin(int begin) {
        this.begin = begin;
    }

    /**
     * Returns the starting index of the valid data in the buffer.
     *
     * @return the starting index
     */
    public int getBegin() {
        return begin;
    }

    /**
     * Returns the maximum capacity of this buffer.
     *
     * @return the maximum capacity
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * Wraps the buffer content into a ByteBuffer.
     *
     * @return a ByteBuffer wrapping the valid data
     */
    public ByteBuffer byteBuffer() {
        return ByteBuffer.wrap(buf, begin, count);
    }

    /**
     * Replaces the internal buffer with the given byte array without copying.
     * This method provides zero-copy buffer replacement.
     *
     * @param data the byte array to use as the new internal buffer, or null to clear
     * @return this buffer, for chaining
     */
    public HttpBuf replace(byte[] data) {
        if (data == null) {
            clear();
            return this;
        }
        this.buf = data;
        this.begin = 0;
        this.count = data.length;
        return this;
    }

    /**
     * Replaces the internal buffer with a portion of the given byte array without copying.
     * This method provides zero-copy buffer replacement.
     *
     * @param data   the byte array to use as the new internal buffer, or null to clear
     * @param offset the starting offset in the data array
     * @param len    the length of the portion to use
     * @return this buffer, for chaining
     * @throws IndexOutOfBoundsException if offset or len is invalid
     */
    public HttpBuf replace(byte[] data, int offset, int len) {
        if (data == null) {
            clear();
            return this;
        }
        if (offset < 0 || len < 0 || offset + len > data.length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", len=" + len + ", data.length=" + data.length);
        }
        this.buf = data;
        this.begin = offset;
        this.count = len;
        return this;
    }

    /**
     * Returns the internal buffer array.
     * Note: The returned array may contain data beyond the valid range [begin, begin+count).
     *
     * @return the internal buffer array
     */
    public byte[] getBuf() {
        return buf;
    }

    /**
     * Sets the extra portion of the buffer.
     *
     * @param extra the extra portion
     * @return this buffer
     */
    public HttpBuf extra(int extra) {
        this.extra = extra;
        return this;
    }

    /**
     * Returns the extra portion of the buffer.
     *
     * @return the extra portion
     */
    public int extra() {
        return extra;
    }
}
