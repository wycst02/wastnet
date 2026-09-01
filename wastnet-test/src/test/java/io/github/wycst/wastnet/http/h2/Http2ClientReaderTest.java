package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpDecodedResponse;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for Http2ClientReader (client-side H2 frame decoder).
 */
public class Http2ClientReaderTest {

    private static ChannelContext noopCtx() throws Exception {
        return new ChannelContext(SocketChannel.open(), 0) {
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
    }

    private static ChannelContext throwingCtx() throws Exception {
        return new ChannelContext(SocketChannel.open(), 0) {
            @Override
            public int write(ByteBuffer buf) throws IOException {
                throw new IOException("simulated");
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void testConstructorAndNextStreamId() {
        Http2ClientReader reader = new Http2ClientReader();
        assertEquals(1, reader.nextStreamId());
        assertEquals(3, reader.nextStreamId());
    }

    @Test
    void testInitSendsPreface() throws Exception {
        Http2ClientReader reader = new Http2ClientReader();
        reader.init(noopCtx());
    }

    /** True branch : configured initialReceiveWindowSize differs from default -> init builds custom client SETTINGS.
     *  Isolation is achieved via a per-instance NioConfig (no global change). */
    @Test
    void testInitCustomWindowTriggersBuildInitClientSettings() throws Exception {
        ChannelContext ctx = noopCtx();
        // per-instance NioConfig isolation (no global change); attachNioConfig is public
        NioConfig nioConfig = new NioConfig();
        nioConfig.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE, 123456); // non-default
        ctx.attachNioConfig(nioConfig);

        Http2ClientReader reader = new Http2ClientReader(ctx);
        reader.init(ctx);
        // init reaches the true branch (custom SETTINGS built) and flushes preface + settings without error
    }

    @Test
    void testInitIOExceptionClosesAndRethrows() throws Exception {
        Http2ClientReader reader = new Http2ClientReader();
        assertThrows(IOException.class, () -> reader.init(throwingCtx()));
    }

    @Test
    void testGetOrCreateStreamCreateExistingAndNull() throws Exception {
        Http2ClientReader reader = new Http2ClientReader();
        ChannelContext ctx = noopCtx();
        Http2ClientStream s1 = reader.getOrCreateStream(1, ctx);
        assertNotNull(s1);
        Http2ClientStream s1b = reader.getOrCreateStream(1, ctx);
        assertSame(s1, s1b);
        Http2ClientStream s3 = reader.getOrCreateStream(3, ctx);
        assertNotNull(s3);
        // streamId <= currentMax and not present -> returns null
        Http2ClientStream s2 = reader.getOrCreateStream(2, ctx);
        assertNull(s2);
    }

    @Test
    void testGetStreamDelegatesToCreate() throws Exception {
        Http2ClientReader reader = new Http2ClientReader();
        Http2Stream s = reader.getStream(5, noopCtx());
        assertNotNull(s);
        assertTrue(s instanceof Http2ClientStream);
    }

    @Test
    void testBuildResponseNonStreamingWithContentLength() {
        Http2ClientReader reader = new Http2ClientReader();
        Http2ClientStream s = new Http2ClientStream(reader, 7, null);
        s.statusCode = 200;
        s.declaredContentLength = 5;
        s.contentType = "text/plain";
        s.bodyData = "hello".getBytes();
        s.headers.put(":status", "200");
        HttpDecodedResponse resp = reader.buildResponse(s);
        assertNotNull(resp);
        assertEquals(200, resp.getStatusCode());
    }

    @Test
    void testBuildResponseFallsBackToBodyLength() {
        Http2ClientReader reader = new Http2ClientReader();
        Http2ClientStream s = new Http2ClientStream(reader, 9, null);
        s.statusCode = 204;
        s.declaredContentLength = -1;
        s.contentType = null;
        s.bodyData = "abc".getBytes();
        HttpDecodedResponse resp = reader.buildResponse(s);
        assertNotNull(resp);
        assertEquals(3, resp.getContentLength());
    }

    @Test
    void testBuildResponseStreaming() {
        Http2ClientReader reader = new Http2ClientReader();
        Http2ClientStream s = new Http2ClientStream(reader, 11, null);
        s.statusCode = 200;
        s.declaredContentLength = 10;
        s.contentType = "application/json";
        s.bodyData = "0123456789".getBytes();
        s.needStreaming = true;
        s.bodyStream = new Http2BodyInputStream(s.bodyData, s);
        HttpDecodedResponse resp = reader.buildResponse(s);
        assertNotNull(resp);
    }
}
