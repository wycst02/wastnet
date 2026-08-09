package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

/**
 * codec for reader and writer
 *
 * @since 2024-2-24
 * @author wangyc
 */
public abstract class ChannelCodec<T> extends ChannelDecoder<T> implements ChannelReader<T>, ChannelWriter<T> {

    @Override
    public void init(ChannelContext ctx) throws Exception {
    }

    @Override
    public void wakeup() {
    }
}
