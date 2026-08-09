package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.channel.ChannelDecoder;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class HttpMessageDecoder extends ChannelDecoder<HttpMessage> {

    public abstract void decode(byte[] buf, int offset, int len, ChannelContext ctx) throws IOException;

    @Override
    public final void decode(ChannelContext ctx, ByteBuffer buf) throws IOException {
        throw new UnsupportedOperationException();
    }
}
