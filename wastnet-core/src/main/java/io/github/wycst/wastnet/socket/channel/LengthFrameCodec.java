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
package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.conf.SocketConf;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Codec for length-prefixed frame protocols (TCP).
 * Handles TCP fragmentation, delivering complete frames via {@link #onFrame} and building
 * framed output via {@link #write}. Key override points: {@link #encodeBody} / {@link #decodeBody}
 * for body conversion, {@link #createHeader} / {@link #onFrame} for header/trailer control.
 * <p>
 * Length field can represent body length (default) or total frame length
 * ({@code lengthIncludesHeader = true}). Optional trailer (0-4 bytes) for integrity checks.
 * Default byte order is big-endian; use {@link #byteOrder(ByteOrder)} to change.
 *
 * @param <T> message type for write
 * @author wangyc
 */
public class LengthFrameCodec<T> extends ChannelCodec<T> {

    private final int headerLength;
    private final int lengthFieldOffset;
    private final int lengthFieldLength;
    private final int maxFrameLength;
    private final int bodyLengthBias;
    private final int trailerLength;
    private final long timeoutMs;
    protected ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
    protected boolean closeOnCodecError = true;

    /** Close connection on encode/decode error (default true). */
    public LengthFrameCodec<T> closeOnCodecError(boolean closeOnCodecError) {
        this.closeOnCodecError = closeOnCodecError;
        return this;
    }

    // body-length-only constructor, see full constructor
    public LengthFrameCodec(int headerLength, int lengthFieldOffset,
                            int lengthFieldLength, int maxFrameLength) {
        this(headerLength, lengthFieldOffset, lengthFieldLength, maxFrameLength, 0, false, ByteOrder.BIG_ENDIAN, SocketConf.READ_TIMEOUT_MS);
    }

    // see full constructor
    public LengthFrameCodec(int headerLength, int lengthFieldOffset,
                            int lengthFieldLength, int maxFrameLength,
                            boolean lengthIncludesHeader) {
        this(headerLength, lengthFieldOffset, lengthFieldLength, maxFrameLength, 0, lengthIncludesHeader, ByteOrder.BIG_ENDIAN, SocketConf.READ_TIMEOUT_MS);
    }

    // see full constructor
    public LengthFrameCodec(int headerLength, int lengthFieldOffset,
                            int lengthFieldLength, int maxFrameLength,
                            int trailerLength, boolean lengthIncludesHeader) {
        this(headerLength, lengthFieldOffset, lengthFieldLength, maxFrameLength, trailerLength, lengthIncludesHeader, ByteOrder.BIG_ENDIAN, SocketConf.READ_TIMEOUT_MS);
    }

    /**
     * @param headerLength         total header bytes
     * @param lengthFieldOffset    offset of length field within header
     * @param lengthFieldLength    length field bytes (1-4)
     * @param maxFrameLength       max total frame length
     * @param trailerLength        trailer bytes after body (0-4)
     * @param lengthIncludesHeader true if length field value includes header
     * @param byteOrder            big-endian or little-endian
     * @param timeoutMs            read timeout in milliseconds
     */
    public LengthFrameCodec(int headerLength, int lengthFieldOffset,
                            int lengthFieldLength, int maxFrameLength,
                            int trailerLength, boolean lengthIncludesHeader, ByteOrder byteOrder, long timeoutMs) {
        validateParameters(headerLength, lengthFieldOffset, lengthFieldLength, maxFrameLength, trailerLength);
        this.headerLength = headerLength;
        this.lengthFieldOffset = lengthFieldOffset;
        this.lengthFieldLength = lengthFieldLength;
        this.maxFrameLength = maxFrameLength;
        this.bodyLengthBias = lengthIncludesHeader ? -headerLength : 0;
        this.trailerLength = trailerLength;
        this.timeoutMs = timeoutMs;
        this.byteOrder = byteOrder;
    }

    /** Set byte order for length field (default BIG_ENDIAN). */
    public LengthFrameCodec<T> byteOrder(ByteOrder byteOrder) {
        if (byteOrder == null) throw new IllegalArgumentException("byteOrder cannot be null");
        this.byteOrder = byteOrder;
        return this;
    }

    // ==================== Read (decode) ====================

    @Override
    public final void decode(ChannelContext ctx, ByteBuffer buf) throws IOException {
        int len = buf.remaining();
        int offset = buf.position();
        buf.clear();
        decodeFrames(ctx, buf.array(), offset, len);
    }

    private void decodeFrames(ChannelContext ctx, byte[] data, int offset, int len) throws IOException {
        while (len > 0) {
            if (len < headerLength) {
                data = read(ctx, data, offset + len, headerLength - len, timeoutMs);
                len = headerLength;
            }
            int rawLength = readLength(data, offset + lengthFieldOffset);
            int bodyLength = rawLength + bodyLengthBias;
            if (bodyLength < 0) {
                throw new IOException("negative frame body length: " + bodyLength);
            }
            int actualHdrLen = computeActualHeaderLength(rawLength);
            int totalLength = actualHdrLen + bodyLength + trailerLength;
            if (totalLength > maxFrameLength) {
                throw new IOException("total frame length " + totalLength + " exceeds maxFrameLength " + maxFrameLength);
            }
            if (len < totalLength) {
                data = read(ctx, data, offset + len, totalLength - len, timeoutMs);
                len = totalLength;
            }
            byte[] frameHeader = Arrays.copyOfRange(data, offset, offset + actualHdrLen);
            byte[] frameBody = Arrays.copyOfRange(data, offset + actualHdrLen, offset + actualHdrLen + bodyLength);
            byte[] frameTrailer = trailerLength > 0 ? Arrays.copyOfRange(data, offset + actualHdrLen + bodyLength, offset + totalLength) : null;
            onFrame(ctx, frameHeader, frameBody, frameTrailer);
            offset += totalLength;
            len -= totalLength;
        }
    }

    /**
     * Process a fully decoded frame. Default delegates to {@link #decodeBody}.
     * Override for header inspection, trailer validation, or secondary decoding.
     */
    protected void onFrame(ChannelContext ctx, byte[] header, byte[] body, byte[] trailer) throws IOException {
        decodeBody(ctx, body);
    }

    /**
     * Decode body bytes. Default invokes {@code ctx.invokeHandle(body)}.
     * Override for body-only scenarios. Pairs with {@link #encodeBody}.
     */
    protected void decodeBody(ChannelContext ctx, byte[] body) throws IOException {
        ctx.invokeHandle(body);
    }

    /** Callback on protocol error. Default closes the connection. */
    protected void onProtocolError(ChannelContext ctx, Throwable cause) {
        ctx.close();
    }

    // ==================== Write (encode) ====================

    @Override
    public final ByteBuffer write(ChannelContext ctx, T message) throws IOException {
        byte[] body;
        try {
            body = encodeBody(ctx, message);
        } catch (Exception e) {
            if (closeOnCodecError) {
                onProtocolError(ctx, new IOException("encode failed", e));
            }
            return null;
        }
        if (body == null) {
            return null;
        }
        int wireLength = body.length - bodyLengthBias;
        int totalFrameLength = headerLength + body.length + trailerLength;
        if (totalFrameLength > maxFrameLength) {
            throw new IOException("total frame length " + totalFrameLength + " exceeds maxFrameLength " + maxFrameLength);
        }
        byte[] header = createHeader(wireLength);
        ByteBuffer buf = ByteBuffer.allocate(totalFrameLength);
        buf.put(header);
        buf.put(body);
        if (trailerLength > 0) {
            writeTrailer(buf.array(), headerLength + body.length);
            buf.position(totalFrameLength);
        }
        buf.flip();
        return buf;
    }

    /** Write trailer bytes (e.g. CRC16). Default zero-fills. Called when trailerLength > 0. */
    protected void writeTrailer(byte[] buf, int offset) {
        // default: zero-fill, subclasses override
    }

    /**
     * Build frame header. Default zero-fills and writes length field.
     * Override for additional fields (type, flags) or variable-length headers.
     */
    protected byte[] createHeader(int wireLength) {
        byte[] header = new byte[headerLength];
        writeLength(header, lengthFieldOffset, wireLength);
        return header;
    }

    /**
     * Convert message to body bytes. Default handles byte[]; override for String/POJO.
     * Errors caught by {@link #write} and handled per {@link #closeOnCodecError}.
     */
    protected byte[] encodeBody(ChannelContext ctx, T message) throws Exception {
        if (message instanceof byte[]) {
            return (byte[]) message;
        }
        throw new UnsupportedOperationException("encodeBody must be overridden for " + message.getClass());
    }

    // ==================== Length field helpers ====================

    /** Parse length field. Override for custom encoding (varint, etc.). */
    protected int readLength(byte[] buf, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(buf, offset, lengthFieldLength).order(byteOrder);
        switch (lengthFieldLength) {
            case 1:
                return buf[offset] & 0xFF;
            case 2:
                return buffer.getShort() & 0xFFFF;
            case 3:
                if (byteOrder == ByteOrder.BIG_ENDIAN) {
                    return ((buf[offset] & 0xFF) << 16) | ((buf[offset + 1] & 0xFF) << 8) | (buf[offset + 2] & 0xFF);
                } else {
                    return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8) | ((buf[offset + 2] & 0xFF) << 16);
                }
            case 4:
                return buffer.getInt();
            default:
                throw new IllegalStateException("unsupported lengthFieldLength: " + lengthFieldLength);
        }
    }

    /** Actual header size for current frame. Override for variable-length headers. */
    protected int computeActualHeaderLength(int rawLength) {
        return headerLength;
    }

    /** Write length field. Override for custom encoding. Used by {@link #createHeader}. */
    protected void writeLength(byte[] buf, int offset, int value) {
        ByteBuffer buffer = ByteBuffer.wrap(buf, offset, lengthFieldLength).order(byteOrder);
        switch (lengthFieldLength) {
            case 1:
                buf[offset] = (byte) value;
                break;
            case 2:
                buffer.putShort((short) value);
                break;
            case 3:
                if (byteOrder == ByteOrder.BIG_ENDIAN) {
                    buf[offset] = (byte) (value >> 16);
                    buf[offset + 1] = (byte) (value >> 8);
                    buf[offset + 2] = (byte) value;
                } else {
                    buf[offset] = (byte) value;
                    buf[offset + 1] = (byte) (value >> 8);
                    buf[offset + 2] = (byte) (value >> 16);
                }
                break;
            case 4:
                buffer.putInt(value);
                break;
            default:
                throw new IllegalStateException("unsupported lengthFieldLength: " + lengthFieldLength);
        }
    }

    protected final int readInt(byte[] buf, int offset) {
        return ByteBuffer.wrap(buf, offset, 4).order(byteOrder).getInt();
    }

    protected final short readShort(byte[] buf, int offset) {
        return ByteBuffer.wrap(buf, offset, 2).order(byteOrder).getShort();
    }

    protected final void writeInt(byte[] buf, int offset, int value) {
        ByteBuffer.wrap(buf, offset, 4).order(byteOrder).putInt(value);
    }

    protected final void writeShort(byte[] buf, int offset, short value) {
        ByteBuffer.wrap(buf, offset, 2).order(byteOrder).putShort(value);
    }

    /** Validate constructor parameter consistency. */
    static void validateParameters(int headerLength, int lengthFieldOffset,
                                    int lengthFieldLength, int maxFrameLength,
                                    int trailerLength) {
        if (headerLength < 1) throw new IllegalArgumentException("headerLength must be >= 1");
        if (lengthFieldOffset < 0 || lengthFieldOffset >= headerLength)
            throw new IllegalArgumentException("lengthFieldOffset out of header range");
        if (lengthFieldLength < 1 || lengthFieldLength > 4)
            throw new IllegalArgumentException("lengthFieldLength must be 1-4");
        if (lengthFieldOffset + lengthFieldLength > headerLength)
            throw new IllegalArgumentException("length field (offset " + lengthFieldOffset + " + length " + lengthFieldLength + ") exceeds header length " + headerLength);
        if (maxFrameLength <= 0)
            throw new IllegalArgumentException("maxFrameLength must be > 0, got " + maxFrameLength);
        if (trailerLength < 0 || trailerLength > 4)
            throw new IllegalArgumentException("trailerLength must be 0-4, got " + trailerLength);
        long fieldMaxLength = (1L << (lengthFieldLength << 3)) - 1;
        if ((long) maxFrameLength > fieldMaxLength) {
            throw new IllegalArgumentException("maxFrameLength " + maxFrameLength
                    + " exceeds maximum for lengthFieldLength " + lengthFieldLength
                    + " (max " + fieldMaxLength + ")");
        }
    }
}
