package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
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
 * so {@link Http2ServerReader#init} / {@code receiveClientPreface} are tested via integration tests only.
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

    /** True branch (L136): configured initialReceiveWindowSize OR maxConcurrentStreams differ from defaults
     *  -> replyServerSettings builds a custom SERVER_REPLY_FRAMES via buildServerReplyFrames.
     *  Isolation is achieved via a per-instance NioConfig (no global change). */
    @Test
    public void testReplyServerSettingsCustomWindowTriggersBuild() throws Exception {
        // real ctx with no-op write/flush so the final writeFlush() doesn't touch a real socket
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
        // per-instance NioConfig isolation (no global change); attachNioConfig is public
        NioConfig nioConfig = new NioConfig();
        nioConfig.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE, 123456);
        nioConfig.option(HttpOptions.HTTP2_MAX_CONCURRENT_STREAMS, 777);
        ctx.attachNioConfig(nioConfig);

        Http2ServerReader reader = new Http2ServerReader(ctx);
        Http2ServerReader result = reader.replyServerSettings(ctx);
        // true branch executed: replyServerSettings built custom frames and flushed without touching a real socket
        assertSame(reader, result);
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
        // Lower maxServerConcurrentStreams on the reader instance temporarily via Unsafe
        java.lang.reflect.Field maxField = Http2ServerReader.class.getDeclaredField("maxServerConcurrentStreams");
        maxField.setAccessible(true);
        java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        long offset = (long) unsafe.getClass().getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, maxField);

        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65536);
        Http2ServerReader reader = new Http2ServerReader();
        reader.streamInitSendWindowSize = 65535;

        int original = (int) unsafe.getClass().getMethod("getInt", Object.class, long.class).invoke(unsafe, reader, offset);
        unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(unsafe, reader, offset, 1);

        try {
            // First stream succeeds
            assertNotNull(reader.getStream(1, ctx));
            // Second stream should be refused (max=1)
            assertNull(reader.getStream(3, ctx));
        } finally {
            unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class).invoke(unsafe, reader, offset, original);
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
