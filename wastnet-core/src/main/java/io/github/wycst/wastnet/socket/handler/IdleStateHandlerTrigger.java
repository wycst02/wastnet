package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Triggers idle state detection for connections.
 *
 * @since 2024-1-25
 * @author wangyc
 */
public final class IdleStateHandlerTrigger {

    final IdleStateHandler idleStateHandler;
    final ChannelContext ctx;
    private final long readerIdleTimeNanos;
    private final long writerIdleTimeNanos;
    final boolean sharedMode;

    long idleReadTriggerCnt;
    long idleWriteTriggerCnt;
    final AtomicLong lastReadNanos = new AtomicLong();
    final AtomicLong idleReadTriggerConsecutiveCnt = new AtomicLong();
    final AtomicLong lastWriteNanos = new AtomicLong();
    final AtomicLong idleWriteTriggerConsecutiveCnt = new AtomicLong();

    ScheduledFuture<?> readIdleFuture;
    ScheduledFuture<?> writeIdleFuture;
    /** Flag to prevent rescheduling idle tasks after the connection is released */
    volatile boolean released;
    static final long MIN_NANOS = 1000000000L;

    Runnable scheduleWriteTask;
    Runnable scheduleReadTask;

    public IdleStateHandlerTrigger(IdleStateHandler idleStateHandler, ChannelContext ctx) {
        this.idleStateHandler = idleStateHandler;
        this.ctx = ctx;
        this.readerIdleTimeNanos = idleStateHandler.getReaderIdleTimeNanos();
        this.writerIdleTimeNanos = idleStateHandler.getWriterIdleTimeNanos();
        this.sharedMode = idleStateHandler.getMode() == IdleStateHandler.Mode.SHARED;
        if (!sharedMode) {
            // EXCLUSIVE mode: per-connection recursive scheduling (precise)
            this.scheduleReadTask = () -> {
                // Skip if the connection has been released to avoid ghost tasks
                if (released) return;
                long useNanos = System.nanoTime() - lastReadNanos.get();
                long rem = readerIdleTimeNanos - useNanos;
                readIdleFuture.cancel(false);
                if (rem > MIN_NANOS) {
                    scheduleReadTask(rem);
                    return;
                }
                try {
                    increaseTriggerReadCount();
                    idleStateHandler.onIdleTriggered(ctx, IdleStateHandler.IdleType.Read, idleReadTriggerCnt, idleReadTriggerConsecutiveCnt.get());
                } catch (Throwable ignored) {
                } finally {
                    scheduleReadTask(readerIdleTimeNanos);
                }
            };
            this.scheduleWriteTask = () -> {
                // Skip if the connection has been released to avoid ghost tasks
                if (released) return;
                long useNanos = System.nanoTime() - lastWriteNanos.get();
                long rem = writerIdleTimeNanos - useNanos;
                writeIdleFuture.cancel(false);
                if (rem > MIN_NANOS) {
                    scheduleWriteTask(rem);
                    return;
                }
                try {
                    increaseTriggerWriteCount();
                    idleStateHandler.onIdleTriggered(ctx, IdleStateHandler.IdleType.Write, idleWriteTriggerCnt, idleWriteTriggerConsecutiveCnt.get());
                } catch (Throwable ignored) {
                } finally {
                    scheduleWriteTask(writerIdleTimeNanos);
                }
            };
            if (readerIdleTimeNanos >= MIN_NANOS) {
                scheduleReadTask(readerIdleTimeNanos);
                onReadTriggered();
            }
            if (writerIdleTimeNanos >= MIN_NANOS) {
                scheduleWriteTask(writerIdleTimeNanos);
                onWriteTriggered();
            }
        } else {
            // SHARED mode: no individual scheduling, scan loop handles all.
            // Initialize timestamps so idle is measured from connection creation
            // (robust to System.nanoTime() sign on all platforms).
            long now = System.nanoTime();
            lastReadNanos.set(now);
            lastWriteNanos.set(now);
        }
    }

    public void release() {
        // Mark as released first to stop any in-flight task from rescheduling
        released = true;
        if (readIdleFuture != null) {
            readIdleFuture.cancel(false);
        }
        if (writeIdleFuture != null) {
            writeIdleFuture.cancel(false);
        }
    }

    /**
     * Called by the worker's scan loop (SHARED mode only).
     */
    public void scanIdle(long now) {
        if (released || !sharedMode) return;
        if (readerIdleTimeNanos > 0 && now - lastReadNanos.get() > readerIdleTimeNanos) {
            increaseTriggerReadCount();
            try {
                idleStateHandler.onIdleTriggered(ctx, IdleStateHandler.IdleType.Read, idleReadTriggerCnt, idleReadTriggerConsecutiveCnt.get());
            } catch (Throwable ignored) {
            }
        }
        if (writerIdleTimeNanos > 0 && now - lastWriteNanos.get() > writerIdleTimeNanos) {
            increaseTriggerWriteCount();
            try {
                idleStateHandler.onIdleTriggered(ctx, IdleStateHandler.IdleType.Write, idleWriteTriggerCnt, idleWriteTriggerConsecutiveCnt.get());
            } catch (Throwable ignored) {
            }
        }
    }


    void scheduleWriteTask(long timeNanos) {
        // Guard against reschedule after the connection is released
        if (released) return;
        writeIdleFuture = ctx.schedule(scheduleWriteTask, timeNanos, TimeUnit.NANOSECONDS);
    }

    void increaseTriggerWriteCount() {
        ++idleWriteTriggerCnt;
        long val = idleWriteTriggerConsecutiveCnt.incrementAndGet();
        if (idleWriteTriggerCnt <= 0) {
            idleWriteTriggerCnt = 1;
        }
        if (val <= 0) {
            idleWriteTriggerConsecutiveCnt.set(1);
        }
    }

    void scheduleReadTask(long timeNanos) {
        // Guard against reschedule after the connection is released
        if (released) return;
        readIdleFuture = ctx.schedule(scheduleReadTask, timeNanos, TimeUnit.NANOSECONDS);
    }

    void increaseTriggerReadCount() {
        ++idleReadTriggerCnt;
        long val = idleReadTriggerConsecutiveCnt.incrementAndGet();
        if (idleReadTriggerCnt <= 0) {
            idleReadTriggerCnt = 1;
        }
        if (val <= 0) {
            idleReadTriggerConsecutiveCnt.set(1);
        }
    }

    public void onReadTriggered() {
        lastReadNanos.set(System.nanoTime());
        this.idleReadTriggerConsecutiveCnt.set(0);
    }

    public void onWriteTriggered() {
        lastWriteNanos.set(System.nanoTime());
        this.idleWriteTriggerConsecutiveCnt.set(0);
    }
}
