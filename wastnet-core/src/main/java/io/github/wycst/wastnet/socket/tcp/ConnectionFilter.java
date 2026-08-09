package io.github.wycst.wastnet.socket.tcp;

import java.nio.channels.SocketChannel;

/**
 * Connection filter for accepting or rejecting incoming TCP connections.
 *
 * <p>Invoked in the Acceptor thread immediately after {@code ServerSocketChannel.accept()},
 * before any resources (ByteBuffer, ChannelContext, worker registration) are allocated.
 * Returning {@code false} closes the connection with zero resource waste.
 *
 * @author wangyc
 */
public interface ConnectionFilter {

    /**
     * Decide whether to accept an incoming connection.
     *
     * @param channel the newly accepted socket channel
     * @return true to accept the connection, false to reject and close it
     * @throws Exception if filter execution fails (the connection will be rejected and closed)
     */
    boolean onAccept(SocketChannel channel) throws Exception;
}
