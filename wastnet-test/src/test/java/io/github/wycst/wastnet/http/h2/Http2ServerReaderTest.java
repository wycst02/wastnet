package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2ServerReader}.
 * <p>
 * Note: {@code ChannelContext.readFully(byte[])} is a final method and cannot be mocked,
 * so {@link #init} / {@code receiveClientPreface} are tested via integration tests only.
 *
 * @author wangyc
 */
public class Http2ServerReaderTest {

    // ==================== replyServerSettings ====================

    @Test
    public void testReplyServerSettingsWritesAndReturnsSelf() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = new Http2ServerReader();

        Http2ServerReader result = reader.replyServerSettings(ctx);
        assertSame(reader, result);
        verify(ctx).writeFlush(Http2ServerReader.SERVER_REPLY_FRAMES);
    }

    // ==================== getStream ====================

    @Test
    public void testGetStreamCreatesNewStreamForOddId() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        Http2ServerReader reader = new Http2ServerReader();
        reader.streamInitSendWindowSize = 65535;

        Http2Stream stream = reader.getStream(1, ctx);
        assertNotNull(stream);
        assertTrue(stream instanceof Http2ServerStream);
        assertEquals(1, stream.streamId);
    }

    @Test
    public void testGetStreamReturnsExistingStream() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        Http2ServerReader reader = new Http2ServerReader();
        reader.streamInitSendWindowSize = 65535;

        Http2Stream first = reader.getStream(1, ctx);
        Http2Stream second = reader.getStream(1, ctx);
        assertSame(first, second);
    }

    @Test
    public void testGetStreamReturnsNullForEvenStreamId() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = new Http2ServerReader();
        reader.streamInitSendWindowSize = 65535;

        Http2Stream stream = reader.getStream(2, ctx);
        assertNull(stream);
    }

    @Test
    public void testGetStreamReturnsNullForStreamIdLessThanCurrentMax() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = new Http2ServerReader();
        reader.streamInitSendWindowSize = 65535;

        reader.getStream(5, ctx);
        reader.getStream(7, ctx);
        Http2Stream stream = reader.getStream(3, ctx);
        assertNull(stream);
    }

    @Test
    public void testGetStreamReturnsNullForRefusedStreamWhenMaxConcurrentExceeded() throws Exception {
        // Lower MAX_SERVER_CONCURRENT_STREAMS temporarily via Unsafe
        java.lang.reflect.Field maxField = Http2ServerReader.class.getDeclaredField("MAX_SERVER_CONCURRENT_STREAMS");
        maxField.setAccessible(true);
        java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        long offset = (long) unsafe.getClass().getMethod("staticFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, maxField);
        Object base = unsafe.getClass().getMethod("staticFieldBase", java.lang.reflect.Field.class).invoke(unsafe, maxField);
        int original = (int) unsafe.getClass().getMethod("getInt", Object.class, long.class).invoke(unsafe, base, offset);
        unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(unsafe, base, offset, 1);

        try {
            ChannelContext ctx = mock(ChannelContext.class);
            when(ctx.getWriteBufferSize()).thenReturn(65536);
            Http2ServerReader reader = new Http2ServerReader();
            reader.streamInitSendWindowSize = 65535;

            // First stream succeeds
            assertNotNull(reader.getStream(1, ctx));
            // Second stream should be refused (max=1)
            assertNull(reader.getStream(3, ctx));
        } finally {
            unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(unsafe, base, offset, original);
        }
    }

    // ==================== init() with real TCP ====================

    /**
     * Client closes without writing → readFully returns -1 → valid=false, ctx.close().
     */
    @Test
    public void testInitPrefaceFromClosedChannel() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        client.close();

        Http2ServerReader reader = new Http2ServerReader();
        ChannelContext ctx = new ChannelContext(server, 4096);
        reader.init(ctx);
        assertFalse(reader.valid);
        try { server.close(); } catch (Exception ignored) {}
    }

    /**
     * Client writes invalid preface bytes → validatePreface fails → valid=false.
     */
    @Test
    public void testInitPrefaceWithInvalidBytes() throws Exception {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ssc.socket().getLocalPort();
        SocketChannel client = SocketChannel.open();
        client.connect(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = ssc.accept();
        ssc.close();
        // Write 24 wrong preface bytes
        client.write(ByteBuffer.wrap(new byte[24]));
        client.close();

        Http2ServerReader reader = new Http2ServerReader();
        ChannelContext ctx = new ChannelContext(server, 4096);
        reader.init(ctx);
        assertFalse(reader.valid);
        try { server.close(); } catch (Exception ignored) {}
    }
}
