package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;

public interface ChannelHandler<T> {

    /**
     * Called when channel is connected (accept)
     *
     * @param ctx channel context
     * @throws IOException if connection handling fails
     */
    default void onConnected(ChannelContext ctx) throws IOException {
    }

    /**
     * Called when data reading is completed
     *
     * @param ctx     channel context
     * @param message encoded or aggregated object
     * @throws IOException if message handling fails
     */
    void onHandle(ChannelContext ctx, T message) throws IOException;

    /**
     * Called when channel is closed
     *
     * @param ctx channel context
     * @throws IOException if close handling fails
     */
    default void onClosed(ChannelContext ctx) throws IOException {
    }

    /**
     * Called when exception is caught
     *
     * @param context channel context
     * @param cause   the caught exception
     * @throws IOException if exception handling fails
     */
    default void onException(ChannelContext context, Throwable cause) throws IOException {
    }
}
