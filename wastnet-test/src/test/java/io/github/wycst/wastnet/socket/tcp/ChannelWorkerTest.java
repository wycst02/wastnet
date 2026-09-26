package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChannelWorker}.
 *
 * @author wangyc
 */
public class ChannelWorkerTest {

    private static TCPServer server;
    private static ChannelWorker worker;

    @BeforeAll
    public static void setup() throws Exception {
        java.net.ServerSocket ss = new java.net.ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();
        server = new TCPServer(port, new NioConfig());
        server.config().setChannelHandler(new ChannelHandler<byte[]>() {
            @Override
            public void onHandle(ChannelContext ctx, byte[] message) {}
        });
        server.start();
        worker = server.workers()[0];
    }

    @AfterAll
    public static void cleanup() {
        if (server != null) server.shutdown();
    }

    @Test
    public void testConstructor() {
        assertNotNull(worker);
        assertNotNull(worker.selector);
    }

    @Test
    public void testRunAsync() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        worker.runAsync(new Runnable() {
            public void run() { latch.countDown(); }
        });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    public void testRunAsyncMultiple() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; ++i) {
            worker.runAsync(new Runnable() {
                public void run() { latch.countDown(); }
            });
        }
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    public void testDecrementConnectionCount() {
        int before = worker.getConnectionCount();
        worker.decrementConnectionCount();
        assertEquals(before - 1, worker.getConnectionCount());
    }

    @Test
    public void testDecrementConnectionCountMultiple() {
        int before = worker.getConnectionCount();
        worker.decrementConnectionCount();
        worker.decrementConnectionCount();
        assertEquals(before - 2, worker.getConnectionCount());
    }

    @Test
    public void testGetScheduledExecutorService() {
        assertNotNull(worker.getScheduledExecutorService());
        assertSame(worker.getScheduledExecutorService(), worker.getScheduledExecutorService());
    }

    @Test
    public void testGetScheduledExecutorServiceCanSchedule() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        worker.getScheduledExecutorService().schedule(new Runnable() {
            public void run() { latch.countDown(); }
        }, 10, TimeUnit.MILLISECONDS);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    @Test
    public void testWakeup() {
        worker.wakeup();
    }

    @Test
    public void testIsSyncWorkerThread() {
        assertFalse(ChannelWorker.isSyncWorkerThread());
    }

    @Test
    public void testGetConnectionCount() {
        worker.getConnectionCount(); // just verify no exception
    }
}
