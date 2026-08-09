package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ChannelHandler} default lifecycle methods.
 */
public class ChannelHandlerTest {

    @Test
    public void testOnConnectedDefaultDoesNotThrow() {
        RecordingHandler handler = new RecordingHandler();
        Assertions.assertDoesNotThrow(() -> handler.onConnected(mock(ChannelContext.class)));
    }

    @Test
    public void testOnClosedDefaultDoesNotThrow() {
        RecordingHandler handler = new RecordingHandler();
        Assertions.assertDoesNotThrow(() -> handler.onClosed(mock(ChannelContext.class)));
    }

    @Test
    public void testOnExceptionDefaultDoesNotThrow() {
        RecordingHandler handler = new RecordingHandler();
        Assertions.assertDoesNotThrow(() -> handler.onException(mock(ChannelContext.class), new IOException("test")));
    }

    @Test
    public void testOnHandleIsCalled() throws IOException {
        RecordingHandler handler = new RecordingHandler();

        String message = "test-message";
        handler.onHandle(mock(ChannelContext.class), message);
        Assertions.assertEquals(1, handler.received.size());
        Assertions.assertEquals("test-message", handler.received.get(0));
    }

    @Test
    public void testOnHandleMultipleCalls() throws IOException {
        RecordingHandler handler = new RecordingHandler();

        handler.onHandle(mock(ChannelContext.class), "a");
        handler.onHandle(mock(ChannelContext.class), "b");
        handler.onHandle(mock(ChannelContext.class), "c");
        Assertions.assertEquals(3, handler.received.size());
    }

    @Test
    public void testFullLifecycleSequence() throws IOException {
        RecordingHandler handler = new RecordingHandler();
        ChannelContext ctx = mock(ChannelContext.class);

        handler.onConnected(ctx);
        Assertions.assertEquals(1, handler.lifecycle.size());
        Assertions.assertEquals("connected", handler.lifecycle.get(0));

        handler.onHandle(ctx, "data");
        Assertions.assertEquals(1, handler.received.size());

        handler.onClosed(ctx);
        Assertions.assertEquals(2, handler.lifecycle.size());
        Assertions.assertEquals("closed", handler.lifecycle.get(1));

        handler.onException(ctx, new IOException("error"));
        Assertions.assertEquals(3, handler.lifecycle.size());
        Assertions.assertEquals("exception", handler.lifecycle.get(2));
    }

    /** Concrete subclass for testing abstract ChannelHandler. */
    static class RecordingHandler extends ChannelHandler<String> {
        final List<String> received = new ArrayList<String>();
        final List<String> lifecycle = new ArrayList<String>();

        @Override
        public void onHandle(ChannelContext ctx, String message) throws IOException {
            received.add(message);
        }

        @Override
        public void onConnected(ChannelContext ctx) throws IOException {
            super.onConnected(ctx);
            lifecycle.add("connected");
        }

        @Override
        public void onClosed(ChannelContext ctx) throws IOException {
            super.onClosed(ctx);
            lifecycle.add("closed");
        }

        @Override
        public void onException(ChannelContext context, Throwable cause) throws IOException {
            super.onException(context, cause);
            lifecycle.add("exception");
        }
    }

    @Test
    public void testClearableHandlerCanBeImplemented() {
        ClearableHandler clearable = new ClearableHandler() {
            boolean cleared = false;
            @Override
            public void clear() {
                cleared = true;
            }
        };
        clearable.clear();
        // just verify no exception
    }
}
