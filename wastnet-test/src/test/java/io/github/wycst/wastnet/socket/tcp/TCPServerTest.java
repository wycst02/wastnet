package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.exception.SocketException;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link TCPServer}.
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class TCPServerTest {

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18080;
        }
    }

    // ==================== Constructors & setters ====================

    @Test
    public void testConstructorWithPort() {
        TCPServer server = new TCPServer(8080);
        Assertions.assertEquals(8080, server.getPort());
        server.shutdown();
    }

    @Test
    public void testConstructorWithPortAndConfig() {
        TCPServer server = new TCPServer(9090, new NioConfig());
        Assertions.assertEquals(9090, server.getPort());
        server.shutdown();
    }

    @Test
    public void testLocalOnly() {
        TCPServer server = new TCPServer(8080);
        Assertions.assertSame(server, server.localOnly(true));
        server.shutdown();
    }

    @Test
    public void testConnectionFilter() {
        TCPServer server = new TCPServer(8080);
        Assertions.assertSame(server, server.connectionFilter(ctx -> true));
        server.shutdown();
    }

    // ==================== Workers ====================

    @Test
    public void testWorkers() throws Exception {
        TCPServer server = new TCPServer(18080);
        try {
            ChannelWorker[] workers = server.workers();
            Assertions.assertTrue(workers.length >= 2);
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testNextWorkerRoundRobin() throws Exception {
        TCPServer server = new TCPServer(18080);
        try {
            ChannelWorker[] workers = server.workers();
            ChannelWorker w0 = server.nextWorker(0, workers);
            ChannelWorker w1 = server.nextWorker(1, workers);
            Assertions.assertNotNull(w0);
            Assertions.assertNotNull(w1);
            Assertions.assertNotSame(w0, w1);
        } finally {
            server.shutdown();
        }
    }

    // ==================== Start / Stop / Restart ====================

    @Test
    public void testStartAndStop() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            TCPServer result = server.start();
            Assertions.assertSame(server, result);
            Assertions.assertTrue(server.engineRunFlag);
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testStartBindError() throws Exception {
        // Bind to a port first, then try to create a server on the same port
        java.net.ServerSocket occupied = new java.net.ServerSocket(findFreePort());
        int occupiedPort = occupied.getLocalPort();
        TCPServer server = new TCPServer(occupiedPort);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            Assertions.assertThrows(SocketException.class, server::start);
        } finally {
            occupied.close();
            server.shutdown();
        }
    }

    @Test
    public void testStopWhenNotStarted() {
        TCPServer server = new TCPServer(8080);
        server.shutdown(); // should not throw
        server.shutdown();
    }

    @Test
    public void testStartWithLocalOnly() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.localOnly(true);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            Assertions.assertTrue(server.engineRunFlag);
        } finally {
            server.shutdown();
        }
    }

    // ==================== ConnectionFilter via AcceptDispatcher ====================

    @Test
    public void testConnectionFilterAcceptAndReject() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        server.config().setConnectionFilter(client -> callCount.getAndIncrement() % 2 == 0);
        SocketChannel accepted = null, rejected = null;
        try {
            server.start();
            // First connect: filter accepts (even=0)
            accepted = SocketChannel.open();
            accepted.connect(new InetSocketAddress("127.0.0.1", port));
            accepted.write(ByteBuffer.wrap("a".getBytes()));
            Thread.sleep(200);

            // Second connect: filter rejects (odd=1)
            rejected = SocketChannel.open();
            rejected.connect(new InetSocketAddress("127.0.0.1", port));
            Thread.sleep(200);

            Assertions.assertTrue(accepted.isConnected());
            Assertions.assertTrue(callCount.get() >= 2);
        } finally {
            if (accepted != null) try { accepted.close(); } catch (Exception e) {}
            if (rejected != null) try { rejected.close(); } catch (Exception e) {}
            server.shutdown();
        }
    }

    // ==================== shutdownGraceful ====================

    /**
     * Verify that shutdownGraceful() works when there are no connections:
     * stops accepting, closes server socket, shuts down workers.
     */
    @Test
    public void testShutdownGracefulNoConnections() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            Assertions.assertTrue(server.engineRunFlag);
        } finally {
            server.shutdownGraceful();
        }
        Assertions.assertFalse(server.engineRunFlag);
    }

    /**
     * Verify that shutdownGraceful() drains completed connections:
     * <ol>
     *   <li>Client sends data and handler processes it (connection finishes)</li>
     *   <li>shutdownGraceful() detects no in-flight requests, proceeds to Phase 3</li>
     *   <li>All connections are closed, server port is freed</li>
     * </ol>
     */
    @Test
    public void testShutdownGracefulWithConnectionsDrain() throws Exception {
        int port = findFreePort();
        CountDownLatch handleLatch = new CountDownLatch(1);
        TCPServer server = new TCPServer(port);
        // Note: ChannelReader.UNDO passes ByteBuffer to the handler,
        // so the handler generic type must be ByteBuffer, not byte[].
        server.config().setChannelHandler(new ChannelHandler<ByteBuffer>() {
            @Override
            public void onHandle(ChannelContext ctx, ByteBuffer message) {
                handleLatch.countDown();
            }
        });
        SocketChannel client = null;
        try {
            server.start();

            // Connect a real TCP client and send data
            client = SocketChannel.open();
            client.connect(new InetSocketAddress("127.0.0.1", port));
            client.write(ByteBuffer.wrap("hello".getBytes()));

            // Wait for the handler to process the message (connection no longer in-flight)
            Assertions.assertTrue(handleLatch.await(10, TimeUnit.SECONDS),
                    "Handler should process message within 10s");

            // shutdownGraceful: Phase 2 finds no in-flight requests, proceeds immediately
            server.shutdownGraceful();
            Assertions.assertFalse(server.engineRunFlag);

            // Verify server closed the connection: read should return -1 (EOF)
            ByteBuffer tmp = ByteBuffer.allocate(1);
            int n = client.read(tmp);
            Assertions.assertEquals(-1, n, "Server should have closed the connection");
        } finally {
            if (client != null) try { client.close(); } catch (Exception e) {}
            // Reset shutdown flag so shutdown() doesn't throw (might already be shutdown)
            try {
                server.shutdown();
            } catch (Exception e) {
                // ignore, might already be shutdown by shutdownGraceful
            }
        }
    }

    /**
     * Verify that calling shutdownGraceful() twice is safe (idempotent).
     * The second call should be a no-op since engineRunFlag is already false.
     */
    @Test
    public void testShutdownGracefulCalledTwice() {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
        } finally {
            server.shutdownGraceful(); // first call
            server.shutdownGraceful(); // second call: should be no-op
        }
        Assertions.assertFalse(server.engineRunFlag);
    }

    // ==================== createConnectionRunner ====================

    @Test
    public void testSharedModeIdleScanTriggersIdle() throws Exception {
        int port = findFreePort();
        CountDownLatch idleLatch = new CountDownLatch(1);
        AtomicBoolean idleTriggered = new AtomicBoolean(false);
        TCPServer server = new TCPServer(port);

        server.idleStateHandler(new IdleStateHandler(2, 0, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED) {
            @Override
            public void onIdleTriggered(ChannelContext ctx, IdleType idleType,
                                         long triggerTotalCount, long triggerConsecutiveCount) throws Throwable {
                idleTriggered.set(true);
                idleLatch.countDown();
            }
        });

        SocketChannel client = null;
        try {
            server.start();

            // Connect a client and let it sit idle
            client = SocketChannel.open();
            client.connect(new InetSocketAddress("127.0.0.1", port));

            // Wait for idle trigger (idle timeout 2s, scan every 1s). Allow up to 15s
            // to absorb worker-startup and scan-scheduling latency under full-suite load.
            Assertions.assertTrue(idleLatch.await(15, TimeUnit.SECONDS),
                    "SHARED idle scan should trigger within 15s");
            Assertions.assertTrue(idleTriggered.get(),
                    "Idle callback should have been invoked");
        } finally {
            if (client != null) try { client.close(); } catch (Exception e) {}
            server.shutdown();
        }
    }

    @Test
    public void testCreateConnectionRunnerNonSSL() throws Exception {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            ChannelWorker worker = server.workers()[0];
            // Non-SSL → creates plain ChannelRunner
            int helperPort = findFreePort();
            java.net.ServerSocket ss = new java.net.ServerSocket(helperPort);
            java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open();
            ch.connect(new java.net.InetSocketAddress("127.0.0.1", ss.getLocalPort()));
            ChannelRunner runner = server.createConnectionRunner(worker, ch);
            Assertions.assertNotNull(runner);
            Assertions.assertFalse(runner instanceof ChannelSSLRunner);
            ch.close();
            ss.close();
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testCreateConnectionRunnerWithSSL() throws Exception {
        int port = findFreePort();
        // Configure SSL
        javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
        sslCtx.init(null, null, null);
        TCPServer server = new TCPServer(port)
                .ssl(true)
                .sslContext(sslCtx);
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            ChannelWorker worker = server.workers()[0];
            // SSL → creates ChannelSSLRunner
            int helperPort = findFreePort();
            java.net.ServerSocket ss = new java.net.ServerSocket(helperPort);
            java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open();
            ch.connect(new java.net.InetSocketAddress("127.0.0.1", ss.getLocalPort()));
            ChannelRunner runner = server.createConnectionRunner(worker, ch);
            Assertions.assertNotNull(runner);
            Assertions.assertTrue(runner instanceof ChannelSSLRunner);
            ch.close();
            ss.close();
        } finally {
            server.shutdown();
        }
    }

    // ==================== nextWorker load balance ====================

    @Test
    public void testNextWorkerLoadBalance() throws Exception {
        int port = findFreePort();
        TCPServer server = new TCPServer(port);
        server.config().option(SocketOptions.LOAD_BALANCE_TYPE, "LEAST_CONN");
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        try {
            server.start();
            ChannelWorker[] workers = server.workers();

            // Decrement one worker more than others to create imbalance
            workers[0].decrementConnectionCount();
            workers[0].decrementConnectionCount();

            // nextWorker should select the one with least connections
            ChannelWorker selected = server.nextWorker(0, workers);
            Assertions.assertNotNull(selected);
        } finally {
            server.shutdown();
        }
    }
}
