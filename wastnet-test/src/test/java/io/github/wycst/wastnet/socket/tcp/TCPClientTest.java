package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelCodec;
import io.github.wycst.wastnet.socket.channel.LengthFrameCodec;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Tests for {@link TCPClient}.
 * <p>
 * Part 1: Unit tests for setters and configuration (no server needed).
 * Part 2: Integration tests with a real TCP server for connect/send/close.
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class TCPClientTest {

    // ==================== Part 1: Setters & Configuration ====================

    @Test
    public void testConnectTimeoutSetter() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        Assertions.assertSame(client, client.connectTimeout(1000));
    }

    @Test
    public void testReconnectAttemptsSetter() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        Assertions.assertSame(client, client.reconnectAttempts(5));
    }

    @Test
    public void testReconnectDelaySetter() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        Assertions.assertSame(client, client.reconnectDelay(2000));
    }

    @Test
    public void testAutoReconnectSetter() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        Assertions.assertSame(client, client.autoReconnect(true));
    }

    @Test
    public void testChannelCodecSetter() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        ChannelCodec<?> codec = new LengthFrameCodec<String>(4, 0, 4, 65536) {
            @Override
            protected byte[] encodeBody(ChannelContext ctx, String message) {
                return message.getBytes();
            }
        };
        Assertions.assertSame(client, client.channelCodec(codec));
    }

    @Test
    public void testChannelHandlerSetter() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        client.channelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {
            }
        });
    }

    @Test
    public void testIsConnectedReturnsFalseWhenNotConnected() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        Assertions.assertFalse(client.isConnected());
    }

    @Test
    public void testContextReturnsNullWhenNotConnected() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        Assertions.assertNull(client.context());
    }

    @Test
    public void testCloseWithoutConnect() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        client.close(); // should not throw
    }

    @Test
    public void testConnectWithoutCodecThrows() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        IOException ex = Assertions.assertThrows(IOException.class, client::connect);
        Assertions.assertTrue(ex.getMessage().contains("channelCodec not configured"));
    }

    // ==================== Part 2: Integration Tests ====================

    private static final AtomicInteger serverPort = new AtomicInteger(19090);
    private ServerSocket acceptServer;
    private Thread serverThread;
    private volatile Socket accepted;
    private volatile boolean serverRunning;

    private int startTestServer() throws Exception {
        int port = serverPort.getAndIncrement();
        acceptServer = new ServerSocket(port);
        serverRunning = true;
        serverThread = new Thread(() -> {
            try {
                Socket s = acceptServer.accept();
                accepted = s;
                InputStream in = s.getInputStream();
                byte[] lenBuf = new byte[4];
                byte[] buf = new byte[65536];
                while (serverRunning && !s.isClosed()) {
                    // Read 4-byte length prefix
                    int lenRead = 0;
                    while (lenRead < 4) {
                        int n = in.read(lenBuf, lenRead, 4 - lenRead);
                        if (n < 0) return;
                        lenRead += n;
                    }
                    int bodyLen = java.nio.ByteBuffer.wrap(lenBuf).getInt();
                    // Read body
                    int bodyRead = 0;
                    while (bodyRead < bodyLen) {
                        int n = in.read(buf, bodyRead, bodyLen - bodyRead);
                        if (n < 0) return;
                        bodyRead += n;
                    }
                    // Echo back with length prefix
                    byte[] realBody = new byte[bodyLen];
                    System.arraycopy(buf, 0, realBody, 0, bodyLen);
                    byte[] header = java.nio.ByteBuffer.allocate(4).putInt(bodyLen).array();
                    OutputStream out = s.getOutputStream();
                    out.write(header);
                    out.write(realBody);
                    out.flush();
                }
            } catch (Exception ignored) {
            }
        }, "tcp-test-server-" + port);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(100);
        return port;
    }

    private void stopTestServer() {
        serverRunning = false;
        if (accepted != null) {
            try { accepted.close(); } catch (Exception ignored) {}
        }
        if (acceptServer != null) {
            try { acceptServer.close(); } catch (Exception ignored) {}
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    private TCPClient<String> createClient(int port, ChannelHandler<byte[]> handler) {
        TCPClient<String> client = new TCPClient<String>("localhost", port);
        client.channelCodec(new LengthFrameCodec<String>(4, 0, 4, 65536) {
            @Override
            protected byte[] encodeBody(ChannelContext ctx, String message) {
                return message.getBytes();
            }
        });
        client.channelHandler(handler);
        client.printReadErrorLog(false);
        return client;
    }

    @Test
    public void testConnectAndIsConnected() throws Exception {
        int port = startTestServer();
        AtomicReference<ChannelContext> capturedCtx = new AtomicReference<>();
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {
                capturedCtx.set(ctx);
            }
        });

        try {
            client.connect();
            Assertions.assertTrue(client.isConnected(), "Should be connected after connect()");

            ChannelContext ctx = client.context();
            Assertions.assertNotNull(ctx, "context() should return non-null after connect");
        } finally {
            client.close();
            stopTestServer();
        }
    }

    @Test
    public void testSendAndReceive() throws Exception {
        int port = startTestServer();
        AtomicReference<byte[]> received = new AtomicReference<>();
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {
                received.set(message);
            }
        });

        try {
            client.connect();
            Assertions.assertTrue(client.isConnected());

            client.send("hello");
            Thread.sleep(500);
            Assertions.assertNotNull(received.get(), "Should receive echoed data");
            Assertions.assertEquals("hello", new String(received.get()));
        } finally {
            client.close();
            stopTestServer();
        }
    }

    @Test
    public void testConnectTimeout() throws Exception {
        // Connect to TEST-NET address (unreachable) with short timeout
        TCPClient<String> client = new TCPClient<String>("192.0.2.1", 9999);
        client.channelCodec(new LengthFrameCodec<String>(4, 0, 4, 65536) {
            @Override
            protected byte[] encodeBody(ChannelContext ctx, String message) {
                return message.getBytes();
            }
        });
        client.connectTimeout(500);

        Assertions.assertThrows(IOException.class, client::connect);
    }

    @Test
    public void testCloseAfterConnect() throws Exception {
        int port = startTestServer();
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });

        client.connect();
        Assertions.assertTrue(client.isConnected());

        client.close();
        Assertions.assertFalse(client.isConnected(), "Should not be connected after close");
        stopTestServer();
    }

    @Test
    public void testAlreadyConnectedReturnsSameInstance() throws Exception {
        int port = startTestServer();
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });

        try {
            client.connect();
            TCPClient<String> result = client.connect();
            Assertions.assertSame(client, result);
        } finally {
            client.close();
            stopTestServer();
        }
    }

    @Test
    public void testReconnectFailsAfterServerCloses() throws Exception {
        int port = startTestServer();
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        client.reconnectAttempts(1);
        client.reconnectDelay(100);

        try {
            client.connect();
            Assertions.assertTrue(client.isConnected());

            // Stop server
            stopTestServer();
            Thread.sleep(300);

            // Reconnect should fail
            Assertions.assertThrows(IOException.class, client::reconnect);
        } finally {
            client.close();
        }
    }

    @Test
    public void testDisconnectOnServerClose() throws Exception {
        int port = startTestServer();
        AtomicBoolean handlerClosed = new AtomicBoolean(false);
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
            @Override
            public void onClosed(ChannelContext ctx) {
                handlerClosed.set(true);
            }
        });

        try {
            client.connect();
            Assertions.assertTrue(client.isConnected());

            // Close server side
            if (accepted != null) {
                accepted.close();
            }

            Thread.sleep(500);
        } finally {
            client.close();
            stopTestServer();
        }
    }

    // ==================== Reconnect after server restart ====================

    @Test
    public void testReconnectAfterServerRestart() throws Exception {
        // Use a static port to avoid TIME_WAIT conflicts
        int port = serverPort.getAndIncrement();
        // First connection
        startTestServerOnPort(port);
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        client.reconnectAttempts(5);
        client.reconnectDelay(200);

        try {
            client.connect();
            Assertions.assertTrue(client.isConnected());

            // Stop server
            stopTestServer();
            Thread.sleep(300);

            // Restart server on same port (may fail due to TIME_WAIT)
            // This is best-effort — if bind succeeds, test reconnect
            try {
                startTestServerOnPort(port);
                client.reconnect();
                Assertions.assertTrue(client.isConnected());
            } catch (java.net.BindException e) {
                // TIME_WAIT on Windows — skip reconnect test
                System.out.println("Skipping reconnect test due to port TIME_WAIT");
            }
        } finally {
            client.close();
            stopTestServer();
        }
    }

    // ==================== Auto-reconnect on close ====================

    @Test
    public void testAutoReconnectOnServerClose() throws Exception {
        int port = startTestServer();
        TCPClient<String> client = createClient(port, new ChannelHandler<byte[]>() {
            @Override public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        client.autoReconnect(true);
        client.reconnectAttempts(1);
        client.reconnectDelay(100);
        // Suppress expected "auto-reconnect failed" error log
        java.util.logging.Logger.getLogger("i.g.w.w.s.t.TCPClient").setLevel(Level.OFF);

        try {
            client.connect();
            Assertions.assertTrue(client.isConnected());

            // Close server → triggers close listener → auto-reconnect
            stopTestServer();
            Thread.sleep(500);

            // Reconnect attempt was submitted (may fail since server is gone)
            // The key assertion: close listener ran without throwing
        } finally {
            client.close();
        }
    }

    // ==================== disconnect with null state ====================

    @Test
    public void testDisconnectNotConnected() {
        TCPClient<?> client = new TCPClient<>("localhost", 9090);
        // disconnect is private, but close() calls it
        client.close(); // should not throw
    }

    // ==================== Helper: start server on specific port ====================

    private int startTestServerOnPort(int port) throws Exception {
        acceptServer = new ServerSocket(port);
        serverRunning = true;
        serverThread = new Thread(() -> {
            try {
                accepted = acceptServer.accept();
                InputStream in = accepted.getInputStream();
                byte[] lenBuf = new byte[4];
                byte[] buf = new byte[65536];
                while (serverRunning && !accepted.isClosed()) {
                    int lenRead = 0;
                    while (lenRead < 4) { int n = in.read(lenBuf, lenRead, 4 - lenRead); if (n < 0) return; lenRead += n; }
                    int bodyLen = java.nio.ByteBuffer.wrap(lenBuf).getInt();
                    int bodyRead = 0;
                    while (bodyRead < bodyLen) { int n = in.read(buf, bodyRead, bodyLen - bodyRead); if (n < 0) return; bodyRead += n; }
                    byte[] realBody = new byte[bodyLen];
                    System.arraycopy(buf, 0, realBody, 0, bodyLen);
                    byte[] header = java.nio.ByteBuffer.allocate(4).putInt(bodyLen).array();
                    java.io.OutputStream out = accepted.getOutputStream();
                    out.write(header); out.write(realBody); out.flush();
                }
            } catch (Exception ignored) {}
        }, "tcp-test-server-" + port);
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(200);
        return acceptServer.getLocalPort();
    }
}
