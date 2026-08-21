package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * ByteBuffer decoder that converts raw channel data into handler-processable objects.
 * Not required - if not specified, all decoding work is delegated to the handler.
 *
 * @since 2024-1-21
 * @author wangyc
 */
public interface ChannelReader<T> {

    ChannelReader<ByteBuffer> UNDO = ChannelContext::invokeHandle;

    /**
     * Called before the channel is ready
     *
     * @param ctx channel context
     * @throws Exception if initialization fails
     */
    default void init(ChannelContext ctx) throws Exception {
    }

    /**
     * Decode data from the channel buffer into structured objects
     *
     * @param ctx        channel context
     * @param buf        buffer containing raw data
     * @throws IOException if decoding fails
     */
    void decode(ChannelContext ctx, ByteBuffer buf) throws IOException;

    /**
     * Wake up the decoder (e.g., to handle buffered data)
     */
    default void wakeup() {
    }

    /**
     * Called after the channel is closed, symmetric to {@link #init(ChannelContext)}.
     * Override to release per-connection resources (e.g. unblock pending readers).
     *
     * @param ctx channel context
     */
    default void onClosed(ChannelContext ctx) {
    }
}