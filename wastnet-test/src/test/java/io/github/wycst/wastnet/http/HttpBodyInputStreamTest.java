package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HttpBodyInputStream} — streaming request body reader.
 */
@SuppressWarnings("resource")
public class HttpBodyInputStreamTest {

    private static ChannelContext mockChannel(byte[] channelData, boolean closed) throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(closed);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    int available = channelData.length;
                    int copyLen = Math.min(len, available);
                    if (copyLen <= 0) return -1;
                    System.arraycopy(channelData, 0, buf, off, copyLen);
                    // Consume copied bytes from channel data for subsequent calls
                    byte[] remaining = new byte[available - copyLen];
                    if (remaining.length > 0) {
                        System.arraycopy(channelData, copyLen, remaining, 0, remaining.length);
                    }
                    // Update the mock's behavior for next call
                    when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                            .thenAnswer(inv -> {
                                byte[] b = inv.getArgument(0);
                                int o = inv.getArgument(1);
                                int l = inv.getArgument(2);
                                int av = remaining.length;
                                int cl = Math.min(l, av);
                                if (cl <= 0) return -1;
                                System.arraycopy(remaining, 0, b, o, cl);
                                byte[] rem2 = new byte[av - cl];
                                if (rem2.length > 0) System.arraycopy(remaining, cl, rem2, 0, rem2.length);
                                when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                                        .thenAnswer(inv2 -> rem2.length > 0 ? -1 : -1);
                                return cl;
                            });
                    return copyLen;
                });
        return ctx;
    }

    @Test
    public void testReadFullyFromPreReadBuffer() throws IOException {
        byte[] preRead = "hello".getBytes();
        ChannelContext ctx = mockChannel(new byte[0], false);
        HttpBodyInputStream stream = new HttpBodyInputStream(5, preRead, ctx);

        byte[] buf = new byte[5];
        int n = stream.read(buf, 0, 5);

        Assertions.assertEquals(5, n);
        Assertions.assertArrayEquals("hello".getBytes(), buf);
        Assertions.assertEquals(5, stream.pos);
        Assertions.assertFalse(stream.completed);
    }

    @Test
    public void testReadFullyFromBufferThenChannel() throws IOException {
        byte[] preRead = "hel".getBytes();
        ChannelContext ctx = mockChannel("lo!".getBytes(), false);
        HttpBodyInputStream stream = new HttpBodyInputStream(6, preRead, ctx);

        byte[] buf = new byte[6];
        int n = stream.read(buf, 0, 6);

        Assertions.assertEquals(6, n);
        Assertions.assertArrayEquals("hello!".getBytes(), buf);
        Assertions.assertTrue(stream.checkCompleted());
    }

    @Test
    public void testReadFullyReturnsMinusOneWhenCompleted() throws IOException {
        ChannelContext ctx = mockChannel(new byte[0], false);
        HttpBodyInputStream stream = new HttpBodyInputStream(0, new byte[0], ctx);
        stream.markCompleted();

        byte[] buf = new byte[4];
        int n = stream.read(buf, 0, 4);

        Assertions.assertEquals(-1, n);
    }

    @Test
    public void testReadFullyReturnsPartialWhenChannelClosed() throws IOException {
        byte[] preRead = "abc".getBytes();
        ChannelContext ctx = mockChannel(new byte[0], false);
        when(ctx.isChannelClosed()).thenReturn(true);
        HttpBodyInputStream stream = new HttpBodyInputStream(10, preRead, ctx);

        byte[] buf = new byte[10];
        int n = stream.read(buf, 0, 10);

        Assertions.assertEquals(3, n); // only pre-read buffer data
        Assertions.assertArrayEquals("abc".getBytes(), new byte[]{buf[0], buf[1], buf[2]});
    }

    @Test
    public void testNextFromBuffer() throws IOException {
        byte[] preRead = "AB".getBytes();
        ChannelContext ctx = mockChannel(new byte[0], false);
        HttpBodyInputStream stream = new HttpBodyInputStream(2, preRead, ctx);

        Assertions.assertEquals('A', stream.next());
        Assertions.assertEquals('B', stream.next());
    }

    @Test
    public void testNextFromChannel() throws IOException {
        byte[] channelData = {'X'};
        ChannelContext ctx = mockChannel(channelData, false);
        HttpBodyInputStream stream = new HttpBodyInputStream(1, new byte[0], ctx);

        int b = stream.next();
        Assertions.assertEquals('X', b);
    }

    @Test
    public void testNextReturnsMinusOneAtEnd() throws IOException {
        ChannelContext ctx = mockChannel(new byte[0], false);
        HttpBodyInputStream stream = new HttpBodyInputStream(0, new byte[0], ctx);
        stream.markCompleted();

        Assertions.assertEquals(-1, stream.next());
    }

    @Test
    public void testReadFullBytesFromBufferAndChannel() throws IOException {
        byte[] preRead = "hel".getBytes();
        byte[] channelData = "lo!".getBytes();
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    int cl = Math.min(len, channelData.length);
                    System.arraycopy(channelData, 0, buf, off, cl);
                    return cl;
                });

        HttpBodyInputStream stream = new HttpBodyInputStream(6, preRead, ctx);
        byte[] all = stream.readFullBytes();

        Assertions.assertArrayEquals("hello!".getBytes(), all);
    }

    @Test
    public void testCompleteIsNoOpWhenAlreadyCompleted() throws IOException {
        ChannelContext ctx = mockChannel(new byte[0], false);
        HttpBodyInputStream stream = new HttpBodyInputStream(10, new byte[0], ctx);
        stream.markCompleted();

        stream.complete(); // should not throw
        Assertions.assertTrue(stream.completed);
    }

    @Test
    public void testReadChannelRespectsContentLength() throws IOException {
        byte[] channelData = "1234567890".getBytes();
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    int cl = Math.min(len, channelData.length);
                    System.arraycopy(channelData, 0, buf, off, cl);
                    return cl;
                });

        // bodyLength=5, should only read 5 bytes even though channel has 10
        HttpBodyInputStream stream = new HttpBodyInputStream(5, new byte[0], ctx);
        byte[] buf = new byte[10];
        int n = stream.read(buf, 0, 10);

        Assertions.assertEquals(5, n); // only 5 bytes due to content-length
        Assertions.assertTrue(stream.checkCompleted());
    }

    @Test
    public void testReadChannelEof() throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong())).thenReturn(-1);

        HttpBodyInputStream stream = new HttpBodyInputStream(10, new byte[0], ctx);
        byte[] buf = new byte[10];
        int n = stream.read(buf, 0, 10);

        Assertions.assertEquals(-1, n);
        Assertions.assertTrue(stream.completed);
    }
}
