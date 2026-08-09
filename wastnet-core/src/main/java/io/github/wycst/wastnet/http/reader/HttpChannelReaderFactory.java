package io.github.wycst.wastnet.http.reader;

import io.github.wycst.wastnet.http.HttpMessage;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.channel.ChannelReaderFactory;

public class HttpChannelReaderFactory implements ChannelReaderFactory {
    @Override
    public ChannelReader<HttpMessage> getChannelReader() {
        return new HttpChannelProtocolReader();
    }
}
