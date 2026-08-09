package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for Http2ServerStream, Http2ClientStream, Http2ClientReader, Http2Request.
 */
public class Http2SmallCoverageTest {

    private static Http2MessageReader createReader() {
        // Use a test instance with proper fields
        return new Http2MessageReader() {
            @Override
            public void init(ChannelContext ctx) throws Exception {}
            @Override
            protected Http2Stream getStream(int streamId, ChannelContext ctx) { return null; }
        };
    }

    @Test
    public void testHttp2ServerStreamConstructor() throws Exception {
        Http2MessageReader reader = createReader();
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        assertEquals("Server ", stream.debugPrefix());
        ch.close();
    }

    @Test
    public void testHttp2ClientStreamConstructor() throws Exception {
        Http2MessageReader reader = createReader();
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        Http2ClientStream stream = new Http2ClientStream(reader, 3, ctx);
        assertEquals("Client ", stream.debugPrefix());
        ch.close();
    }
}
