package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.util.Arrays;

/**
 * @since 2024-2-25
 * @author wangyc
 */
public abstract class ChannelDecoder<T> implements ChannelReader<T> {

    /**
     * Read len bytes starting from offset (automatically expand if length exceeds)
     *
     * @param ctx    channel context
     * @param buf    target byte array
     * @param offset starting offset
     * @param len    number of bytes to read
     * @return original array or expanded array
     * @throws IOException if read operation fails
     */
    public final byte[] read(ChannelContext ctx, byte[] buf, int offset, int len) throws IOException {
        return read(ctx, buf, offset, len, ctx.option(SocketOptions.READ_TIMEOUT_MS));
    }

    /**
     * Read len bytes starting from offset with custom timeout (automatically expand if length exceeds)
     *
     * @param ctx       channel context
     * @param buf       target byte array
     * @param offset    starting offset
     * @param len       number of bytes to read
     * @param timeoutMs read timeout in milliseconds
     * @return original array or expanded array
     * @throws IOException if read operation fails
     */
    public final byte[] read(ChannelContext ctx, byte[] buf, int offset, int len, long timeoutMs) throws IOException {
        int total = offset + len;
        if (buf.length < total) {
            buf = Arrays.copyOf(buf, total);
        }
        return readInternal(ctx, buf, offset, len, timeoutMs);
    }

    /**
     * Directly read len bytes from offset (without boundary checking)
     *
     * @param ctx    channel context
     * @param buf    target byte array
     * @param offset starting offset
     * @param len    number of bytes to read
     * @return original array or expanded array
     * @throws IOException if read operation fails
     */
    protected final byte[] readInternal(ChannelContext ctx, byte[] buf, int offset, int len) throws IOException {
        return readInternal(ctx, buf, offset, len, ctx.option(SocketOptions.READ_TIMEOUT_MS));
    }

    /**
     * Directly read len bytes from offset (without boundary checking)
     *
     * @param ctx       channel context
     * @param buf       target byte array
     * @param offset    starting offset
     * @param len       number of bytes to read
     * @param timeoutMs timeoutMs
     * @return original array or expanded array
     * @throws IOException if read operation fails
     */
    protected final byte[] readInternal(ChannelContext ctx, byte[] buf, int offset, int len, long timeoutMs) throws IOException {
        if (ctx.readFully(buf, offset, len, timeoutMs) == -1) {
            throw new IOException("channel is closed");
        }
        return buf;
    }

    /**
     * Read specified bytes
     *
     * @param ctx channel context
     * @param buf target byte array
     * @throws IOException if read operation fails
     */
    public final void read(ChannelContext ctx, byte[] buf) throws IOException {
        readInternal(ctx, buf, 0, buf.length);
    }
}
