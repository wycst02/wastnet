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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
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
        Thread.sleep(20);

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
        nioConfig.syncMode(false);
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
        Thread.sleep(20);
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
        Thread.sleep(20);
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
            Thread.sleep(50);

            // Connect a plain SocketChannel and send an HTTP request
            SocketChannel plainChannel = SocketChannel.open();
            plainChannel.connect(new InetSocketAddress("127.0.0.1", httpPort));
            plainChannel.configureBlocking(false);
            String req = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
            plainChannel.write(ByteBuffer.wrap(req.getBytes()));
            Thread.sleep(50);

            // Create ChannelSSLRunner with allowPlaintextWhenSslEnabled
            NioConfig config = new NioConfig();
            config.setReadBufferSize(4096);
            config.setAllowPlaintextWhenSslEnabled(true);
            config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 2000L);
            ChannelReader<?> mockReader = mock(ChannelReader.class);
            config.setChannelReader(mockReader);
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, null, null);
            ChannelSSLRunner runner = new ChannelSSLRunner(null, plainChannel, config, sslCtx, null);

            long deadline = System.currentTimeMillis() + 2000L;
            int tries = 0;
            while (!runner.finishHandshake && System.currentTimeMillis() < deadline && tries++ < 200) {
                Thread.sleep(10);
                runner.beforeReady();
            }

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

    // ==================== sslHandShake: suspend on no data / mid-handshake deadline ====================

    @Test
    public void testSSLHandshakeSuspendsWithoutData() throws Exception {
        // Silent peer accepts but never sends anything; its FIN later simulates the next OP_READ event.
        ServerSocket silentServer = new ServerSocket(0);
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("127.0.0.1", silentServer.getLocalPort()));
        ch.configureBlocking(false);
        Socket silentSide = silentServer.accept();
        Thread.sleep(20);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, null, null, false);
        ChannelSSLContext sslChannelCtx = new ChannelSSLContext(ch, engineCtx);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 100L);
        ChannelReader mockReader = mock(ChannelReader.class);
        config.setChannelReader(mockReader);

        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslChannelCtx, config);
        try {
            runner.beforeReady();
            // Suspended: connection alive, no application data dispatched
            Assertions.assertTrue(ch.isOpen());
            verify(mockReader, never()).decode(any(), any());

            // Survives well past the configured deadline: non-blocking mode arms no timeout of its own
            Thread.sleep(100);
            Assertions.assertTrue(ch.isOpen());
            verify(mockReader, never()).decode(any(), any());

            // Next OP_READ after peer FIN drives re-entry → EOF → connection released
            silentSide.close();
            Thread.sleep(20);
            runner.beforeReady();
            Assertions.assertFalse(ch.isOpen());
        } finally {
            silentSide.close();
            ch.close();
            silentServer.close();
        }
    }

    @Test
    public void testSSLHandshakePartialRecordDeadlineOnReentry() throws Exception {
        // Raw peer sends only a 5-byte TLS record header (contentType=22, declares 100 body bytes):
        // engine must suspend on BUFFER_UNDERFLOW instead of spinning; the absolute deadline
        // is enforced on the next OP_READ re-entry.
        java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        Socket rawPeer = new Socket();
        rawPeer.connect(new InetSocketAddress("127.0.0.1", ((InetSocketAddress) ssc.getLocalAddress()).getPort()));
        SocketChannel ch = ssc.accept();
        ch.configureBlocking(false);
        rawPeer.getOutputStream().write(new byte[]{22, 3, 3, 0, 100});
        rawPeer.getOutputStream().flush();
        Thread.sleep(50); // let the partial record land in the socket buffer

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, null, null, false);
        ChannelSSLContext sslChannelCtx = new ChannelSSLContext(ch, engineCtx);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 100L);
        ChannelReader mockReader = mock(ChannelReader.class);
        config.setChannelReader(mockReader);

        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslChannelCtx, config);
        try {
            runner.beforeReady();
            // Suspended on the partial record: alive, no ServerHello flight sent, no app data dispatched
            Assertions.assertTrue(ch.isOpen());
            Assertions.assertEquals(0, rawPeer.getInputStream().available());
            verify(mockReader, never()).decode(any(), any());

            Thread.sleep(200); // deadline passes while suspended; nothing self-closes the channel (must exceed SSL_HANDSHAKE_TIMEOUT_MS=100)
            Assertions.assertTrue(ch.isOpen());

            // Next OP_READ re-entry drives the engine → absolute deadline check → release
            runner.beforeReady();
            Assertions.assertFalse(ch.isOpen());
        } finally {
            rawPeer.close();
            ch.close();
            ssc.close();
        }
    }

    // ==================== sslHandShake: first read returns -1 (channel closed) ====================

    @Test
    public void testSSLHandshakeChannelClosedFirstRead() throws Exception {
        // Connect then close → first channelRead detects closed channel → size == -1
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("127.0.0.1", port));
        Thread.sleep(20);
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
        // allowPlaintextWhenSslEnabled=true but incoming data starts with byte 22 (a real TLS ClientHello)
        // → isMaybePlaintext=false → doHandshake() runs; the cert-less server engine fails → release
        java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        int sslPort = ((InetSocketAddress) ssc.getLocalAddress()).getPort();

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.setAllowPlaintextWhenSslEnabled(true);
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 3000L);
        ChannelReader mockReader = mock(ChannelReader.class);
        config.setChannelReader(mockReader);

        ChannelWorker worker = new ChannelWorker(new NioEngine<>(1, config));
        // Real TLS client: sends ClientHello as soon as the handshake starts
        Socket tlsClient = javax.net.ssl.SSLSocketFactory.getDefault().createSocket();
        tlsClient.connect(new InetSocketAddress("127.0.0.1", sslPort));
        Thread handshakeThread = new Thread(() -> {
            try {
                ((javax.net.ssl.SSLSocket) tlsClient).startHandshake();
            } catch (Exception ignored) {
            }
        });
        handshakeThread.setDaemon(true);
        handshakeThread.start();

        SocketChannel ch = ssc.accept();
        ch.configureBlocking(false);
        Thread.sleep(50); // wait for ClientHello to land in the socket buffer
        try {
            ChannelSSLRunner runner = new ChannelSSLRunner(worker, ch, config, sslCtx, null);

            // TLS data (byte 22) skips the plaintext branch and enters doHandshake(), which fails
            runner.beforeReady();
            Assertions.assertTrue(runner.closed);
        } finally {
            worker.selector.close();
            if (worker.scheduledExecutorService != null) worker.scheduledExecutorService.shutdownNow();
            tlsClient.close();
            ch.close();
            ssc.close();
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

    // ==================== sslHandShake: exception path with error logging on ====================

    @Test
    public void testSSLHandshakeFailureWithErrorLogEnabled() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        SSLEngineContext engineCtx = new SSLEngineContext(sslCtx, null, null, false);
        ChannelSSLContext sslChannelCtx = new ChannelSSLContext(ch, engineCtx);
        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.setPrintSSLErrorLog(true);
        ChannelSSLRunner runner = new ChannelSSLRunner(null, sslChannelCtx, config);

        runner.beforeReady();
        Assertions.assertTrue(runner.closed);
        ch.close();
    }

    // ==================== createClientRunner: synchronous client handshake ====================

    @Test
    public void testCreateClientRunnerServesEncryptedResponse() throws Exception {
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> response.body("plain-ok".getBytes()))
                .startupBannerEnabled(false)
                .start();
        SocketChannel ch = null;
        try {
            Thread.sleep(50);
            ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslPort));
            ch.configureBlocking(false);

            NioConfig config = new NioConfig();
            config.setReadBufferSize(4096);
            ChannelReader mockReader = mock(ChannelReader.class);
            doAnswer(inv -> {
                ByteBuffer buf = inv.getArgument(1);
                if (buf.remaining() >= 2) {
                    ChannelSSLContext c = inv.getArgument(0);
                    buf.get(); // advance past position 0 so readFully takes its residue branch
                    c.readFully(new byte[1], 0, 1, 2000);
                } else {
                    buf.get(new byte[buf.remaining()]);
                }
                return null;
            }).when(mockReader).decode(any(), any());
            config.setChannelReader(mockReader);

            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, ChannelSSLContext.TRUST_ALL_MANAGERS, null);

            ChannelSSLRunner runner = ChannelSSLRunner.createClientRunner(null, ch, config, sslCtx, null);

            // Encrypted round trip proves the client handshake completed and the runner is in SSL mode
            ChannelSSLContext sctx = (ChannelSSLContext) runner.ctx;
            sctx.write(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes()));
            sctx.flush();
            for (int i = 0; i < 20; i++) {
                runner.handleChannelRead();
                Thread.sleep(20);
            }
            // residue flag kept the read loop going: decode ran more than once on the response flight
            verify(mockReader, atLeast(2)).decode(any(), any());
            sctx.close();
        } finally {
            if (ch != null) try { ch.close(); } catch (Exception ignored) {}
            sslServer.shutdown();
        }
    }

    // ==================== plaintext fallback: drain loop fills packetInBuf completely ====================

    private static SocketChannel connectRawPeerPair(Socket rawPeer) throws Exception {
        java.nio.channels.ServerSocketChannel ssc = java.nio.channels.ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        rawPeer.connect(new InetSocketAddress("127.0.0.1",
                ((InetSocketAddress) ssc.getLocalAddress()).getPort()));
        SocketChannel ch = ssc.accept();
        ch.configureBlocking(false);
        ssc.close();
        return ch;
    }

    private static SSLEngineContext newServerEngineCtx() throws Exception {
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        return new SSLEngineContext(sslCtx, null, null);
    }

    /** First read fills the whole packet buffer: drain, read again, drain the remainder, return 0. */
    @Test
    public void testSSLPlaintextDrainRefillsBuffer() throws Exception {
        Socket rawPeer = new Socket();
        SocketChannel ch = connectRawPeerPair(rawPeer);
        SSLEngineContext engineCtx = newServerEngineCtx();
        int capacity = engineCtx.packetInBuf.capacity();
        byte[] payload = new byte[capacity + 100];
        Arrays.fill(payload, (byte) 'P');
        rawPeer.getOutputStream().write(payload);
        rawPeer.getOutputStream().flush();
        Thread.sleep(50);

        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.setAllowPlaintextWhenSslEnabled(true);
        ChannelReader mockReader = mock(ChannelReader.class);
        AtomicInteger decodedBytes = new AtomicInteger();
        doAnswer(inv -> {
            ByteBuffer buf = inv.getArgument(1);
            decodedBytes.addAndGet(buf.remaining());
            return null;
        }).when(mockReader).decode(any(), any());
        config.setChannelReader(mockReader);

        ChannelSSLRunner runner = new ChannelSSLRunner(null, new ChannelSSLContext(ch, engineCtx), config);
        try {
            Assertions.assertTrue(runner.beforeReady());
            Assertions.assertFalse(runner.isSSL);
            // both the full buffer and the 100-byte tail were drained in two decode cycles
            verify(mockReader, times(2)).decode(any(), any());
            Assertions.assertEquals(payload.length, decodedBytes.get());
        } finally {
            rawPeer.close();
            ch.close();
        }
    }

    /** First read fills the whole packet buffer and the peer EOFs: second read returns -1 → release. */
    @Test
    public void testSSLPlaintextDrainEofAfterFullBuffer() throws Exception {
        Socket rawPeer = new Socket();
        SocketChannel ch = connectRawPeerPair(rawPeer);
        SSLEngineContext engineCtx = newServerEngineCtx();
        int capacity = engineCtx.packetInBuf.capacity();
        byte[] payload = new byte[capacity];
        Arrays.fill(payload, (byte) 'P');
        rawPeer.getOutputStream().write(payload);
        rawPeer.shutdownOutput(); // FIN follows the full payload
        Thread.sleep(50);

        NioConfig config = new NioConfig();
        config.setReadBufferSize(4096);
        config.setAllowPlaintextWhenSslEnabled(true);
        ChannelReader mockReader = mock(ChannelReader.class);
        AtomicInteger decodedBytes = new AtomicInteger();
        doAnswer(inv -> {
            ByteBuffer buf = inv.getArgument(1);
            decodedBytes.addAndGet(buf.remaining());
            return null;
        }).when(mockReader).decode(any(), any());
        config.setChannelReader(mockReader);

        ChannelSSLRunner runner = new ChannelSSLRunner(null, new ChannelSSLContext(ch, engineCtx), config);
        try {
            Assertions.assertFalse(runner.beforeReady()); // -1 from the post-drain read → handshake code -1
            Assertions.assertTrue(runner.closed);
            verify(mockReader, times(1)).decode(any(), any());
            Assertions.assertEquals(capacity, decodedBytes.get());
        } finally {
            rawPeer.close();
            ch.close();
        }
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
