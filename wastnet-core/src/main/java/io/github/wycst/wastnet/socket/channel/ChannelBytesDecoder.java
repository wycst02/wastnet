package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p> Not accustomed to operating ByteBuffer objects, inheritable ChannelBytesReader traverses arrays
 * <p></p>
 * <p> If switching between BIG-ENDIAN and LITTLE-ENDIAN is involved, it is recommended to use ByteBuffer directly.
 * <p> Otherwise, the encoder needs to manually handle the conversion issue between BIG-ENDIAN and LITTLE-ENDIAN
 *
 * @since 2024-1-31
 * @author wangyc
 */
public abstract class ChannelBytesDecoder<E> extends ChannelDecoder<E> {

    /**
     * Operation to read byte array (zero copy)
     *
     * @param ctx        channel context
     * @param buf        target byte array
     * @param offset     starting offset
     * @param len        number of bytes to read
     * @throws IOException if read operation fails
     */
    public abstract void decode(ChannelContext ctx, byte[] buf, final int offset, int len) throws IOException;

    @Override
    public final void decode(ChannelContext ctx, ByteBuffer buf) throws IOException {
        // buf.arrayOffset();
        int len = buf.remaining();
        int offset = buf.position();
        buf.clear();
        decode(ctx, buf.array(), offset, len);
    }
}
