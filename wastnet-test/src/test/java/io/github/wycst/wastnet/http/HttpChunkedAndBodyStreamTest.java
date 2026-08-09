package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketUtils;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure memory coverage tests for HttpChunkedStream and HttpBodyStreamDecoder.
 */
class HttpChunkedAndBodyStreamTest {

    // ==================== HttpChunkedStream ====================

    @Test
    void testReadUnsupported() {
        HttpChunkedStream stream = new HttpChunkedStream(new byte[0], mock(ChannelContext.class));
        assertThrows(UnsupportedOperationException.class, () -> stream.read());
    }

    @Test
    void testCompletedImmediateReturn() throws Exception {
        HttpChunkedStream stream = new HttpChunkedStream(new byte[0], mock(ChannelContext.class));
        stream.complete();
        stream.complete(); // second call: completed already true → return
    }

    // ==================== HttpBodyStreamDecoder ====================

    @Test
    void testDecoderNullStreamReturnsNull() {
        HttpBodyStreamDecoder d = new HttpBodyStreamDecoder("multipart/form-data; boundary=--b", null);
        assertNull(d.getMultipartFields("f"));
    }

    @Test
    void testDecoderSimpleMultipart() {
        byte[] body = ("--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nv\r\n--b--\r\n").getBytes();
        HttpBodyStreamDecoder d = new HttpBodyStreamDecoder("multipart/form-data; boundary=b",
                new ByteArrayInputStream(body));
        assertNotNull(d.getMultipartFields("f"));
    }

    @Test
    void testDecoderFormUrlencodedThrows() {
        HttpBodyStreamDecoder d = new HttpBodyStreamDecoder("application/x-www-form-urlencoded",
                new ByteArrayInputStream("a=1".getBytes()));
        assertThrows(IllegalStateException.class, d::decodeFormUrlencoded);
    }

    @Test
    void testDecoderGetMultipartFieldValues() {
        byte[] body = ("--b\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nv1\r\n--b\r\n"
                + "Content-Disposition: form-data; name=\"f\"\r\n\r\nv2\r\n--b--\r\n").getBytes();
        HttpBodyStreamDecoder d = new HttpBodyStreamDecoder("multipart/form-data; boundary=b",
                new ByteArrayInputStream(body));
        assertNotNull(d.getMultipartFieldValues("f"));
    }
}
