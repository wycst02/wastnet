package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.SocketOptions;
import io.github.wycst.wastnet.socket.handler.IdleStateHandler;
import io.github.wycst.wastnet.util.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IO event processing thread, each Worker maintains an independent Selector.
 */
final class ChannelWorker extends Thread {
    static final Log LOG = LogFactory.getLog(ChannelWorker.class);
    static ThreadLocal<Thread> workerThreadTl = new ThreadLocal<>();

    final String workId;
    final Selector selector;
    final ByteBuffer workerBuffer;
    /**
     * Marks an in-progress cross-thread registration. Set true before selector.wakeup()
     * in register() and cleared in its finally. The worker waits on it right after
     * selector.select() returns; since select() and client.register() serialize on the
     * selector's internal lock, register() cannot run until the worker has entered wait(),
     * so its notify() always follows wait() — no lost wakeup, no deadlock.
     */
    volatile boolean registering;
    final AtomicInteger connectionCount = new AtomicInteger(0);
    /** Per-worker single-thread scheduled executor for idle detection, lazily initialized */
    volatile ScheduledExecutorService scheduledExecutorService;
    final NioEngine<?> engine;

    /** Scan task future for SHARED idle mode */
    private ScheduledFuture<?> idleScanFuture;

    public ChannelWorker(NioEngine<?> engine) throws IOException {
        this.engine = engine;
        this.workId = Utils.hex();
        this.selector = Selector.open();
        this.workerBuffer = ByteBuffer.allocate(engine.nioConfig.getReadBufferSize());
    }

    static boolean isSyncWorkerThread() {
        return workerThreadTl.get() == Thread.currentThread();
    }

    /**
     * Lazily create a single-thread scheduled executor for this worker.
     */
    ScheduledExecutorService getScheduledExecutorService() {
        if (scheduledExecutorService == null) {
            synchronized (this) {
                if (scheduledExecutorService == null) {
                    scheduledExecutorService = Executors.newScheduledThreadPool(1);
                }
            }
        }
        return scheduledExecutorService;
    }

    /**
     * Submit a task to a background executor to avoid blocking the worker thread.
     * Falls back to the shared executor service if runner executor is not configured.
     */
    void runAsync(Runnable runnable) {
        engine.runnerExecutor.execute(runnable);
    }

    public void register(SocketChannel client, ChannelRunner channelRunner) throws ClosedChannelException {
        registering = true; // must be set BEFORE wakeup() so the worker sees it right after select() returns
        selector.wakeup();
        try {
            SelectionKey selectionKey = client.register(selector, SelectionKey.OP_READ, channelRunner);
            channelRunner.setReadKey(selectionKey);
            connectionCount.incrementAndGet();
        } finally {
            registering = false;
            synchronized (this) {
                notify();
            }
        }
    }

    public int getConnectionCount() {
        return connectionCount.get();
    }

    public void decrementConnectionCount() {
        connectionCount.decrementAndGet();
    }

    public void wakeup() {
        selector.wakeup();
    }

    /**
     * Close all connections managed by this worker.
     * Called during server shutdown to release all client resources.
     */
    void closeAllConnections() {
        for (SelectionKey key : new ArrayList<>(selector.keys())) {
            if (key.isValid()) {
                ChannelRunner runner = (ChannelRunner) key.attachment();
                if (runner != null) {
                    try {
                        runner.ctx.fireClearListener();
                        runner.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * Check if any connection managed by this worker is currently processing data.
     *
     * @return true if at least one runner has in-flight (runFlag == true)
     */
    boolean hasInflightRequests() {
        for (SelectionKey key : new ArrayList<>(selector.keys())) {
            if (key.isValid()) {
                ChannelRunner runner = (ChannelRunner) key.attachment();
                if (runner != null && runner.isRunFlag()) {
                    return true;
                }
            }
        }
        return false;
    }

    void handleSelectedKeys(Set<SelectionKey> selectedKeys) throws IOException {
        while (!selectedKeys.isEmpty()) {
            Iterator<SelectionKey> iter = selectedKeys.iterator();
            while (iter.hasNext()) {
                SelectionKey key = iter.next();
                iter.remove();
                ChannelRunner runner = (ChannelRunner) key.attachment();
                try {
                    if (!key.isValid()) {
                        runner.close();
                        continue;
                    }
                    // Handle OP_WRITE — notify blocked writer
                    if (key.isWritable()) {
                        runner.ctx.wakeupWrite();
                        continue;
                    }
                    if (!runner.isRunFlag()) {
                        runner.runFlag = true;
                        // Dynamic decision: sync for fast connections, async for slow connections
                        if (runner.predictSync() || engine.nioConfig.isSyncRunner()) {
                            runner.run0();
                        } else {
                            engine.runnerExecutor.execute(runner);
                        }
                    } else {
                        runner.wakeup();
                    }
                } catch (Throwable throwable) {
                    if (!key.isValid()) {
                        LOG.debug("channel key is invalid(cancel)");
                    }
                    if (engine.nioConfig.isPrintReadErrorLog()) {
                        LOG.error("read error", throwable);
                    }
                    runner.close();
                }
            }
        }
    }

    void handleRead() throws Throwable {
        // Signal that worker thread has entered its event loop
        CountDownLatch latch = engine.startLatch;
        if (latch != null) latch.countDown();

        // Start the shared idle scan loop if configured in SHARED mode
        if (engine.nioConfig.getIdleStateHandler() != null
                && engine.nioConfig.getIdleStateHandler().getMode() == IdleStateHandler.Mode.SHARED) {
            idleScanFuture = getScheduledExecutorService()
                    .scheduleWithFixedDelay(new IdleScanTask(), 0, 1000, TimeUnit.MILLISECONDS);
        }

        final long selectTimeoutMs = engine.nioConfig.option(SocketOptions.SELECT_TIMEOUT_MS);
        final int selectEmptyCount = engine.nioConfig.option(SocketOptions.SELECT_EMPTY_COUNT);
        int selectZeroCount = 0;
        final Set<SelectionKey> selectedKeys = selector.selectedKeys();
        while (engine.engineRunFlag) {
            int num = selector.select(selectTimeoutMs);
            if (num == 0) {
                if (++selectZeroCount < selectEmptyCount) {
                    continue;
                }
                selectZeroCount = 0;
                num = selector.select();
                if (registering) {
                    // Wait for register()'s finally-block notify(); safe & never missed (see 'registering').
                    synchronized (this) {
                        wait();
                    }
                }
                if (num == 0) {
                    continue;
                }
            }
            handleSelectedKeys(selectedKeys);
        }
        selector.close();
    }

    @Override
    public void run() {
        try {
            workerThreadTl.set(Thread.currentThread());
            handleRead();
        } catch (Throwable ignored) {
        } finally {
            workerThreadTl.remove();
            stopIdleScan();
            if (scheduledExecutorService != null) {
                Utils.shutdownExecutorService(scheduledExecutorService);
            }
        }
    }

    private void stopIdleScan() {
        if (idleScanFuture != null) {
            idleScanFuture.cancel(false);
            idleScanFuture = null;
        }
    }

    /**
     * Runnable that scans all connections for idle state (SHARED mode).
     * Only scans triggers in SHARED mode; EXCLUSIVE triggers use their own scheduling.
     */
    class IdleScanTask implements Runnable {
        @Override
        public void run() {
            try {
                long now = System.nanoTime();
                List<SelectionKey> keys = new ArrayList<>(selector.keys());
                for (SelectionKey key : keys) {
                    if (!key.isValid()) continue;
                    ChannelRunner runner = (ChannelRunner) key.attachment();
                    runner.ctx.idleStateHandlerTrigger.scanIdle(now);
                }
            } catch (Throwable ignored) {
                // next tick retries
            }
        }
    }
}
