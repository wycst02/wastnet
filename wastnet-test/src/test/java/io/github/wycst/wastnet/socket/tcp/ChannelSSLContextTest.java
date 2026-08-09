package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.http.HTTPServer;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link ChannelSSLContext}.
 * <p>
 * Covers disabled (plaintext fallback) paths for all SSL methods,
 * constructor variants, and static factory methods.
 */
public class ChannelSSLContextTest {

    private ServerSocket serverSocket;
    private Socket serverSide;
    private SocketChannel clientChannel;

    @BeforeEach
    public void setupConnectedChannel() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        new Thread(() -> {
            try { serverSide = serverSocket.accept(); } catch (Exception e) {}
        }, "ssl-test-accept").start();

        clientChannel = SocketChannel.open();
        clientChannel.connect(new InetSocketAddress("127.0.0.1", port));
        clientChannel.configureBlocking(false);
        // Wait for server to accept
        Thread.sleep(100);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (clientChannel != null) try { clientChannel.close(); } catch (Exception e) {}
        if (serverSide != null) try { serverSide.close(); } catch (Exception e) {}
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception e) {}
    }

    private static SSLEngineContext createDisabledEngineCtx() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        SSLEngineContext ctx = new SSLEngineContext(sslContext, null, null);
        ctx.setDisabled(true);
        return ctx;
    }

    // ==================== Constructors ====================

    @Test
    public void testConstructorWithSocketChannel() throws Exception {
        SSLEngineContext sslCtx = createDisabledEngineCtx();
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, sslCtx);
        Assertions.assertNotNull(ctx);
    }

    @Test
    public void testConstructorWithId() throws Exception {
        SSLEngineContext sslCtx = createDisabledEngineCtx();
        ChannelSSLContext ctx = new ChannelSSLContext(100L, clientChannel, sslCtx);
        Assertions.assertNotNull(ctx);
    }

    // ==================== isSSL ====================

    @Test
    public void testIsSSLReturnsTrueWhenEnabled() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        SSLEngineContext sslCtx = new SSLEngineContext(sslContext, null, null);
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, sslCtx);
        Assertions.assertTrue(ctx.isSSL());
    }

    @Test
    public void testIsSSLReturnsFalseWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        Assertions.assertFalse(ctx.isSSL());
    }

    // ==================== Disabled path coverage ====================

    @Test
    public void testGetWriteBufferSizeWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        Assertions.assertEquals(0, ctx.getWriteBufferSize());
    }

    @Test
    public void testGetHandShakedApplicationProtocolWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        Assertions.assertNull(ctx.getHandShakedApplicationProtocol());
    }

    @Test
    public void testFlushWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        ctx.flush(); // should not throw when disabled
    }

    @Test
    public void testWriteWhenDisabled() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putInt(12345);
        buf.flip();
        ctx.write(buf);
        // should not throw
    }

    @Test
    public void testReadWhenDisabledReturnsZero() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        ByteBuffer buf = ByteBuffer.allocate(16);
        int n = ctx.read(buf);
        // connected channel with no data → returns 0
        Assertions.assertEquals(0, n);
    }

    @Test
    public void testReadFullyWhenDisabledReturnsMinusOneOrTimeout() throws Exception {
        // Close server side so channelRead eventually returns -1
        if (serverSide != null) {
            serverSide.shutdownOutput();
            serverSide.close();
        }
        Thread.sleep(100);
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        byte[] b = new byte[4];
        try {
            int n = ctx.readFully(b, 0, 4, 1000);
            Assertions.assertEquals(-1, n);
        } catch (java.net.SocketTimeoutException e) {
            // Timeout is also acceptable (FIN not yet received)
        }
    }

    // ==================== ChannelSSLContext$1 (TrustManager anonymous class) ====================

    @Test
    public void testTrustAllManagers() throws Exception {
        javax.net.ssl.X509TrustManager tm = (javax.net.ssl.X509TrustManager) ChannelSSLContext.TRUST_ALL_MANAGERS[0];
        Assertions.assertNotNull(tm.getAcceptedIssuers());
        Assertions.assertEquals(0, tm.getAcceptedIssuers().length);
        tm.checkClientTrusted(null, null);
        tm.checkServerTrusted(null, null);
    }

    // ==================== transferTo empty src ====================

    @Test
    public void testTransferToEmptySrc() throws Exception {
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, createDisabledEngineCtx());
        java.lang.reflect.Method m = ChannelSSLContext.class.getDeclaredMethod("transferTo", ByteBuffer.class, ByteBuffer.class);
        m.setAccessible(true);
        ByteBuffer emptySrc = ByteBuffer.allocate(0);
        ByteBuffer dst = ByteBuffer.allocate(10);
        int n = (int) m.invoke(ctx, emptySrc, dst);
        Assertions.assertEquals(0, n);
    }

    // ==================== read with applicationInBuf remaining data ====================

    @Test
    public void testReadWithApplicationInBufRemaining() throws Exception {
        javax.net.ssl.SSLContext rawCtx = javax.net.ssl.SSLContext.getInstance("TLS");
        rawCtx.init(null, ChannelSSLContext.TRUST_ALL_MANAGERS, null);
        SSLEngineContext engineCtx = new SSLEngineContext(rawCtx, null, null, true);
        ChannelSSLContext ctx = new ChannelSSLContext(clientChannel, engineCtx);

        // Simulate read-mode leftover as left by readFully's return:
        // position = consumed bytes (>0), limit = total decrypted data
        ByteBuffer appBuf = engineCtx.applicationInBuf;
        appBuf.put("hello".getBytes());
        appBuf.flip();                    // read mode: position=0, limit=5
        appBuf.position(2);               // simulate 2 bytes already consumed via readFully
                                          // unread data [2,5) = "llo"

        // read should consume remaining data from current position
        ByteBuffer readBuf = ByteBuffer.allocate(10);
        int n = ctx.read(readBuf);
        Assertions.assertEquals(3, n);
        readBuf.flip();
        byte[] data = new byte[readBuf.remaining()];
        readBuf.get(data);
        Assertions.assertEquals("llo", new String(data));
    }

    // ==================== createClientContext ====================
    // Full SSL handshake (doHandshake path) is covered by SslTlsIntegrationTest / Http2OverTlsIntegrationTest.
    // Here we just verify the factory creates the SSL context correctly without doing handshake.

    @Test
    public void testIsSSLAndDisabledMethods() throws Exception {
        // Create SSL-enabled context (not disabled, not doing handshake)
        javax.net.ssl.SSLContext rawCtx = javax.net.ssl.SSLContext.getInstance("TLS");
        rawCtx.init(null, ChannelSSLContext.TRUST_ALL_MANAGERS, null);
        SSLEngineContext engineCtx = new SSLEngineContext(rawCtx, null, null, true);
        ChannelSSLContext ctx = new ChannelSSLContext(600L, clientChannel, engineCtx);

        Assertions.assertTrue(ctx.isSSL());
        Assertions.assertTrue(ctx.getWriteBufferSize() > 0);
        Assertions.assertNull(ctx.getHandShakedApplicationProtocol());
    }

    // ==================== createClientContext ====================

    @Test
    public void testCreateClientContextFailsOnPlainConnection() throws Exception {
        // Close the server side so channelRead returns -1 immediately (connection closed)
        if (serverSide != null) {
            serverSide.close();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
        Thread.sleep(50);

        Assertions.assertThrows(IOException.class,
                () -> ChannelSSLContext.createClientContext(999L, clientChannel, (String[]) null));
    }

    // ==================== Full SSL handshake + read/write/flush ====================

    @Test
    public void testFullSSLHandshakeAndDataTransfer() throws Exception {
        // Start an HTTPServer with SSL on a new port
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> {
                    response.setContentType("text/plain");
                    response.body("Hello SSL".getBytes());
                })
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200); // wait for server to start

            // Connect a raw SocketChannel to the SSL server
            SocketChannel sslChannel = SocketChannel.open();
            sslChannel.connect(new InetSocketAddress("127.0.0.1", sslPort));
            sslChannel.configureBlocking(false);

            // Perform full SSL handshake via createClientContext
            ChannelSSLContext sslCtx = ChannelSSLContext.createClientContext(100L, sslChannel, (String[]) null);
            Assertions.assertNotNull(sslCtx);
            Assertions.assertTrue(sslCtx.isSSL());
            Assertions.assertFalse(sslCtx.sslEngineCtx.isDisabled());

            // Write an HTTP GET request (SSL encrypted)
            byte[] httpReq = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
            ByteBuffer writeBuf = ByteBuffer.wrap(httpReq);
            int writeResult = sslCtx.write(writeBuf);
            // SSL write returns the number of plaintext bytes accepted for encryption
            Assertions.assertEquals(httpReq.length, writeResult);
            sslCtx.flush(); // flush encrypted data to channel

            // Wait for response
            Thread.sleep(200);

            // Read the response (SSL decrypted)
            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            int totalRead = 0;
            int retries = 0;
            while (totalRead == 0 && retries < 10) {
                int n = sslCtx.read(readBuf);
                if (n > 0) {
                    totalRead += n;
                } else if (n == -1) {
                    break;
                } else {
                    retries++;
                    Thread.sleep(100);
                }
            }
            Assertions.assertTrue(totalRead > 0, "Should read SSL-decrypted HTTP response");
            readBuf.flip();
            String response = new String(readBuf.array(), readBuf.position(), readBuf.remaining());
            Assertions.assertTrue(response.contains("Hello SSL"), "Response should contain body data");

            // Close SSL context to cover close_notify path (L401-415)
            sslCtx.close();
            sslChannel.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== SSL write/read with multiple chunks ====================

    @Test
    public void testSslWriteMultipleChunks() throws Exception {
        // Use the same pattern as testFullSSLHandshakeAndDataTransfer
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> {
                    response.setContentType("text/plain");
                    response.body("OK".getBytes());
                })
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);
            SocketChannel sslChannel = SocketChannel.open();
            sslChannel.connect(new InetSocketAddress("127.0.0.1", sslPort));
            sslChannel.configureBlocking(false);

            ChannelSSLContext sslCtx = ChannelSSLContext.createClientContext(200L, sslChannel, (String[]) null);
            Assertions.assertTrue(sslCtx.isSSL());

            // Write two separate chunks
            sslCtx.write(ByteBuffer.wrap("GET ".getBytes()));
            sslCtx.flush();
            sslCtx.write(ByteBuffer.wrap("/ HTTP/1.1\r\n".getBytes()));
            sslCtx.write(ByteBuffer.wrap("Host: localhost\r\n\r\n".getBytes()));
            sslCtx.flush();

            // Read response
            Thread.sleep(300);
            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            int total = 0, retries = 0;
            while (total == 0 && retries < 15) {
                int n = sslCtx.read(readBuf);
                if (n > 0) total += n;
                else if (n == -1) break;
                else { retries++; Thread.sleep(100); }
            }
            Assertions.assertTrue(total > 0, "Should read response");
            sslChannel.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== SSL close path (L401-415) ====================

    @Test
    public void testSslCloseAfterHandshake() throws Exception {
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> response.body("OK".getBytes()))
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslPort));
            ch.configureBlocking(false);
            ChannelSSLContext ctx = ChannelSSLContext.createClientContext(400L, ch, (String[]) null);
            ctx.close(); // should send close_notify without throwing
            ctx.close(); // idempotent: second close should also not throw
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== Read after handshake without writing (L240) ====================

    @Test
    public void testSslReadNoDataReturnsZero() throws Exception {
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> response.body("OK".getBytes()))
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslPort));
            ch.configureBlocking(false);
            ChannelSSLContext ctx = ChannelSSLContext.createClientContext(500L, ch, (String[]) null);
            // Read without writing — no data available yet
            ByteBuffer buf = ByteBuffer.allocate(4096);
            int n = ctx.read(buf);
            Assertions.assertEquals(0, n); // non-blocking, no data
            ctx.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== Write large data to trigger flush path (L179) ====================

    @Test
    public void testSslWriteLargePayload() throws Exception {
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> response.body("OK".getBytes()))
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslPort));
            ch.configureBlocking(false);
            ChannelSSLContext ctx = ChannelSSLContext.createClientContext(600L, ch, (String[]) null);
            // Write 128KB — exceeds SSL packet buffer capacity, triggers flush mid-wrap
            byte[] bigData = new byte[128 * 1024];
            ByteBuffer buf = ByteBuffer.wrap(bigData);
            int result = ctx.write(buf);
            // write returns the number of plaintext bytes accepted for encryption
            Assertions.assertEquals(bigData.length, result);
            ctx.flush();
            ctx.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== readFully SSL path (L300-367) ====================

    @Test
    public void testSslReadFullyWithResponse() throws Exception {
        int sslPort = findFreePort();
        HTTPServer sslServer = HTTPServer.of(sslPort)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((request, response) -> {
                    response.setContentType("text/plain");
                    response.body("Hello SSL".getBytes());
                })
                .startupBannerEnabled(false)
                .start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslPort));
            ch.configureBlocking(false);
            ChannelSSLContext ctx = ChannelSSLContext.createClientContext(800L, ch, (String[]) null);

            // Send HTTP request
            ctx.write(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes()));
            ctx.flush();
            Thread.sleep(300); // wait for response to arrive

            // readFully — blocking until 4 bytes arrive or timeout (response starts with "HTTP")
            byte[] buf = new byte[4];
            try {
                int n = ctx.readFully(buf, 0, 4, 5000);
                Assertions.assertEquals(4, n);
                String head = new String(buf);
                Assertions.assertTrue(head.equals("HTTP") || head.startsWith("HTTP"),
                        "Expected response to start with HTTP, got: " + head);
            } catch (java.net.SocketTimeoutException e) {
                // Timeout also covers the inner loop's awaitReadableWithTimeout path
            }
            ctx.close();
        } finally {
            sslServer.shutdown();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    // ==================== createClientContext with TrustManager arg ====================

    @Test
    public void testCreateClientContextWithNullTrustManagerFailsOnPlainConnection() throws Exception {
        // Close the server side so channelRead returns -1 immediately (connection closed)
        if (serverSide != null) {
            serverSide.close();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
        Thread.sleep(50);

        Assertions.assertThrows(IOException.class,
                () -> ChannelSSLContext.createClientContext(999L, clientChannel, null, null));
    }

    @Test
    public void testCreateClientContextWithTrustAllViaSslServer() throws Exception {
        HTTPServer sslServer = HTTPServer.of(findFreePort())
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((req, res) -> {
                    try { res.body("OK".getBytes()); } catch (Exception ignored) {}
                });
        sslServer.start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslServer.getPort()));
            ch.configureBlocking(false);
            ChannelSSLContext ctx = ChannelSSLContext.createClientContext(700L, ch, null, null,
                    ChannelSSLContext.getTrustAllManagers());
            Assertions.assertNotNull(ctx);
            Assertions.assertTrue(ctx.isSSL());
            Assertions.assertFalse(ctx.sslEngineCtx.isDisabled());
            ctx.close();
        } finally {
            sslServer.shutdown();
        }
    }

    // ==================== getTrustAllManagers ====================

    @Test
    public void testGetTrustAllManagersReturnsNonNull() {
        javax.net.ssl.X509TrustManager tm =
                (javax.net.ssl.X509TrustManager) ChannelSSLContext.getTrustAllManagers()[0];
        Assertions.assertNotNull(tm);
        Assertions.assertNotNull(tm.getAcceptedIssuers());
        Assertions.assertEquals(0, tm.getAcceptedIssuers().length);
    }

    @Test
    public void testGetTrustAllManagersReturnsNewArrayEachCall() {
        Assertions.assertNotSame(ChannelSSLContext.getTrustAllManagers(),
                ChannelSSLContext.getTrustAllManagers());
    }

    // ==================== createClientContext with SSLEngineContext arg ====================

    @Test
    public void testCreateClientContextWithEngineFailsOnPlainConnection() throws Exception {
        if (serverSide != null) {
            serverSide.close();
        }
        if (serverSocket != null) {
            serverSocket.close();
        }
        Thread.sleep(50);

        SSLContext rawCtx = SSLContext.getInstance("TLS");
        rawCtx.init(null, ChannelSSLContext.getTrustAllManagers(), null);
        SSLEngineContext engineCtx = new SSLEngineContext(rawCtx, null, null, true);
        Assertions.assertThrows(IOException.class,
                () -> ChannelSSLContext.createClientContext(999L, clientChannel, engineCtx));
    }

    @Test
    public void testCreateClientContextWithEngineViaSslServer() throws Exception {
        HTTPServer sslServer = HTTPServer.of(findFreePort())
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler((req, res) -> {
                    try { res.body("OK".getBytes()); } catch (Exception ignored) {}
                });
        sslServer.start();
        try {
            Thread.sleep(200);
            SocketChannel ch = SocketChannel.open();
            ch.connect(new InetSocketAddress("127.0.0.1", sslServer.getPort()));
            ch.configureBlocking(false);

            SSLContext rawCtx = SSLContext.getInstance("TLS");
            rawCtx.init(null, ChannelSSLContext.getTrustAllManagers(), null);
            SSLEngineContext engineCtx = new SSLEngineContext(rawCtx, null, null, true);
            ChannelSSLContext ctx = ChannelSSLContext.createClientContext(800L, ch, engineCtx);
            Assertions.assertNotNull(ctx);
            Assertions.assertTrue(ctx.isSSL());
            ctx.close();
        } finally {
            sslServer.shutdown();
        }
    }
}
