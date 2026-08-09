package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ChannelCodec} default methods.
 */
public class ChannelCodecTest {

    @Test
    public void testInitDefaultDoesNotThrow() throws Exception {
        SimpleCodec codec = new SimpleCodec();
        Assertions.assertDoesNotThrow(() -> codec.init(mock(ChannelContext.class)));
    }

    @Test
    public void testWakeupDefaultDoesNotThrow() {
        SimpleCodec codec = new SimpleCodec();
        Assertions.assertDoesNotThrow(() -> codec.wakeup());
    }

    @Test
    public void testInheritsFromChannelDecoder() {
        SimpleCodec codec = new SimpleCodec();
        Assertions.assertTrue(codec instanceof ChannelDecoder);
        Assertions.assertTrue(codec instanceof ChannelReader);
    }

    @Test
    public void testWriteReturnsNullForNullMessage() throws Exception {
        SimpleCodec codec = new SimpleCodec();
        ChannelContext ctx = mock(ChannelContext.class);
        Assertions.assertNull(codec.write(ctx, null));
    }

    /** Minimal concrete subclass for testing, implements required abstract methods. */
    static class SimpleCodec extends ChannelCodec<Void> {
        @Override
        public void decode(ChannelContext ctx, ByteBuffer buf) {
        }

        @Override
        public ByteBuffer write(ChannelContext ctx, Void message) throws java.io.IOException {
            // ChannelWriter.write() abstract — implement returning null for null input
            if (message == null) return null;
            return ByteBuffer.allocate(0);
        }
    }
}
