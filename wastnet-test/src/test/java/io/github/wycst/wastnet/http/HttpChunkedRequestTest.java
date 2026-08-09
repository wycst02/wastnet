package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Covers {@link HttpChunkedRequest#delegateBody(ChannelContext)} which re-encodes a chunked
 * request body into chunked framing on the target channel (proxy forwarding path).
 */
public class HttpChunkedRequestTest {

    // Expose the protected delegateBody hook.
    private static class ExposedHttpChunkedRequest extends HttpChunkedRequest {
        ExposedHttpChunkedRequest(byte[] body, ChannelContext ctx) {
            super(HttpMethod.POST, "a".getBytes(), "/", java.util.Collections.emptyMap(),
                    HttpVersion.HTTP_1_1, java.util.Collections.emptyMap(), body, 0, null, ctx);
        }
        void doDelegateBody(ChannelContext targetCtx) throws Throwable {
            delegateBody(targetCtx);
        }
    }

    // Captures every write(byte[]) / write(byte[],off,len) by routing through write(ByteBuffer).
    private static class CaptureCtx extends ChannelContext {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        CaptureCtx() throws Exception {
            super(SocketChannel.open(), 4096);
        }
        @Override
        public int write(ByteBuffer buf) throws java.io.IOException {
            byte[] tmp = new byte[buf.remaining()];
            buf.get(tmp);
            out.write(tmp);
            return tmp.length;
        }
    }

    @Test
    public void testDelegateBodyReEncodesChunks() throws Throwable {
        // Full chunked body kept in memory so the stream never touches the real socket.
        byte[] chunked = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".getBytes("ISO-8859-1");
        CaptureCtx src = new CaptureCtx();
        ExposedHttpChunkedRequest req = new ExposedHttpChunkedRequest(chunked, src);

        CaptureCtx target = new CaptureCtx();
        req.doDelegateBody(target);

        String output = new String(target.out.toByteArray(), "ISO-8859-1");
        // HttpChunkedStream may coalesce adjacent source chunks, so only the decoded payload
        // and the trailing end marker must be preserved. "hello world" = 11 (0xb) bytes.
        Assertions.assertEquals("b\r\nhello world\r\n0\r\n\r\n", output);
    }
}
