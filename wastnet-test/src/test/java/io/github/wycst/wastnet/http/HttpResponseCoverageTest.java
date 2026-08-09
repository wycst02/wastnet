package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for HttpDefaultResponse, HttpBodyStreamDecoder, HttpChunkedStream.
 */
public class HttpResponseCoverageTest {

    private ChannelContext createCtx() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {}
        });
        return ctx;
    }

    private HttpDefaultRequest createRequest(ChannelContext ctx) {
        return new HttpDefaultRequest(
                HttpMethod.GET, "/".getBytes(), "/", Collections.<String, List<String>>emptyMap(),
                HttpVersion.HTTP_1_1, Collections.<String, Object>singletonMap("Host", "localhost"),
                new byte[0], 0, null, ctx);
    }

    private HttpDefaultResponse createResponse() throws Exception {
        ChannelContext ctx = createCtx();
        return (HttpDefaultResponse) createRequest(ctx).getResponse();
    }

    // ==================== HttpDefaultResponse / HttpCompleteResponse ====================

    @Test
    public void testDefaultResponseBasic() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.status(200).contentType("text/plain").contentLength(10);
        assertEquals("text/plain", resp.getContentType());
        assertEquals(10, resp.getContentLength());
    }

    @Test
    public void testDefaultResponseBody() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.body("hello".getBytes());
        resp.body(" world");
    }

    @Test
    public void testDefaultResponseSetStatusAndText() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.setStatusAndText(HttpStatus.NOT_FOUND);
    }

    @Test
    public void testDefaultResponseAddHeader() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.addHeader("X-Custom", "val1");
        resp.addHeader("X-Custom", "val2");
        assertEquals("val1", resp.getHeader("x-custom"));
    }

    @Test
    public void testDefaultResponseContentLengthHeader() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.addHeader("Content-Length", "42");
        assertEquals(42, resp.getContentLength());
    }

    @Test
    public void testDefaultResponseContentLengthInvalid() throws Exception {
        HttpDefaultResponse resp = createResponse();
        assertThrows(IllegalArgumentException.class, () -> resp.addHeader("Content-Length", "abc"));
    }

    @Test
    public void testDefaultResponseContentLengthNegative() throws Exception {
        HttpDefaultResponse resp = createResponse();
        assertThrows(IllegalArgumentException.class, () -> resp.addHeader("Content-Length", "-5"));
    }

    @Test
    public void testDefaultResponseChunkedWithContentLength() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.setChunked(true);
        assertThrows(IllegalStateException.class, () -> resp.addHeader("Content-Length", "100"));
    }

    @Test
    public void testDefaultResponseRedirect() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.addHeader("Location", "/redirect-target");
    }

    @Test
    public void testDefaultResponseMultipleStatusCalls() throws Exception {
        HttpDefaultResponse resp = createResponse();
        resp.status(HttpStatus.CREATED);
        resp.status(201);
    }

    // ==================== ChunkedOutputStream via streamingGzipCompress ====================

    @Test
    public void testChunkedOutputStreamViaGzip() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(0));
        int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("localhost", port));
        ch.configureBlocking(false);
        SocketChannel accepted = ssc.accept();
        accepted.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {}
        });
        HttpDefaultRequest req = new HttpDefaultRequest(
                HttpMethod.GET, "/".getBytes(), "/", Collections.<String, List<String>>emptyMap(),
                HttpVersion.HTTP_1_1, Collections.<String, Object>singletonMap("Host", "localhost"),
                new byte[0], 0, null, ctx);
        HttpDefaultResponse resp = (HttpDefaultResponse) req.getResponse();
        resp.setChunked(true);
        // streamingGzipCompress wraps ChunkedOutputStream with GZIPOutputStream
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream("hello".getBytes())) {
            resp.streamingGzipCompress(in);
        } finally {
            accepted.close();
            ch.close();
            ssc.close();
        }
    }

    // ==================== HttpBodyStreamDecoder ====================

    @Test
    public void testBodyStreamDecoderNullStream() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder("multipart/form-data; boundary=--b", null);
        assertNull(decoder.getMultipartFields("f"));
    }

    @Test
    public void testBodyStreamDecoderSimpleMultipart() throws Exception {
        byte[] body = "--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nv\r\n--b--\r\n".getBytes();
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=b",
                new ByteArrayInputStream(body));
        assertNotNull(decoder.getMultipartFields("f"));
    }

    @Test
    public void testBodyStreamDecoderFormUrlencoded() throws Exception {
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded",
                new ByteArrayInputStream("a=1".getBytes()));
        assertThrows(IllegalStateException.class, decoder::decodeFormUrlencoded);
    }

    // ==================== HttpChunkedStream ====================

    @Test
    public void testChunkedStreamCompleted() throws Exception {
        ChannelContext ctx = createCtx();
        HttpChunkedStream stream = new HttpChunkedStream(new byte[0], ctx);
        stream.complete();
        assertEquals(-1, stream.read(new byte[10], 0, 10));
    }

    @Test
    public void testChunkedStreamBasicRead() throws Exception {
        ChannelContext ctx = createCtx();
        byte[] data = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(data, ctx);
        byte[] buf = new byte[10];
        int n = stream.read(buf, 0, 10);
        assertEquals(5, n);
        assertEquals("hello", new String(buf, 0, n));
    }

    @Test
    public void testChunkedStreamCompleteTwice() throws Exception {
        ChannelContext ctx = createCtx();
        HttpChunkedStream stream = new HttpChunkedStream(new byte[0], ctx);
        stream.complete();
        stream.complete();
    }

    @Test
    public void testChunkedStreamPartial() throws Exception {
        ChannelContext ctx = createCtx();
        byte[] data = "5\r\nhel".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(data, ctx);
        assertThrows(Exception.class, () -> stream.read(new byte[10], 0, 10));
    }
}
