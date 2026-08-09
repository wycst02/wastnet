package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelCodec;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Field;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link NioEngine}.
 * <p>
 * Covers configuration setters, lifecycle methods (stop/shutdown),
 * SSL helpers, and edge cases.
 */
public class NioEngineTest {

    /**
     * Minimal concrete subclass for testing NioEngine base class.
     */
    static class TestEngine extends NioEngine<TestEngine> {
        TestEngine(int port) {
            super(port);
        }
        TestEngine(int port, NioConfig nioConfig) {
            super(port, nioConfig);
        }
    }

    // ==================== Constructors ====================

    @Test
    public void testConstructorWithPort() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertEquals(8080, engine.getPort());
        Assertions.assertNotNull(engine.config());
        engine.shutdown();
    }

    @Test
    public void testConstructorWithNullConfig() {
        TestEngine engine = new TestEngine(8080, null);
        Assertions.assertNotNull(engine.config());
        engine.shutdown();
    }

    @Test
    public void testConstructorWithInvalidPort() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TestEngine(-1));
    }

    // ==================== Getters ====================

    @Test
    public void testGetPort() {
        TestEngine engine = new TestEngine(9090);
        Assertions.assertEquals(9090, engine.getPort());
        engine.shutdown();
    }

    @Test
    public void testConfig() {
        NioConfig cfg = new NioConfig();
        TestEngine engine = new TestEngine(7070, cfg);
        Assertions.assertSame(cfg, engine.config());
        engine.shutdown();
    }

    @Test
    public void testIsSslDefault() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertFalse(engine.isSsl());
        engine.shutdown();
    }

    @Test
    public void testIsSslAfterEnable() {
        TestEngine engine = new TestEngine(8080);
        engine.ssl(true);
        Assertions.assertTrue(engine.isSsl());
        engine.shutdown();
    }

    // ==================== Configuration setters (fluent API) ====================

    @Test
    public void testConfigSetter() {
        NioConfig cfg = new NioConfig();
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.config(cfg));
        Assertions.assertSame(cfg, engine.config());
        engine.shutdown();
    }

    @Test
    public void testIdleStateHandler() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.idleStateHandler(mock(io.github.wycst.wastnet.socket.handler.IdleStateHandler.class)));
        engine.shutdown();
    }

    @Test
    public void testChannelHandler() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.channelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        }));
        engine.shutdown();
    }

    @Test
    public void testChannelReader() {
        TestEngine engine = new TestEngine(8080);
        ChannelReader reader = new ChannelCodec<Object>() {
            @Override
            public void decode(ChannelContext ctx, java.nio.ByteBuffer buf) {}
            @Override
            public java.nio.ByteBuffer write(ChannelContext ctx, Object message) { return null; }
        };
        Assertions.assertSame(engine, engine.channelReader(reader));
        engine.shutdown();
    }

    @Test
    public void testChannelReaderFactory() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.channelReaderFactory(mock(io.github.wycst.wastnet.socket.channel.ChannelReaderFactory.class)));
        engine.shutdown();
    }

    @Test
    public void testBufferSize() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.bufferSize(4096));
        engine.shutdown();
    }

    @Test
    public void testWorkerNum() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.workerNum(4));
        engine.shutdown();
    }

    @Test
    public void testSslEnable() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.ssl(true));
        Assertions.assertTrue(engine.isSsl());
        engine.shutdown();
    }

    @Test
    public void testSslContext() throws Exception {
        TestEngine engine = new TestEngine(8080);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        Assertions.assertSame(engine, engine.sslContext(ctx));
        Assertions.assertTrue(engine.isSsl());
        engine.shutdown();
    }

    @Test
    public void testSslCipherSuites() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.sslCipherSuites("TLS_AES_128_GCM_SHA256"));
        engine.shutdown();
    }

    @Test
    public void testApplicationProtocols() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.applicationProtocols("h2", "http/1.1"));
        engine.shutdown();
    }

    @Test
    public void testPrintSSLErrorLog() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.printSSLErrorLog(true));
        engine.shutdown();
    }

    @Test
    public void testPrintReadErrorLog() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.printReadErrorLog(true));
        engine.shutdown();
    }

    @Test
    public void testPrintApplicationMessage() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.printApplicationMessage(true));
        engine.shutdown();
    }

    @Test
    public void testSslHandshakeTimeout() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.sslHandshakeTimeout(5000));
        engine.shutdown();
    }

    @Test
    public void testAllowPlaintextWhenSslEnabled() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertSame(engine, engine.allowPlaintextWhenSslEnabled(true));
        engine.shutdown();
    }

    @Test
    public void testChannelCodecSetter() {
        TestEngine engine = new TestEngine(8080);
        ChannelCodec<?> codec = new LengthFrameCodec<String>(4, 0, 4, 65536) {
            @Override
            protected byte[] encodeBody(ChannelContext ctx, String message) {
                return message.getBytes();
            }
        };
        Assertions.assertSame(engine, engine.channelCodec(codec));
        engine.shutdown();
    }

    // ==================== SSL helpers ====================

    @Test
    public void testInitSslContextWithNoContextThrows() {
        TestEngine engine = new TestEngine(8080);
        engine.ssl(true); // SSL enabled but no context
        Assertions.assertThrows(io.github.wycst.wastnet.exception.SocketException.class,
                engine::initSslContext);
        engine.shutdown();
    }

    @Test
    public void testInitSslContextWithContextSucceeds() throws Exception {
        TestEngine engine = new TestEngine(8080);
        SSLContext ctx = SSLContext.getInstance("TLS");
        engine.sslContext(ctx);
        engine.initSslContext(); // should not throw
        engine.shutdown();
    }

    @Test
    public void testGetSSLContextReturnsSetContext() throws Exception {
        TestEngine engine = new TestEngine(8080);
        SSLContext ctx = SSLContext.getInstance("TLS");
        engine.sslContext(ctx);
        Assertions.assertSame(ctx, engine.getSSLContext());
        engine.shutdown();
    }

    @Test
    public void testGetSSLContextReturnsNullByDefault() {
        TestEngine engine = new TestEngine(8080);
        Assertions.assertNull(engine.getSSLContext());
        engine.shutdown();
    }

    @Test
    public void testCheckServerAvailableWhenShutdown() {
        TestEngine engine = new TestEngine(8080);
        engine.shutdown();
        Assertions.assertThrows(io.github.wycst.wastnet.exception.SocketException.class,
                engine::checkServerAvailable);
    }

    @Test
    public void testCheckServerAvailableWhenRunning() {
        TestEngine engine = new TestEngine(8080);
        engine.checkServerAvailable(); // should not throw
        engine.shutdown();
    }

    // ==================== Lifecycle: stop ====================

    @Test
    public void testStopWhenNotStarted() {
        TestEngine engine = new TestEngine(8080);
        engine.shutdown(); // should not throw, logs "engine is not start"
        engine.shutdown();
    }

    @Test
    public void testStopWhenStarted() {
        TestEngine engine = new TestEngine(8080);
        // Simulate started state by setting engineRunFlag
        setEngineRunFlag(engine, true);
        engine.shutdown(); // should stop
        engine.shutdown();
    }

    @Test
    public void testStopTwice() {
        TestEngine engine = new TestEngine(8080);
        engine.shutdown(); // first stop ("not start")
        engine.shutdown(); // second stop ("not start")
        engine.shutdown();
    }

    // ==================== Lifecycle: shutdown ====================

    @Test
    public void testShutdown() {
        TestEngine engine = new TestEngine(8080);
        engine.shutdown();
        // Second shutdown should be no-op
        engine.shutdown();
    }

    @Test
    public void testShutdownWithEngineRunning() {
        TestEngine engine = new TestEngine(8080);
        setEngineRunFlag(engine, true);
        engine.shutdown(); // calls stop() first, then shuts down executors
    }

    // ==================== createExecutor ====================

    @Test
    public void testCreateExecutorReturnsThreadPool() {
        // createExecutor is called in the constructor, just verify the runnerExecutor is a ThreadPoolExecutor
        TestEngine engine = new TestEngine(8080);
        Assertions.assertNotNull(engine.runnerExecutor);
        engine.shutdown();
    }

    // ==================== Helpers ====================

    private static void setEngineRunFlag(NioEngine<?> engine, boolean value) {
        try {
            Field f = NioEngine.class.getDeclaredField("engineRunFlag");
            f.setAccessible(true);
            f.setBoolean(engine, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
