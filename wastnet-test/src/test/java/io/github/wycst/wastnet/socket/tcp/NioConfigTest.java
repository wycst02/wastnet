package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.ClearableHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NioConfig}.
 */
public class NioConfigTest {

    @Test
    public void testDefaultWorkerNumAtLeastTwo() {
        NioConfig config = new NioConfig();
        Assertions.assertTrue(config.getWorkerNum() >= 2);
    }

    @Test
    public void testSetWorkerNumCapsAtMax() {
        NioConfig config = new NioConfig();
        // Set a very large worker num — should be capped
        config.setWorkerNum(1024);
        int workerNum = config.getWorkerNum();
        // MAX_WORKER_NUM = defaultWorkerNum << 2. With CPU >= 2, defaultWorkerNum >= 2, so max >= 8.
        Assertions.assertTrue(workerNum >= 2 && workerNum <= Runtime.getRuntime().availableProcessors() << 2);
    }

    @Test
    public void testSetWorkerNumLessThanTwoDefaultsToTwo() {
        NioConfig config = new NioConfig();
        config.setWorkerNum(0);
        Assertions.assertEquals(2, config.getWorkerNum());
    }

    @Test
    public void testSetWorkerNumOneDefaultsToTwo() {
        NioConfig config = new NioConfig();
        config.setWorkerNum(1);
        Assertions.assertEquals(2, config.getWorkerNum());
    }

    @Test
    public void testTestModeSetsSyncRunnerAndAllowPlaintext() {
        NioConfig config = new NioConfig();
        config.testMode();
        Assertions.assertTrue(config.isSyncRunner());
        Assertions.assertTrue(config.isAllowPlaintextWhenSslEnabled());
    }

    @Test
    public void testSetReadBufferSizeMin512() {
        NioConfig config = new NioConfig();
        config.setReadBufferSize(100);
        Assertions.assertEquals(512, config.getReadBufferSize());
    }

    @Test
    public void testSetReadBufferSizeNormal() {
        NioConfig config = new NioConfig();
        config.setReadBufferSize(2048);
        Assertions.assertEquals(2048, config.getReadBufferSize());
    }

    @Test
    public void testSetWriteBufferSizeMin512() {
        NioConfig config = new NioConfig();
        config.setWriteBufferSize(200);
        Assertions.assertEquals(512, config.getWriteBufferSize());
    }

    @Test
    public void testSetWriteBufferSizeNormal() {
        NioConfig config = new NioConfig();
        config.setWriteBufferSize(4096);
        Assertions.assertEquals(4096, config.getWriteBufferSize());
    }

    @Test
    public void testClearWithNullHandlersDoesNotThrow() {
        NioConfig config = new NioConfig();
        Assertions.assertDoesNotThrow(() -> config.clear());
    }

    @Test
    public void testClearInvokesClearableHandler() {
        NioConfig config = new NioConfig();
        ChannelHandler<?> mockHandler = mock(ChannelHandler.class, withSettings().extraInterfaces(ClearableHandler.class));
        config.setChannelHandler(mockHandler);
        config.clear();
        verify((ClearableHandler) mockHandler).clear();
    }

    @Test
    public void testClearDoesNotInvokeNonClearableHandler() {
        NioConfig config = new NioConfig();
        ChannelHandler<?> mockHandler = mock(ChannelHandler.class);
        config.setChannelHandler(mockHandler);
        config.clear();
        // No clear() call expected on non-ClearableHandler
    }

    @Test
    public void testClearInvokesClearableIdleStateHandler() {
        NioConfig config = new NioConfig();
        IdleStateHandler mockIdle = mock(IdleStateHandler.class, withSettings().extraInterfaces(ClearableHandler.class));
        config.setIdleStateHandler(mockIdle);
        config.clear();
        verify((ClearableHandler) mockIdle).clear();
    }

    @Test
    public void testGetChannelReaderReturnsUndoWhenNull() {
        NioConfig config = new NioConfig();
        ChannelReader<?> reader = config.getChannelReader();
        Assertions.assertSame(ChannelReader.UNDO, reader);
    }

    @Test
    public void testGetChannelReaderReturnsCustomReader() {
        NioConfig config = new NioConfig();
        ChannelReader<?> customReader = mock(ChannelReader.class);
        config.setChannelReader(customReader);
        ChannelReader<?> reader = config.getChannelReader();
        Assertions.assertSame(customReader, reader);
    }

    @Test
    public void testSelfReturnsThis() {
        NioConfig config = new NioConfig();
        Assertions.assertSame(config, config.self());
    }

    @Test
    public void testSetSyncRunner() {
        NioConfig config = new NioConfig();
        config.setSyncRunner(true);
        Assertions.assertTrue(config.isSyncRunner());
        config.setSyncRunner(false);
        Assertions.assertFalse(config.isSyncRunner());
    }

    @Test
    public void testSetAllowPlaintextWhenSslEnabled() {
        NioConfig config = new NioConfig();
        config.setAllowPlaintextWhenSslEnabled(true);
        Assertions.assertTrue(config.isAllowPlaintextWhenSslEnabled());
    }

    @Test
    public void testSetChannelReaderFactory() {
        NioConfig config = new NioConfig();
        config.setChannelReader(new ChannelReader<String>() {
            public void init(ChannelContext ctx) {}
            public void decode(ChannelContext ctx, java.nio.ByteBuffer buf) {}
            public void wakeup() {}
        });
        ChannelReader<?> reader = config.getChannelReader();
        Assertions.assertNotNull(reader);
        Assertions.assertNotSame(ChannelReader.UNDO, reader);
    }

    @Test
    public void testSetGetPrintSSLErrorLog() {
        NioConfig config = new NioConfig();
        config.setPrintSSLErrorLog(true);
        Assertions.assertTrue(config.isPrintSSLErrorLog());
    }

    @Test
    public void testSetGetPrintReadErrorLog() {
        NioConfig config = new NioConfig();
        config.setPrintReadErrorLog(true);
        Assertions.assertTrue(config.isPrintReadErrorLog());
    }

    @Test
    public void testSetGetPrintApplicationMessage() {
        NioConfig config = new NioConfig();
        config.setPrintApplicationMessage(true);
        Assertions.assertTrue(config.isPrintApplicationMessage());
    }

    @Test
    public void testSetGetSslHandshakeTimeoutMs() {
        NioConfig config = new NioConfig();
        config.setSslHandshakeTimeoutMs(10000);
        Assertions.assertEquals(10000, config.getSslHandshakeTimeoutMs());
    }

    @Test
    public void testConnectionFilter() throws Exception {
        NioConfig config = new NioConfig();
        // Mock SocketChannel for filter test
        java.nio.channels.SocketChannel mockChannel = mock(java.nio.channels.SocketChannel.class);
        config.setConnectionFilter(new ConnectionFilter() {
            public boolean onAccept(java.nio.channels.SocketChannel channel) throws Exception {
                return channel == mockChannel;
            }
        });
        ConnectionFilter filter = config.getConnectionFilter();
        Assertions.assertNotNull(filter);
        Assertions.assertTrue(filter.onAccept(mockChannel));
        Assertions.assertFalse(filter.onAccept(null));
    }

    @Test
    public void testSetDefaultBufferSize() {
        NioConfig.setDefaultBufferSize(2048);
        try {
            NioConfig config = new NioConfig();
            Assertions.assertEquals(2048, config.getReadBufferSize());
        } finally {
            NioConfig.setDefaultBufferSize(1024);
        }
    }

    @Test
    public void testSslHandshakeTimeoutDefault() {
        NioConfig config = new NioConfig();
        long timeout = config.getSslHandshakeTimeoutMs();
        Assertions.assertTrue(timeout > 0);
    }
}
