package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Coverage tests for HttpInternalRequest edge cases, HttpConf config branches, and RuntimeEnv.
 */
class BaseHttpAndConfigTest {

    // ==================== HttpInternalRequest ====================

    @Test
    void testRemoteHostNullAddress() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getRemoteAddress()).thenReturn(null);
        // DefaultRequest with null remoteAddress
        HttpRequest req = createRequest(mockCtx);
        assertNull(req.getRemoteHost());
    }

    @Test
    void testRemotePortNullAddress() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getRemoteAddress()).thenReturn(null);
        HttpRequest req = createRequest(mockCtx);
        assertEquals(-1, req.getRemotePort());
    }

    @Test
    void testServerHostNullAddress() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getLocalAddress()).thenReturn(null);
        HttpRequest req = createRequest(mockCtx);
        assertNull(req.getServerHost());
    }

    @Test
    void testServerPortNullAddress() {
        ChannelContext mockCtx = mock(ChannelContext.class);
        when(mockCtx.getLocalAddress()).thenReturn(null);
        HttpRequest req = createRequest(mockCtx);
        assertEquals(-1, req.getServerPort());
    }

    @Test
    void testGetFullHeaderNull() {
        HttpRequest req = createRequest(mock(ChannelContext.class));
        assertNull(req.getFullHeader("Nonexistent"));
    }

    // ==================== HttpConf reflection ====================

    // ==================== HttpMessageDecoder ====================

    @Test
    void testHttpMessageDecoderByteBufferThrows() {
        // HttpMessageDecoder.decode(ChannelContext, ByteBuffer) is final and throws
        HttpMessageDecoder decoder = new HttpMessageDecoder() {
            @Override public void decode(byte[] buf, int offset, int len, ChannelContext ctx) {}
        };
        assertThrows(UnsupportedOperationException.class,
                () -> decoder.decode(null, null));
    }

    // ==================== Helper ====================

    private static HttpRequest createRequest(ChannelContext ctx) {
        return new HttpDefaultRequest(
                HttpMethod.GET, "/".getBytes(), "/",
                new HashMap<String, java.util.List<String>>(),
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                new byte[0], 0, null, ctx);
    }
}
