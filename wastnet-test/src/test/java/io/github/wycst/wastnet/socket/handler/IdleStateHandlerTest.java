package io.github.wycst.wastnet.socket.handler;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link IdleStateHandler} and {@link IdleStateHandlerTrigger}.
 * <p>
 * {@code ChannelContext.schedule()} and {@code ChannelWorker} are final,
 * so we avoid mocking them. The trigger is constructed with zero idle times
 * (no scheduling) and inner fields are set directly.
 *
 * @author wangyc
 */
public class IdleStateHandlerTest {

    // ==================== IdleStateHandler ====================

    @Test
    public void testConstructorReaderOnly() {
        RecordingHandler handler = new RecordingHandler(5, 0, TimeUnit.SECONDS);
        Assertions.assertTrue(handler.getReaderIdleTimeNanos() > 0);
        Assertions.assertEquals(0L, handler.getWriterIdleTimeNanos());
    }

    @Test
    public void testConstructorWriterOnly() {
        RecordingHandler handler = new RecordingHandler(0, 3, TimeUnit.SECONDS);
        Assertions.assertEquals(0L, handler.getReaderIdleTimeNanos());
        Assertions.assertTrue(handler.getWriterIdleTimeNanos() > 0);
    }

    @Test
    public void testConstructorBothTimes() {
        RecordingHandler handler = new RecordingHandler(10, 20, TimeUnit.SECONDS);
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(10), handler.getReaderIdleTimeNanos());
        Assertions.assertEquals(TimeUnit.SECONDS.toNanos(20), handler.getWriterIdleTimeNanos());
    }

    @Test
    public void testConstructorZeroTimes() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        Assertions.assertEquals(0L, handler.getReaderIdleTimeNanos());
        Assertions.assertEquals(0L, handler.getWriterIdleTimeNanos());
    }

    @Test
    public void testConstructorNegativeTreatedAsZero() {
        RecordingHandler handler = new RecordingHandler(-1, -5, TimeUnit.SECONDS);
        Assertions.assertEquals(0L, handler.getReaderIdleTimeNanos());
        Assertions.assertEquals(0L, handler.getWriterIdleTimeNanos());
    }

    @Test
    public void testConstructorWithMillisUnit() {
        RecordingHandler handler = new RecordingHandler(500, 1000, TimeUnit.MILLISECONDS);
        Assertions.assertEquals(TimeUnit.MILLISECONDS.toNanos(500), handler.getReaderIdleTimeNanos());
        Assertions.assertEquals(TimeUnit.MILLISECONDS.toNanos(1000), handler.getWriterIdleTimeNanos());
    }

    @Test
    public void testOnIdleTriggeredIsCalled() throws Throwable {
        RecordingHandler handler = new RecordingHandler(1, 0, TimeUnit.SECONDS);
        handler.onIdleTriggered(mock(ChannelContext.class), IdleStateHandler.IdleType.Read, 1L, 1L);
        Assertions.assertEquals(1, handler.events.size());
        Assertions.assertEquals("Read:1:1", handler.events.get(0));
    }

    // ==================== IdleStateHandlerTrigger constructor (zero times) ====================

    @Test
    public void testTriggerConstructorZeroTimesNoTasks() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Assertions.assertNotNull(trigger.scheduleReadTask);
        Assertions.assertNotNull(trigger.scheduleWriteTask);
        Assertions.assertNull(trigger.readIdleFuture);
        Assertions.assertNull(trigger.writeIdleFuture);
    }

    // ==================== IdleStateHandlerTrigger release ====================

    @Test
    public void testReleaseSetsReleasedFlag() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Assertions.assertFalse(trigger.released);
        trigger.release();
        Assertions.assertTrue(trigger.released);
    }

    @Test
    public void testReleaseCancelsReadFuture() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
        trigger.readIdleFuture = mockFuture;
        trigger.release();

        verify(mockFuture).cancel(false);
    }

    @Test
    public void testReleaseCancelsWriteFuture() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
        trigger.writeIdleFuture = mockFuture;
        trigger.release();

        verify(mockFuture).cancel(false);
    }

    @Test
    public void testReleaseCancelsBothFutures() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        ScheduledFuture<?> readFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> writeFuture = mock(ScheduledFuture.class);
        trigger.readIdleFuture = readFuture;
        trigger.writeIdleFuture = writeFuture;
        trigger.release();

        verify(readFuture).cancel(false);
        verify(writeFuture).cancel(false);
    }

    @Test
    public void testReleaseWithNullFuturesDoesNotThrow() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Assertions.assertDoesNotThrow(() -> trigger.release());
    }

    // ==================== IdleStateHandlerTrigger onRead/onWrite ====================

    @Test
    public void testOnReadTriggeredResetsConsecutiveCounter() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        trigger.idleReadTriggerConsecutiveCnt.set(5);
        trigger.onReadTriggered();

        Assertions.assertEquals(0, trigger.idleReadTriggerConsecutiveCnt.get());
        Assertions.assertTrue(trigger.lastReadNanos.get() > 0);
    }

    @Test
    public void testOnWriteTriggeredResetsConsecutiveCounter() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        trigger.idleWriteTriggerConsecutiveCnt.set(7);
        trigger.onWriteTriggered();

        Assertions.assertEquals(0, trigger.idleWriteTriggerConsecutiveCnt.get());
        Assertions.assertTrue(trigger.lastWriteNanos.get() > 0);
    }

    // ==================== Task guards (released flag) ====================

    @Test
    public void testReadTaskRunWhenReleasedReturnsEarly() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Runnable readTask = trigger.scheduleReadTask;
        trigger.scheduleReadTask = readTask;
        trigger.release();

        Assertions.assertDoesNotThrow(() -> readTask.run());
    }

    @Test
    public void testWriteTaskRunWhenReleasedReturnsEarly() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Runnable writeTask = trigger.scheduleWriteTask;
        trigger.scheduleWriteTask = writeTask;
        trigger.release();

        Assertions.assertDoesNotThrow(() -> writeTask.run());
    }

    // ==================== Task idle trigger paths (via reflection on timing fields) ====================

    @Test
    public void testReadTaskTriggersIdle() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Runnable readTask = trigger.scheduleReadTask;
        trigger.scheduleReadTask = readTask;
        trigger.readIdleFuture = mock(ScheduledFuture.class);
        // Set lastReadNanos far in the past so rem=readerIdleTimeNanos - elapsed <= 0
        trigger.lastReadNanos.set(System.nanoTime() - 10_000_000_000L);
        setFinalField(trigger, "readerIdleTimeNanos", 5_000_000_000L);
        try {
            readTask.run();
        } catch (Exception ignored) {
            // NPE from finally's ctx.schedule() is expected
        }
        // increaseTriggerReadCount should have been called (idleReadTriggerCnt >= 1)
        Assertions.assertTrue(trigger.idleReadTriggerCnt > 0);
    }

    @Test
    public void testWriteTaskTriggersIdle() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Runnable writeTask = trigger.scheduleWriteTask;
        trigger.scheduleWriteTask = writeTask;
        trigger.writeIdleFuture = mock(ScheduledFuture.class);
        trigger.lastWriteNanos.set(System.nanoTime() - 10_000_000_000L);
        setFinalField(trigger, "writerIdleTimeNanos", 5_000_000_000L);
        try {
            writeTask.run();
        } catch (Exception ignored) {
        }
        Assertions.assertTrue(trigger.idleWriteTriggerCnt > 0);
    }

    // ==================== Task reschedule paths (rem > MIN_NANOS) ====================

    @Test
    public void testReadTaskReschedulesWhenNotIdle() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Runnable readTask = trigger.scheduleReadTask;
        trigger.scheduleReadTask = readTask;
        trigger.readIdleFuture = mock(ScheduledFuture.class);
        // lastReadNanos set to roughly now → rem ≈ 5s > MIN_NANOS → reschedule
        trigger.lastReadNanos.set(System.nanoTime());
        setFinalField(trigger, "readerIdleTimeNanos", 5_000_000_000L);
        try {
            readTask.run();
        } catch (Exception ignored) {
            // NPE from ctx.schedule() inside scheduleReadTask() is expected
        }
        // Idle callback should NOT have been called (not idle yet)
        Assertions.assertEquals(0, handler.events.size());
        // Counter should NOT have been incremented
        Assertions.assertEquals(0, trigger.idleReadTriggerCnt);
    }

    @Test
    public void testWriteTaskReschedulesWhenNotIdle() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Runnable writeTask = trigger.scheduleWriteTask;
        trigger.scheduleWriteTask = writeTask;
        trigger.writeIdleFuture = mock(ScheduledFuture.class);
        trigger.lastWriteNanos.set(System.nanoTime());
        setFinalField(trigger, "writerIdleTimeNanos", 5_000_000_000L);
        try {
            writeTask.run();
        } catch (Exception ignored) {
        }
        Assertions.assertEquals(0, handler.events.size());
        Assertions.assertEquals(0, trigger.idleWriteTriggerCnt);
    }

    // ==================== Counter overflow branches ====================

    @Test
    public void testIncreaseTriggerWriteCountOverflow() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));
        trigger.idleWriteTriggerCnt = Long.MAX_VALUE;
        trigger.idleWriteTriggerConsecutiveCnt.set(Long.MAX_VALUE);
        trigger.increaseTriggerWriteCount();
        Assertions.assertEquals(1, trigger.idleWriteTriggerCnt);
        Assertions.assertEquals(1, trigger.idleWriteTriggerConsecutiveCnt.get());
    }

    @Test
    public void testIncreaseTriggerReadCountOverflow() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));
        trigger.idleReadTriggerCnt = Long.MAX_VALUE;
        trigger.idleReadTriggerConsecutiveCnt.set(Long.MAX_VALUE);
        trigger.increaseTriggerReadCount();
        Assertions.assertEquals(1, trigger.idleReadTriggerCnt);
        Assertions.assertEquals(1, trigger.idleReadTriggerConsecutiveCnt.get());
    }

    // ==================== IdleStateHandler SHARED mode ====================

    @Test
    public void testSharedModeConstructorNoScheduling() {
        IdleStateHandler handler = new RecordingHandler(30, 60, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Assertions.assertTrue(trigger.sharedMode);
        Assertions.assertNull(trigger.scheduleReadTask);
        Assertions.assertNull(trigger.scheduleWriteTask);
        Assertions.assertNull(trigger.readIdleFuture);
        Assertions.assertNull(trigger.writeIdleFuture);
    }

    @Test
    public void testSharedModeOnReadUpdatesTimestamp() {
        IdleStateHandler handler = new RecordingHandler(30, 0, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        trigger.onReadTriggered();
        Assertions.assertTrue(trigger.lastReadNanos.get() > 0);
        Assertions.assertEquals(0, trigger.idleReadTriggerConsecutiveCnt.get());
    }

    @Test
    public void testSharedModeOnWriteUpdatesTimestamp() {
        IdleStateHandler handler = new RecordingHandler(0, 30, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        trigger.onWriteTriggered();
        Assertions.assertTrue(trigger.lastWriteNanos.get() > 0);
        Assertions.assertEquals(0, trigger.idleWriteTriggerConsecutiveCnt.get());
    }

    // ==================== scanIdle ====================

    @Test
    public void testScanIdleTriggersReadIdle() {
        RecordingHandler handler = new RecordingHandler(5, 0, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        // SHARED trigger initializes lastReadNanos at construction; simulate 6s of idle
        // since last read so that now - lastReadNanos > 5s
        trigger.scanIdle(System.nanoTime() + 6_000_000_000L);

        Assertions.assertTrue(trigger.idleReadTriggerCnt > 0);
        Assertions.assertEquals(1, handler.events.size());
        Assertions.assertEquals("Read:1:1", handler.events.get(0));
    }

    @Test
    public void testScanIdleTriggersWriteIdle() {
        RecordingHandler handler = new RecordingHandler(0, 5, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        // SHARED trigger initializes lastWriteNanos at construction; simulate 6s of idle
        // since last write so that now - lastWriteNanos > 5s
        trigger.scanIdle(System.nanoTime() + 6_000_000_000L);

        Assertions.assertTrue(trigger.idleWriteTriggerCnt > 0);
        Assertions.assertEquals(1, handler.events.size());
        Assertions.assertEquals("Write:1:1", handler.events.get(0));
    }

    @Test
    public void testScanIdleNoTriggerWhenReleased() {
        RecordingHandler handler = new RecordingHandler(5, 5, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        trigger.release();
        trigger.scanIdle(System.nanoTime());

        Assertions.assertEquals(0, handler.events.size());
    }

    @Test
    public void testScanIdleNoTriggerWhenExclusiveMode() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        Assertions.assertFalse(trigger.sharedMode);
        // Set timing via reflection to make scanIdle reachable without scheduling
        setFinalField(trigger, "readerIdleTimeNanos", 5_000_000_000L);
        setFinalField(trigger, "writerIdleTimeNanos", 5_000_000_000L);
        trigger.scanIdle(System.nanoTime());

        Assertions.assertEquals(0, handler.events.size());
    }

    @Test
    public void testScanIdleNoTriggerWhenTimeoutZero() {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS, IdleStateHandler.Mode.SHARED);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));

        trigger.scanIdle(System.nanoTime());

        Assertions.assertEquals(0, handler.events.size());
    }

    // ==================== schedule task guards ====================

    @Test
    public void testScheduleWriteTaskWhenReleasedReturnsEarly() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));
        trigger.release();
        trigger.scheduleWriteTask(1_000_000_000L);
    }

    @Test
    public void testScheduleReadTaskWhenReleasedReturnsEarly() throws Throwable {
        RecordingHandler handler = new RecordingHandler(0, 0, TimeUnit.SECONDS);
        IdleStateHandlerTrigger trigger = new IdleStateHandlerTrigger(handler, mock(ChannelContext.class));
        trigger.release();
        trigger.scheduleReadTask(1_000_000_000L);
    }

    // Only used for the two final timing fields (readerIdleTimeNanos / writerIdleTimeNanos)
    // which cannot be assigned directly without removing final. Other fields/methods are
    // accessed directly (package-private), so no reflection is needed for them.
    private static void setFinalField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    static class RecordingHandler extends IdleStateHandler {
        final List<String> events = new ArrayList<String>();

        RecordingHandler(long readerIdleTime, long writerIdleTime, TimeUnit unit) {
            super(readerIdleTime, writerIdleTime, unit);
        }

        RecordingHandler(long readerIdleTime, long writerIdleTime, TimeUnit unit, Mode mode) {
            super(readerIdleTime, writerIdleTime, unit, mode);
        }

        @Override
        public void onIdleTriggered(ChannelContext ctx, IdleType idleType, long triggerTotalCount, long triggerConsecutiveCount) throws Throwable {
            events.add(idleType + ":" + triggerTotalCount + ":" + triggerConsecutiveCount);
        }
    }
}
