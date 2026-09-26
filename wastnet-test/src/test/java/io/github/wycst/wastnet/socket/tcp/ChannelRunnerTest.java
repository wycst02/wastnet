package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.socket.channel.ChannelReader;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * tests for {@link ChannelRunner} and {@link ChannelSSLRunner}.
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class ChannelRunnerTest {

    private ServerSocket serverSocket;
    private Socket serverSide;
    private SocketChannel clientChannel;
    private NioConfig nioConfig;

    @BeforeEach
    public void setup() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        new Thread(() -> {
            try { serverSide = serverSocket.accept(); } catch (Exception e) {}
        }, "runner-test-accept").start();

        clientChannel = SocketChannel.open();
        clientChannel.connect(new InetSocketAddress("127.0.0.1", port));
        clientChannel.configureBlocking(false);
        Thread.sleep(100);

        nioConfig = new NioConfig();
        nioConfig.setReadBufferSize(1024);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (clientChannel != null) try { clientChannel.close(); } catch (Exception e) {}
        if (serverSide != null) try { serverSide.close(); } catch (Exception e) {}
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception e) {}
    }

    // ==================== ChannelRunner simple methods ====================

    @Test
    public void testConstructorSimpleMethods() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        Assertions.assertFalse(runner.isRunFlag());
        Assertions.assertFalse(runner.predictSync());
    }

    @Test
    public void testRunFlagAfterRun0() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.closed = true;
        runner.run0();
        Assertions.assertFalse(runner.isRunFlag());
    }

    @Test
    public void testSetReadKey() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.setReadKey(null);
    }

    @Test
    public void testWakeup() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.wakeup();
    }

    @Test
    public void testRelease() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.release();
        Assertions.assertTrue(runner.closed);
    }

    @Test
    public void testClose() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.close();
    }

    @Test
    public void testCreateReadByteBuffer() throws Exception {
        nioConfig.setSyncRunner(false);
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        Assertions.assertNotNull(runner.createReadByteBuffer());
    }

    @Test
    public void testBeforeReadyHook() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.beforeReady();
    }

    @Test
    public void testBeforeWhenAlreadyReady() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.ready = true;
        runner.before();
        Assertions.assertTrue(runner.ready);
    }

    @Test
    public void testReadWithEmptyBuffer() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        ByteBuffer empty = ByteBuffer.wrap(new byte[0]);
        runner.read(empty);
    }

    @Test
    public void testReadWithData() throws Exception {
        ChannelReader mockReader = mock(ChannelReader.class);
        nioConfig.setChannelReader(mockReader);
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        ByteBuffer buf = ByteBuffer.wrap("data".getBytes());
        runner.read(buf);
        verify(mockReader, times(1)).decode(any(), any());
    }

    @Test
    public void testChannelReadReturnsMinusOne() throws Exception {
        serverSide.close();
        Thread.sleep(100);
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        int ret = runner.handleChannelRead();
        Assertions.assertEquals(-1, ret);
    }

    // ==================== run0 with channel closed ====================

    @Test
    public void testRun0WithChannelClosed() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        // Close the server side so channel is effectively closed
        serverSide.close();
        Thread.sleep(100);
        runner.run0();
        // Should release without error
        Assertions.assertTrue(runner.closed);
    }

    // ==================== read with decode exception ====================

    @Test
    public void testReadWithDecodeException() throws Exception {
        ChannelReader mockReader = mock(ChannelReader.class);
        doThrow(new IOException("decode error")).when(mockReader).decode(any(), any());
        nioConfig.setChannelReader(mockReader);
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        ByteBuffer buf = ByteBuffer.wrap("data".getBytes());
        // Should throw IOException wrapped from decode
        Assertions.assertThrows(IOException.class, () -> runner.read(buf));
    }

    @Test
    public void testReadWithRuntimeException() throws Exception {
        ChannelReader mockReader = mock(ChannelReader.class);
        doThrow(new RuntimeException("fatal")).when(mockReader).decode(any(), any());
        nioConfig.setChannelReader(mockReader);
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        ByteBuffer buf = ByteBuffer.wrap("data".getBytes());
        Assertions.assertThrows(RuntimeException.class, () -> runner.read(buf));
    }

    // ==================== handleChannelRead loop ====================

    @Test
    public void testHandleChannelReadNonBlocking() throws Exception {
        // Connected channel with no data → channelRead returns 0 quickly
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        int ret = runner.handleChannelRead();
        // No data available on non-blocking channel → 0
        Assertions.assertEquals(0, ret);
    }

    // ==================== before with ready=false fails init ====================

    @Test
    public void testBeforeWithInitFailure() throws Exception {
        ChannelReader mockReader = mock(ChannelReader.class);
        doThrow(new RuntimeException("init failed")).when(mockReader).init(any());
        nioConfig.setChannelReader(mockReader);
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.ready = false;
        runner.before();
        // init failed → ready set to false
        Assertions.assertFalse(runner.ready);
    }

    // ==================== close triggers wakeup ====================

    @Test
    public void testCloseTriggersWakeup() throws Exception {
        ChannelRunner runner = new ChannelRunner(null, clientChannel, nioConfig);
        runner.close();
        // Should not throw
    }

    // ==================== ChannelSSLRunner ====================

    @Test
    public void testSSLConstructor() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, clientChannel, nioConfig, sslCtx, null);
        Assertions.assertNotNull(runner);
    }

    @Test
    public void testSSLConstructorWithCtx() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        ChannelSSLContext sslCtx2 = new ChannelSSLContext(clientChannel, new SSLEngineContext(sslCtx, null, null));
        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslCtx2, nioConfig);
        Assertions.assertNotNull(runner);
    }

    @Test
    public void testSSLIsMaybePlaintextSSL() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, clientChannel, nioConfig, sslCtx, null);
        Assertions.assertFalse(runner.isMaybePlaintext(ByteBuffer.wrap(new byte[]{22, 3, 1, 0, 0})));
    }

    @Test
    public void testSSLIsMaybePlaintextPlain() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, clientChannel, nioConfig, sslCtx, null);
        Assertions.assertTrue(runner.isMaybePlaintext(ByteBuffer.wrap(new byte[]{'G', 'E', 'T'})));
    }

    // ==================== ChannelSSLRunner handleChannelRead needs real SSL ====================

    @Test
    public void testSSLHandleChannelReadNonSSL() throws Exception {
        // When isSSL=false, ChannelSSLRunner.handleChannelRead falls through to super
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, clientChannel, nioConfig, sslCtx, null);
        // isSSL/finishHandshake are package-private: set directly (same package)
        runner.isSSL = false;
        runner.finishHandshake = true;
        int ret = runner.handleChannelRead();
        Assertions.assertEquals(0, ret); // no data
    }

    // ==================== Plaintext fallback: SSL runner detects non-SSL data ====================

    @Test
    public void testSSLPlaintextFallback() throws Exception {
        // Start a plain HTTP server (non-SSL) to trigger plaintext detection in sslHandShake
        int httpPort = findFreePort();
        HTTPServer httpServer = HTTPServer.of(httpPort)
                .requestHandler((request, response) -> {
                    response.body("plain-ok".getBytes());
                })
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);

            // Connect a plain SocketChannel and send an HTTP request
            SocketChannel plainChannel = SocketChannel.open();
            plainChannel.connect(new InetSocketAddress("127.0.0.1", httpPort));
            plainChannel.configureBlocking(false);
            String req = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
            plainChannel.write(ByteBuffer.wrap(req.getBytes()));
            Thread.sleep(200);

            // Create ChannelSSLRunner with allowPlaintextWhenSslEnabled
            NioConfig config = new NioConfig();
            config.setReadBufferSize(4096);
            config.setAllowPlaintextWhenSslEnabled(true);
            config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 2000L);
            ChannelReader mockReader = mock(ChannelReader.class);
            config.setChannelReader(mockReader);
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, null, null);
            ChannelSSLRunner runner = new ChannelSSLRunner(null, plainChannel, config, sslCtx, null);

            // beforeReady → sslHandShake → reads HTTP response → isMaybePlaintext=true → falls back
            runner.beforeReady();

            // Verify fallback state
            Assertions.assertFalse(runner.isSSL); // isSSL=false
            Assertions.assertTrue(runner.finishHandshake); // finished

            // Plaintext data was dispatched via read() → mockReader.decode
            verify(mockReader, atLeastOnce()).decode(any(), any());
            plainChannel.close();
        } finally {
            httpServer.shutdown();
        }
    }

    // ==================== sslHandShake: timeout (connected but no data) ====================

    @Test
    public void testSSLHandshakeTimeout() throws Exception {
        // Start a silent server (accepts but doesn't send data)
        ServerSocket silentServer = new ServerSocket(0);
        int port = silentServer.getLocalPort();
        // Connect then read returns 0 (no data from server) → spin loop → timeout
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("127.0.0.1", port));
        ch.configureBlocking(false);
        Thread.sleep(100);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, null, null, false);
        ChannelSSLContext sslChannelCtx = new ChannelSSLContext(ch, engineCtx);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 100L);  // short timeout triggers return -1
        ChannelReader mockReader = mock(ChannelReader.class);
        config.setChannelReader(mockReader);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslChannelCtx, config);

        runner.beforeReady();
        Assertions.assertTrue(runner.closed);
        ch.close();
        silentServer.close();
    }

    // ==================== sslHandShake: first read returns -1 (channel closed) ====================

    @Test
    public void testSSLHandshakeChannelClosedFirstRead() throws Exception {
        // Connect then close → first channelRead detects closed channel → size == -1
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("127.0.0.1", port));
        Thread.sleep(100);
        ch.close();  // close channel BEFORE beforeReady
        ss.close();

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, null, null, false);
        ChannelSSLContext sslChannelCtx = new ChannelSSLContext(ch, engineCtx);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 5000L);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslChannelCtx, config);

        runner.beforeReady();
        // size == -1 → sslHandShake returns -1 → release
        Assertions.assertTrue(runner.closed);
    }

    // ==================== allowPlaintextWhenSslEnabled but data is TLS (byte 22) ====================

    @Test
    public void testSSLAllowPlaintextWithTlsData() throws Exception {
        // allowPlaintextWhenSslEnabled=true but data starts with byte 22 (TLS)
        // → isMaybePlaintext returns false → goes to doHandshake()
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> response.body("ok".getBytes()))
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslPort));
            ch.configureBlocking(false);
            Thread.sleep(200); // wait for server to send TLS ServerHello

            // Create runner with allowPlaintext=true, server-mode SSLEngine
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, null, null);
            NioConfig config = new NioConfig();
            config.setReadBufferSize(4096);
            config.setAllowPlaintextWhenSslEnabled(true);
            config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 3000L);
            ChannelReader mockReader = mock(ChannelReader.class);
            config.setChannelReader(mockReader);

            ChannelSSLRunner runner = new ChannelSSLRunner(null, ch, config, sslCtx, null);

            // sslHandShake reads TLS data (byte 22) → isMaybePlaintext=false
            // → doHandshake() is called (will fail, engine mode mismatch → catch → return -1)
            runner.beforeReady();

            // Handshake fails but the branch was taken: closed=true
            Assertions.assertTrue(runner.closed);
            ch.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== sslHandShake: exception path ====================

    @Test
    public void testSSLHandshakeUnconnectedThrows() throws Exception {
        // Unconnected channel: channel.read() throws NotYetConnectedException
        // → caught by sslHandShake catch block → return -1 → release
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, null, null, false);
        ChannelSSLContext sslChannelCtx = new ChannelSSLContext(ch, engineCtx);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 5000L);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslChannelCtx, config);

        runner.beforeReady();
        Assertions.assertTrue(runner.closed);
        ch.close();
    }

    // ==================== Helper ====================

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18083;
        }
    }
}
