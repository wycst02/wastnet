package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p>channel writer </p>
 *
 * @since 2024-1-21
 * @author wangyc
 */
public interface ChannelWriter<T> {

    /**
     * write to ByteBuffer with channel context for error handling.
     *
     * @param ctx     the channel context, may be null
     * @param message the message to encode
     * @return encoded ByteBuffer, or null if encoding failed and discarded
     */
    ByteBuffer write(ChannelContext ctx, T message) throws IOException;

}
