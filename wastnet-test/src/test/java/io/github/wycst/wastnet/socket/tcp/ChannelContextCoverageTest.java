package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.socket.channel.ChannelWriter;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.socket.handler.IdleStateHandlerTrigger;
import io.github.wycst.wastnet.socket.tcp.ChannelWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage tests for {@link ChannelContext}.
 * Covers simple getter/setter, I/O helper, and error-handling branches.
 */
public class ChannelContextCoverageTest {

    private java.nio.channels.SocketChannel realChannel;
    private ChannelContext ctx;
    private java.net.ServerSocket serverSocket;

    @BeforeEach
    public void setUp() throws Exception {
        // 使用真实连接的 SocketChannel（isOpen()/isConnected() 自然返回 true）
        // Mock 无法 stub isOpen()（final 方法），不同 JDK 行为不一致
        serverSocket = new java.net.ServerSocket(0);
        realChannel = java.nio.channels.SocketChannel.open(
                new java.net.InetSocketAddress("127.0.0.1", serverSocket.getLocalPort()));
        realChannel.configureBlocking(false);
        ctx = new ChannelContext(1L, realChannel, 1024);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (realChannel != null) realChannel.close();
        if (serverSocket != null) serverSocket.close();
    }

    // ==================== isWorkerThread (worker==null → true) ====================

    @Test
    public void testIsWorkerThreadNoWorkerSet() {
        Assertions.assertTrue(ctx.isWorkerThread());
    }

    // ==================== waitForWrite worker path (no worker → sleep 1ms) ====================

    @Test
    public void testWaitForWriteWorkerPath() throws IOException {
        Assertions.assertTrue(ctx.waitForWrite(1000));
    }

    // ==================== write(byte[], int, int) ====================

    @Test
    public void testWriteByteArrayWithOffset() throws IOException {
        byte[] data = "hello".getBytes();
        int n = ctx.write(data, 0, data.length);
        Assertions.assertTrue(n > 0);
    }

    @Test
    public void testWriteByteArray() throws IOException {
        byte[] data = "test".getBytes();
        int n = ctx.write(data);
        Assertions.assertTrue(n > 0);
    }

    // ==================== writeAllFlush ====================

    @Test
    public void testWriteAllFlush() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap("data".getBytes());
        ctx.writeAllFlush(buf);
    }

    // ==================== write(ByteBuffer) with null byteBuffer ====================

    @Test
    public void testWriteByteBufferNoBuffer() throws IOException {
        ChannelContext noBufCtx = new ChannelContext(2L, realChannel, 0);
        ByteBuffer data = ByteBuffer.wrap("test".getBytes());
        int n = noBufCtx.write(data);
        Assertions.assertTrue(n > 0);
    }

    // ==================== send() with/without ChannelWriter ====================

    @Test
    public void testSendWithoutWriter() {
        Assertions.assertThrows(IOException.class, () -> ctx.send("message"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSendWithWriterAndNonNullBuffer() throws IOException {
        ChannelWriter<String> writer = mock(ChannelWriter.class);
        when(writer.write(any(ChannelContext.class), anyString()))
                .thenReturn(ByteBuffer.wrap("msg".getBytes()));
        ctx.setChannelWriter(writer);
        ctx.send("message");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSendWithWriterAndNullBuffer() throws IOException {
        ChannelWriter<String> writer = mock(ChannelWriter.class);
        when(writer.write(any(ChannelContext.class), anyString()))
                .thenReturn(null);
        ctx.setChannelWriter(writer);
        ctx.send("message");
    }

    // ==================== attachment getter/setter ====================

    @Test
    public void testAttachmentSetAndGet() {
        Object obj = new Object();
        ctx.attachment(obj);
        Assertions.assertSame(obj, ctx.attachment());
    }

    @Test
    public void testAttachmentDefaultNull() {
        Assertions.assertNull(ctx.attachment());
    }

    // ==================== binding duplicate ====================

    @Test
    public void testBindingDuplicateThrows() {
        Object obj = new Object();
        ctx.binding(obj);
        Assertions.assertThrows(IllegalStateException.class, () -> ctx.binding(obj));
    }

    // ==================== addCloseListener first time ====================

    @Test
    public void testAddCloseListenerCreatesList() {
        AtomicBoolean called = new AtomicBoolean(false);
        ctx.addCloseListener(() -> called.set(true));
        ctx.close();
        Assertions.assertTrue(called.get());
    }

    // ==================== removeCloseListener when none added ====================

    @Test
    public void testRemoveCloseListenerWhenEmpty() {
        Runnable listener = () -> {};
        ctx.removeCloseListener(listener);
    }

    // ==================== setIdleTrigger ====================

    @Test
    public void testSetIdleTrigger() {
        IdleStateHandler handler = mock(IdleStateHandler.class);
        ctx.setIdleTrigger(handler);
        Assertions.assertNotNull(ctx.idleStateHandlerTrigger);
    }

    @Test
    public void testExclusiveTriggerWithRealWorker() throws Exception {
        IdleStateHandler handler = new IdleStateHandler(10, 20, TimeUnit.SECONDS) {
            @Override
            public void onIdleTriggered(ChannelContext channelCtx, IdleType idleType,
                                         long triggerTotalCount, long triggerConsecutiveCount) {}
        };
        // Real ChannelWorker + NioEngine needed because ctx.schedule() is final
        NioEngine<?> engine = new NioEngine<>(1);
        try {
            ChannelWorker realWorker = new ChannelWorker(engine);
            ctx.setWorker(realWorker);
            ctx.setIdleTrigger(handler);

            Assertions.assertNotNull(ctx.idleStateHandlerTrigger);
            // Cancel scheduled tasks and clean up
            ctx.idleStateHandlerTrigger.release();
            if (realWorker.scheduledExecutorService != null) {
                realWorker.scheduledExecutorService.shutdownNow();
            }
        } finally {
            engine.shutdown();
        }
    }

    @Test
    public void testScheduleReadWriteTaskWithRealWorker() throws Exception {
        IdleStateHandler handler = new IdleStateHandler(10, 20, TimeUnit.SECONDS) {
            @Override
            public void onIdleTriggered(ChannelContext channelCtx, IdleType idleType,
                                         long triggerTotalCount, long triggerConsecutiveCount) {}
        };
        NioEngine<?> engine = new NioEngine<>(1);
        try {
            ChannelWorker realWorker = new ChannelWorker(engine);
            ctx.setWorker(realWorker);
            ctx.setIdleTrigger(handler);

            // Make lastRead/lastWrite timestamps recent (avoids idle trigger)
            ctx.idleStateHandlerTrigger.onReadTriggered();
            ctx.idleStateHandlerTrigger.onWriteTriggered();

            // Invoke ScheduleReadTask.run() and ScheduleWriteTask.run() via reflection.
            // These are package-private inner classes, inaccessible from this package.
            // With rem > MIN_NANOS, they call scheduleReadTask/scheduleWriteTask.
            invokeScheduleTaskRun("ScheduleReadTask");
            invokeScheduleTaskRun("ScheduleWriteTask");

            // ctx.schedule() succeeded (read/write futures set by real worker)
            java.lang.reflect.Field readFutureF =
                    IdleStateHandlerTrigger.class.getDeclaredField("readIdleFuture");
            java.lang.reflect.Field writeFutureF =
                    IdleStateHandlerTrigger.class.getDeclaredField("writeIdleFuture");
            readFutureF.setAccessible(true);
            writeFutureF.setAccessible(true);
            Assertions.assertNotNull(readFutureF.get(ctx.idleStateHandlerTrigger));
            Assertions.assertNotNull(writeFutureF.get(ctx.idleStateHandlerTrigger));

            ctx.idleStateHandlerTrigger.release();
            if (realWorker.scheduledExecutorService != null) {
                realWorker.scheduledExecutorService.shutdownNow();
            }
        } finally {
            engine.shutdown();
        }
    }

    /**
     * Cover the 2-arg schedule(Runnable, long delayMs) delegate (ChannelContext L741-743),
     * which forwards to schedule(runnable, delayMs, TimeUnit.MILLISECONDS).
     */
    @Test
    public void testScheduleMillisDelegate() throws Exception {
        NioEngine<?> engine = new NioEngine<>(1);
        try {
            ChannelWorker realWorker = new ChannelWorker(engine);
            ctx.setWorker(realWorker);

            final boolean[] ran = {false};
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            ScheduledFuture<?> future = ctx.schedule(new Runnable() {
                public void run() {
                    ran[0] = true;
                    latch.countDown();
                }
            }, 10);
            Assertions.assertNotNull(future);
            // wait for the delayed task to actually execute via the worker's scheduled executor
            Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "scheduled task should run");
            Assertions.assertTrue(ran[0]);
            Assertions.assertFalse(future.isDone() == false && future.isCancelled());

            if (realWorker.scheduledExecutorService != null) {
                realWorker.scheduledExecutorService.shutdownNow();
            }
        } finally {
            engine.shutdown();
        }
    }

    private void invokeScheduleTaskRun(String innerClassName) throws Exception {
        Class<?> taskClass = Class.forName(
                "io.github.wycst.wastnet.socket.handler.IdleStateHandlerTrigger$" + innerClassName);
        java.lang.reflect.Constructor<?> ctor =
                taskClass.getDeclaredConstructor(IdleStateHandlerTrigger.class);
        ctor.setAccessible(true);
        Object task = ctor.newInstance(ctx.idleStateHandlerTrigger);
        java.lang.reflect.Method run = taskClass.getDeclaredMethod("run");
        run.setAccessible(true);
        run.invoke(task);
    }


    // ==================== close with idle trigger ====================

    @Test
    public void testCloseWithIdleTrigger() {
        IdleStateHandler handler = mock(IdleStateHandler.class);
        ctx.setIdleTrigger(handler);
        ctx.close();
    }

    // ==================== close with multiple listeners ====================

    @Test
    public void testCloseWithMultipleListeners() {
        AtomicBoolean called1 = new AtomicBoolean(false);
        AtomicBoolean called2 = new AtomicBoolean(false);
        ctx.addCloseListener(() -> called1.set(true));
        ctx.addCloseListener(() -> called2.set(true));
        ctx.close();
        Assertions.assertTrue(called1.get());
        Assertions.assertTrue(called2.get());
    }

    // ==================== readFully with len=0 ====================

    @Test
    public void testReadFullyZeroLength() throws IOException {
        byte[] buf = new byte[0];
        int n = ctx.readFully(buf, 0, 0, 1000);
        Assertions.assertEquals(0, n);
    }

    // ==================== awaitReadableWithTimeout ====================

    @Test
    public void testAwaitReadableWithNegativeTimeout() throws IOException {
        ctx.awaitReadableWithTimeout(System.currentTimeMillis(), -1);
    }

    // ==================== getLocalAddress first call ====================

    @Test
    public void testGetLocalAddressFirstCall() throws IOException {
        // 真实通道有真实地址，不需要 stub
        SocketAddress addr = ctx.getLocalAddress();
        Assertions.assertNotNull(addr);
    }

    // ==================== initializeAddressCache exception ====================

    @Test
    public void testInitializeAddressCacheException() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        try {
            java.lang.reflect.Field f = java.nio.channels.spi.AbstractInterruptibleChannel.class.getDeclaredField("open");
            f.setAccessible(true);
            f.setBoolean(mockCh, true);
        } catch (Exception ignored) {}
        when(mockCh.getRemoteAddress()).thenThrow(new IOException("simulated"));
        ChannelContext excCtx = new ChannelContext(2L, mockCh, 0);
        SocketAddress addr = excCtx.getRemoteAddress();
        Assertions.assertNull(addr);
    }

    // ==================== hasApplicationProtocol ====================

    @Test
    public void testHasApplicationProtocolNoNioConfig() {
        Assertions.assertFalse(ctx.hasApplicationProtocol("h2"));
    }

    // ==================== channelWrite IOException from channel ====================

    @Test
    public void testChannelWriteThrowsIOException() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        try {
            java.lang.reflect.Field f = java.nio.channels.spi.AbstractInterruptibleChannel.class.getDeclaredField("open");
            f.setAccessible(true);
            f.setBoolean(mockCh, true);
        } catch (Exception ignored) {}
        when(mockCh.write(any(ByteBuffer.class))).thenThrow(new IOException("simulated"));
        ChannelContext excCtx = new ChannelContext(2L, mockCh, 0);
        ByteBuffer data = ByteBuffer.wrap("test".getBytes());
        int n = excCtx.write(data);
        Assertions.assertEquals(-1, n);
    }

    // ==================== wakeupWrite ====================

    @Test
    public void testWakeupWrite() {
        ctx.wakeupWrite();
    }

    // ==================== waitForWrite thread-pool path (doRegisterWrite / doAwaitWritable / doRemoveWriteInterest) ====================

    /** Build a real (non-worker) ChannelWorker so worker.wakeup() can be invoked without mocking the final class. */
    private ChannelWorker newRealWorker() throws Exception {
        TCPServer server = new TCPServer(1, new NioConfig());
        return new ChannelWorker(server);
    }

    /** Thread-pool path: worker != null and current thread is NOT a worker thread.
     *  Wakeup from another thread so waitForWrite returns true normally. */
    @Test
    public void testWaitForWriteThreadPoolPath() throws Exception {
        ChannelWorker worker = newRealWorker();
        SelectionKey key = mock(SelectionKey.class);
        when(key.isValid()).thenReturn(true);
        when(key.interestOps()).thenReturn(0);
        when(key.interestOps(anyInt())).thenReturn(key);

        ChannelContext poolCtx = new ChannelContext(11L, realChannel, 1024);
        poolCtx.setWorker(worker);
        poolCtx.setReadKey(key);

        AtomicBoolean ok = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            try {
                ok.set(poolCtx.waitForWrite(500));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        // Give the writer thread time to enter wait()
        Thread.sleep(100);
        // Wake it up from the main thread (calls doRemoveWriteInterest + notifyAll)
        poolCtx.wakeupWrite();
        writer.join(3000);
        Assertions.assertTrue(ok.get(), "waitForWrite should return true after wakeup");
    }

    /** InterruptedException branch (L193-196): pass timeoutMs <= 0 so wait(30000), then interrupt. */
    @Test
    public void testWaitForWriteInterruptedException() throws Exception {
        ChannelWorker worker = newRealWorker();
        SelectionKey key = mock(SelectionKey.class);
        when(key.isValid()).thenReturn(true);
        when(key.interestOps()).thenReturn(0);
        when(key.interestOps(anyInt())).thenReturn(key);

        ChannelContext poolCtx = new ChannelContext(12L, realChannel, 1024);
        poolCtx.setWorker(worker);
        poolCtx.setReadKey(key);

        AtomicBoolean result = new AtomicBoolean(true);
        Thread writer = new Thread(() -> {
            try {
                // timeoutMs <= 0 -> wait(30000); interrupt triggers InterruptedException branch
                result.set(poolCtx.waitForWrite(-1));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        Thread.sleep(100);
        writer.interrupt();   // wake from wait() via InterruptedException
        writer.join(3000);
        Assertions.assertFalse(result.get(), "waitForWrite should return false on interrupt");
    }

    /** doRemoveWriteInterest false branch (L184): readKey != null but invalid -> interestOps not called. */
    @Test
    public void testDoRemoveWriteInterestWhenKeyInvalid() throws Exception {
        ChannelWorker worker = newRealWorker();
        SelectionKey key = mock(SelectionKey.class);
        when(key.isValid()).thenReturn(false);

        ChannelContext poolCtx = new ChannelContext(13L, realChannel, 1024);
        poolCtx.setWorker(worker);
        poolCtx.setReadKey(key);

        // wakeupWrite() -> doRemoveWriteInterest(): readKey!=null && !valid -> skip interestOps
        poolCtx.wakeupWrite();
        verify(key, never()).interestOps(anyInt());
    }

    /** doRegisterWrite false branch (L177): worker != null but readKey == null -> interestOps skipped. */
    @Test
    public void testWaitForWriteThreadPoolPathNoReadKey() throws Exception {
        ChannelWorker worker = newRealWorker();   // no readKey set -> readKey == null

        ChannelContext poolCtx = new ChannelContext(14L, realChannel, 1024);
        poolCtx.setWorker(worker);

        AtomicBoolean ok = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            try {
                ok.set(poolCtx.waitForWrite(500));   // doRegisterWrite() takes the readKey==null false branch
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();
        Thread.sleep(100);
        poolCtx.wakeupWrite();
        writer.join(3000);
        Assertions.assertTrue(ok.get());
    }

    // ==================== write bypass (remaining >= capacity) ====================

    @Test
    public void testWriteLargeDataBypassesBuffer() throws IOException {
        // buf.remaining() >= byteBuffer.capacity() → flush + direct channelWrite
        byte[] largeData = new byte[2000];
        int n = ctx.write(largeData, 0, largeData.length);
        Assertions.assertTrue(n > 0);
    }

    // ==================== write with non-array-backed buffer ====================

    @Test
    public void testCopyBufferDataNonArrayBacked() throws IOException {
        // Use ByteBuffer.allocateDirect() which has no backing array
        // → goes through !hasArray() path in copyBufferData
        ByteBuffer direct = ByteBuffer.allocateDirect(50);
        direct.put("small direct data".getBytes());
        direct.flip();
        int n = ctx.write(direct);
        Assertions.assertTrue(n > 0);
    }

    // ==================== flush when byteBuffer != null ====================

    @Test
    public void testFlushWithBuffer() throws IOException {
        ctx.write("data".getBytes());
        ctx.flush();
    }

    // ==================== channelWrite with idle trigger ====================

    @Test
    public void testChannelWriteWithIdleTrigger() throws IOException {
        IdleStateHandler handler = mock(IdleStateHandler.class);
        ctx.setIdleTrigger(handler);
        ctx.write("test".getBytes());
        // onWriteTriggered is called on IdleStateHandlerTrigger (internal), verify no exception
    }

    // ==================== close with null readKey ====================

    @Test
    public void testCloseWithNullReadKey() {
        // readKey is null → readKey.cancel() should not NPE
        ChannelContext noKeyCtx;
        try {
            noKeyCtx = new ChannelContext(99L, realChannel, 0);
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
        noKeyCtx.close();
        // Should not throw NPE
    }

    // ==================== close with idle trigger release ====================

    @Test
    public void testCloseReleasesIdleTrigger() {
        IdleStateHandler handler = mock(IdleStateHandler.class);
        ctx.setIdleTrigger(handler);
        ctx.close();
        // idleStateHandlerTrigger released
    }

    // ==================== getAttributes lazy init ====================

    @Test
    public void testGetAttributesLazyInit() {
        Object attrs = ctx.getAttributes();
        Assertions.assertNotNull(attrs);
        // Second call returns same instance
        Assertions.assertSame(attrs, ctx.getAttributes());
    }

    // ==================== contextId / getId / isSSL ====================

    @Test
    public void testGetId() {
        Assertions.assertEquals(1L, ctx.getId());
    }

    @Test
    public void testContextId() {
        Assertions.assertNotNull(ctx.contextId());
    }

    @Test
    public void testIsSSL() {
        Assertions.assertFalse(ctx.isSSL());
    }

    @Test
    public void testGetHandShakedApplicationProtocol() {
        Assertions.assertNull(ctx.getHandShakedApplicationProtocol());
    }

    // ==================== setReadKey / reader ====================

    @Test
    public void testSetReadKeyAndReader() {
        ctx.setReadKey(null);
        Assertions.assertNull(ctx.reader());
        ctx.setReadKey(mock(java.nio.channels.SelectionKey.class));
    }

    // ==================== channelRead triggers idle on success ====================

    @Test
    public void testChannelReadTriggersIdle() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        try {
            java.lang.reflect.Field f = java.nio.channels.spi.AbstractInterruptibleChannel.class.getDeclaredField("open");
            f.setAccessible(true);
            f.setBoolean(mockCh, true);
        } catch (Exception ignored) {}
        when(mockCh.read(any(ByteBuffer.class)))
                .thenReturn(5)
                .thenReturn(-1);
        IdleStateHandler handler = mock(IdleStateHandler.class);
        ChannelContext readCtx = new ChannelContext(5L, mockCh, 1024);
        readCtx.setIdleTrigger(handler);
        ByteBuffer buf = ByteBuffer.allocate(100);
        int n = readCtx.read(buf);
        Assertions.assertEquals(5, n);
    }

    // ==================== invokeHandle ====================

    @Test
    @SuppressWarnings("unchecked")
    public void testInvokeHandleCallsHandler() throws IOException {
        io.github.wycst.wastnet.socket.handler.ChannelHandler handler =
                mock(io.github.wycst.wastnet.socket.handler.ChannelHandler.class);
        ctx.setChannelHandler(handler);
        ctx.invokeHandle("test");
        verify(handler).onHandle(ctx, "test");
    }

    // ==================== write with writer null buffer ====================

    @Test
    @SuppressWarnings("unchecked")
    public void testSendWithoutWriterThrows() {
        Assertions.assertThrows(IOException.class, () -> ctx.send("test"));
    }

    // ==================== fireClearListener catch Throwable ====================

    @Test
    public void testFireClearListenerCatchesListenerThrowable() {
        ctx.addClearListener(() -> { throw new RuntimeException("listener fail"); });
        ctx.fireClearListener();
        // Should not propagate the exception
    }

    // ==================== write(ByteBuffer) small data requires flush-and-copy ====================

    @Test
    public void testWriteSmallDataFlushAndCopy() throws IOException {
        // Position byteBuffer by writing 100 bytes first
        ctx.write(new byte[100]);
        // Write 1000 bytes → byteBuffer has 924 remaining, buf has 1000
        // 1000 < 1024 (capacity) → no bypass. toTransfer = min(1000, 924) = 924.
        // Then buf.hasRemaining()=true → flush + copy remaining 76
        int n = ctx.write(new byte[1000]);
        Assertions.assertTrue(n > 0);
    }

    // ==================== channelWrite negative return ====================

    @Test
    public void testChannelWriteReturnsNegative() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        try {
            java.lang.reflect.Field f = java.nio.channels.spi.AbstractInterruptibleChannel.class.getDeclaredField("open");
            f.setAccessible(true);
            f.setBoolean(mockCh, true);
        } catch (Exception ignored) {}
        when(mockCh.write(any(ByteBuffer.class))).thenReturn(-1);
        ChannelContext excCtx = new ChannelContext(7L, mockCh, 0);
        ByteBuffer data = ByteBuffer.wrap("data".getBytes());
        int n = excCtx.write(data);
        Assertions.assertEquals(-1, n);
    }

    // ==================== hasApplicationProtocol with null protocol entry ====================

    @Test
    public void testHasApplicationProtocolWithNullEntry() {
        NioConfig nioConfig = new NioConfig();
        nioConfig.setApplicationProtocols(new String[]{"h2", null, "http/1.1"});
        ctx.setNioConfig(nioConfig);
        Assertions.assertFalse(ctx.hasApplicationProtocol("h3"));
        Assertions.assertTrue(ctx.hasApplicationProtocol("h2"));
    }

    // ==================== initializeAddressCache non-InetSocketAddress remote ====================

    @Test
    public void testInitializeAddressCacheNonInetRemote() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        when(mockCh.getRemoteAddress()).thenReturn(new SocketAddress() {});
        when(mockCh.getLocalAddress()).thenReturn(new java.net.InetSocketAddress("127.0.0.1", 0));
        ChannelContext addrCtx = new ChannelContext(8L, mockCh, 0);
        java.net.InetSocketAddress remote = addrCtx.getRemoteAddress();
        Assertions.assertNull(remote);
    }

    // ==================== getRemoteAddress second call returns cached ====================

    @Test
    public void testGetRemoteAddressCached() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        try {
            java.lang.reflect.Field f = java.nio.channels.spi.AbstractInterruptibleChannel.class.getDeclaredField("open");
            f.setAccessible(true);
            f.setBoolean(mockCh, true);
        } catch (Exception ignored) {}
        when(mockCh.getRemoteAddress()).thenReturn(new java.net.InetSocketAddress("10.0.0.1", 1234));
        ChannelContext addrCtx = new ChannelContext(6L, mockCh, 0);
        java.net.InetSocketAddress first = addrCtx.getRemoteAddress();
        Assertions.assertNotNull(first);
        java.net.InetSocketAddress second = addrCtx.getRemoteAddress();
        Assertions.assertSame(first, second);
    }

    // ==================== hasApplicationProtocol with config ====================

    @Test
    public void testHasApplicationProtocolWithConfig() {
        NioConfig nioConfig = new NioConfig();
        nioConfig.setApplicationProtocols(new String[]{"h2", "http/1.1"});
        ctx.setNioConfig(nioConfig);
        Assertions.assertTrue(ctx.hasApplicationProtocol("h2"));
        Assertions.assertFalse(ctx.hasApplicationProtocol("unknown"));
    }

    // ==================== channelWrite empty buffer ====================

    @Test
    public void testChannelWriteEmptyBufferReturnsZero() throws IOException {
        ByteBuffer empty = ByteBuffer.allocate(0);
        // channelWrite → len == 0 → return 0
        ChannelContext noBufCtx = new ChannelContext(3L, realChannel, 0);
        int n = noBufCtx.write(empty);
        Assertions.assertEquals(0, n);
    }

    // ==================== close second call is no-op ====================

    @Test
    public void testCloseSecondCallIsNoOp() {
        ctx.close();
        ctx.close(); // should not throw
    }

    // ==================== readFully(byte[]) delegate ====================

    @Test
    public void testReadFullyDelegateWithEmptyArray() throws IOException {
        byte[] empty = new byte[0];
        int n = ctx.readFully(empty);
        Assertions.assertEquals(0, n);
    }

    // ==================== wakeup / awaitReadableInternal (readReady flag) ====================

    @Test
    public void testWakeupWithWaitingFlag() throws Exception {
        // wakeup() sets readReady=true under the lock
        ctx.wakeup();
        java.lang.reflect.Field rf = ChannelContext.class.getDeclaredField("readReady");
        rf.setAccessible(true);
        Assertions.assertTrue((Boolean) rf.get(ctx));

        // awaitReadableInternal consumes readReady on the fast path and returns without waiting
        ctx.awaitReadableInternal(1000);
        Assertions.assertFalse((Boolean) rf.get(ctx));
    }

    // ==================== writeFlush(byte[]) ====================

    @Test
    public void testWriteFlushByteArray() throws IOException {
        ctx.writeFlush("test".getBytes());
    }

    // ==================== channelRead returns -1 via readFully ====================

    @Test
    public void testReadFullyChannelClosedReturnsMinusOne() throws IOException {
        SocketChannel mockCh = mock(SocketChannel.class);
        when(mockCh.read(any(ByteBuffer.class))).thenReturn(-1);
        ChannelContext readCtx = new ChannelContext(4L, mockCh, 0);
        byte[] buf = new byte[10];
        int n = readCtx.readFully(buf, 0, buf.length, 1000);
        Assertions.assertEquals(-1, n);
    }

    // ==================== attribute map (set/get/remove/names) ====================

    @Test
    public void testAttributesSetGetRemoveNames() {
        // setAttribute + getAttribute
        ctx.setAttribute("foo", "bar");
        Assertions.assertEquals("bar", ctx.getAttribute("foo"));
        Assertions.assertNull(ctx.getAttribute("missing"));

        // getAttributeNames contains the set key
        java.util.Enumeration<String> names = ctx.getAttributeNames();
        boolean found = false;
        while (names.hasMoreElements()) {
            if ("foo".equals(names.nextElement())) {
                found = true;
                break;
            }
        }
        Assertions.assertTrue(found, "getAttributeNames should include the key 'foo'");

        // removeAttribute then name should be gone and value null
        ctx.removeAttribute("foo");
        Assertions.assertNull(ctx.getAttribute("foo"));
        java.util.Enumeration<String> namesAfter = ctx.getAttributeNames();
        boolean stillThere = false;
        while (namesAfter.hasMoreElements()) {
            if ("foo".equals(namesAfter.nextElement())) {
                stillThere = true;
                break;
            }
        }
        Assertions.assertFalse(stillThere, "getAttributeNames should not include removed key 'foo'");
    }

    /** close() must enter the 'attributes != null' branch (L233) and clear the lazy map. */
    @Test
    public void testCloseClearsAttributes() {
        // setAttribute makes the lazy 'attributes' map non-null so close() reaches attributes.clear()
        ctx.setAttribute("k", "v");
        Assertions.assertEquals("v", ctx.getAttribute("k"));
        ctx.close();
        // after close, attributes map has been cleared (and reset to null)
        Assertions.assertNull(ctx.getAttribute("k"));
    }
}
