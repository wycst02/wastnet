package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for Http2ClientStream (client-side H2 response stream context).
 */
public class Http2ClientStreamTest {

    private static ChannelContext noopCtx(ChannelHandler<Object> handler) throws Exception {
        ChannelContext ctx = new ChannelContext(SocketChannel.open(), 0) {
            @Override
            public int write(ByteBuffer buf) {
                return buf.remaining();
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        ctx.setChannelHandler(handler);
        return ctx;
    }

    @Test
    void testDebugPrefix() {
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, null);
        assertEquals("Client ", s.debugPrefix());
    }

    @Test
    void testOnEndHeadersNormal() {
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, null);
        s.headers.put(":status", "200");
        s.headers.put("content-type", "text/plain");
        s.headers.put("content-length", "5");
        s.endHeaders();
        assertEquals(200, s.getStatusCode());
        assertFalse(s.isServerProtocolError());
        assertEquals("text/plain", s.contentType);
    }

    @Test
    void testOnEndHeadersNoContentLength() {
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, null);
        s.headers.put(":status", "404");
        s.endHeaders();
        assertEquals(404, s.getStatusCode());
        assertFalse(s.isServerProtocolError());
    }

    @Test
    void testOnEndHeadersBadStatusTriggersProtocolError() {
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, null);
        s.headers.put(":status", "abc");
        s.endHeaders();
        assertTrue(s.isServerProtocolError());
    }

    @Test
    void testOnEndHeadersMissingStatusTriggersProtocolError() {
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, null);
        s.endHeaders();
        assertTrue(s.isServerProtocolError());
    }

    @Test
    void testSubmitInvokesHandle() throws Exception {
        final boolean[] invoked = {false};
        ChannelHandler<Object> handler = new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext ctx, Object msg) {
                invoked[0] = true;
            }
        };
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, noopCtx(handler));
        s.submit();
        assertTrue(invoked[0]);
        assertTrue(s.requestInvoked);
    }

    @Test
    void testSubmitHandleThrowsIoIgnored() throws Exception {
        ChannelHandler<Object> handler = new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext ctx, Object msg) throws IOException {
                throw new IOException("x");
            }
        };
        Http2ClientStream s = new Http2ClientStream(new Http2ClientReader(), 1, noopCtx(handler));
        s.submit();
        assertTrue(s.requestInvoked);
    }
}
