package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChannelDecoder} read methods.
 * <p>
 * Tests byte array expansion and delegation to ChannelContext.readFully().
 */
public class ChannelDecoderTest {

    @Test
    public void testReadExpandsBufferWhenTooSmall() throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        // Make readFully return the length passed (indicating success)
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    // Fill with dummy data to simulate read
                    for (int i = 0; i < len; ++i) {
                        buf[off + i] = (byte) i;
                    }
                    return len;
                });

        ConcreteDecoder decoder = new ConcreteDecoder();
        byte[] smallBuf = new byte[4];
        byte[] result = decoder.read(ctx, smallBuf, 0, 10, 5000);

        // Should have returned a new expanded array
        Assertions.assertNotSame(smallBuf, result);
        Assertions.assertEquals(10, result.length);
        verify(ctx).readFully(result, 0, 10, 5000);
    }

    @Test
    public void testReadPreservesBufferWhenSufficient() throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    for (int i = 0; i < len; ++i) {
                        buf[off + i] = 0x42;
                    }
                    return len;
                });

        ConcreteDecoder decoder = new ConcreteDecoder();
        byte[] buf = new byte[20];
        byte[] result = decoder.read(ctx, buf, 0, 10, 5000);

        Assertions.assertSame(buf, result);
        verify(ctx).readFully(buf, 0, 10, 5000);
    }

    @Test
    public void testReadFullyReturnsMinusOneThrowsIOException() throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1);

        ConcreteDecoder decoder = new ConcreteDecoder();
        IOException ex = Assertions.assertThrows(IOException.class,
                () -> decoder.read(ctx, new byte[10], 0, 5, 5000));
        Assertions.assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    public void testReadBufMethodDelegatesCorrectly() throws IOException {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    for (int i = 0; i < buf.length; ++i) {
                        buf[i] = (byte) 0xFF;
                    }
                    return buf.length;
                });

        ConcreteDecoder decoder = new ConcreteDecoder();
        byte[] buf = new byte[8];
        decoder.read(ctx, buf);

        verify(ctx).readFully(buf, 0, 8, 10000L);
    }

    @Test
    public void testInitDefaultDoesNotThrow() throws Exception {
        ConcreteDecoder decoder = new ConcreteDecoder();
        Assertions.assertDoesNotThrow(() -> decoder.init(mock(ChannelContext.class)));
    }

    @Test
    public void testWakeupDefaultDoesNotThrow() {
        ConcreteDecoder decoder = new ConcreteDecoder();
        Assertions.assertDoesNotThrow(() -> decoder.wakeup());
    }

    /** Minimal concrete subclass implementing the abstract decode method. */
    static class ConcreteDecoder extends ChannelDecoder<Void> {
        @Override
        public void decode(ChannelContext ctx, ByteBuffer buf) {
        }
    }
}
