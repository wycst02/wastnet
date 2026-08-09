package io.github.wycst.wastnet.socket.channel;

/**
 * @since 2024-1-21
 * @author wangyc
 */
public interface ChannelReaderFactory {

    /**
     * create reader
     *
     */
    ChannelReader<?> getChannelReader();
}
