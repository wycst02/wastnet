package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HTTP/2 flow-control (backpressure) paths:
 * <ul>
 *   <li>Stream-level send window tracking ({@link Http2Stream#sendWindow})</li>
 *   <li>Connection-level window tracking ({@link Http2ServerReader#connectSendWindow})</li>
 *   <li>Blocking write when window exhausted</li>
 *   <li>Window recovery via {@link Http2Stream#notifyConsumed(int)}</li>
 * </ul>
 */
class Http2StreamFlowControlTest {

    private ChannelContext mockCtx;
    private Http2ServerReader mockReader;
    private Http2Stream streamCtx;

    @BeforeEach
    void setUp() {
        mockCtx = mock(ChannelContext.class);
        when(mockCtx.getWriteBufferSize()).thenReturn(65535);
        mockReader = mock(Http2ServerReader.class);
        H2TestHelper.initMockReader(mockReader);
        mockReader.valid = true; // simulate a live connection (mocks skip the constructor init)
        mockReader.connectSendWindow = Http2ServerReader.CONNECT_RECEIVE_WINDOW_SIZE;
        streamCtx = new Http2ServerStream(mockReader, 1, mockCtx);
    }

    // ================================================================
    //  writeDataFrame — send window tracking
    // ================================================================

    @Test
    void writeDataFrame_reducesBothWindows() throws Exception {
        streamCtx.sendWindow = 65535;
        ByteBuffer frame = streamCtx.createFrameBuffer(100, 50, 0x00, 0x00);
        streamCtx.writeDataFrame(frame, 50);

        assertEquals(65535 - 50, streamCtx.sendWindow);
        assertEquals(Http2ServerReader.CONNECT_RECEIVE_WINDOW_SIZE - 50, mockReader.connectSendWindow);
        verify(mockCtx).write(any(ByteBuffer.class));
    }

    @Test
    void writeDataFrame_zeroPayloadDoesNotChangeWindow() throws Exception {
        streamCtx.sendWindow = 100;
        ByteBuffer frame = streamCtx.createFrameBuffer(20, 0, 0x00, 0x00);
        streamCtx.writeDataFrame(frame, 0);

        assertEquals(100, streamCtx.sendWindow);
        assertEquals(Http2ServerReader.CONNECT_RECEIVE_WINDOW_SIZE, mockReader.connectSendWindow);
    }

    @Test
    void writeDataFrame_blocksWhenStreamWindowExhausted() throws Exception {
        streamCtx.sendWindow = 30;       // insufficient for 50-byte payload
        mockReader.connectSendWindow = 65535; // connection window sufficient

        // Make awaitSendWU actually block so we can test the wait-notify cycle
        Object lock = new Object();
        doAnswer(inv -> {
            synchronized (lock) { lock.wait(); }
            return null;
        }).when(mockReader).awaitSendWU();

        ByteBuffer frame = streamCtx.createFrameBuffer(100, 50, 0x00, 0x00);

        // Start write on background thread (it will block)
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        new Thread(() -> {
            try {
                streamCtx.writeDataFrame(frame, 50);
            } catch (Exception e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        }).start();

        // Give thread time to enter the while loop
        Thread.sleep(200);

        // Restore window and wake up the waiting thread
        streamCtx.sendWindow = 65535;
        mockReader.connectSendWindow = 65535;
        synchronized (lock) { lock.notifyAll(); }

        assertTrue(done.await(3, TimeUnit.SECONDS), "write should complete after WU");
        assertNull(error.get());
        // verify window was decremented correctly after unblock
        assertEquals(65535 - 50, streamCtx.sendWindow);
        assertEquals(65535 - 50, mockReader.connectSendWindow);
        verify(mockCtx, times(1)).write(any(ByteBuffer.class));
    }

    @Test
    void writeDataFrame_blocksWhenConnectionWindowExhausted() throws Exception {
        streamCtx.sendWindow = 65535;          // stream window sufficient
        mockReader.connectSendWindow = 20;     // connection window insufficient

        Object lock = new Object();
        doAnswer(inv -> {
            synchronized (lock) { lock.wait(); }
            return null;
        }).when(mockReader).awaitSendWU();

        ByteBuffer frame = streamCtx.createFrameBuffer(100, 50, 0x00, 0x00);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        new Thread(() -> {
            try { streamCtx.writeDataFrame(frame, 50); }
            catch (Exception e) { error.set(e); }
            finally { done.countDown(); }
        }).start();

        Thread.sleep(200);
        streamCtx.sendWindow = 65535;
        mockReader.connectSendWindow = 65535;
        synchronized (lock) { lock.notifyAll(); }

        assertTrue(done.await(3, TimeUnit.SECONDS), "write should complete after conn WU");
        assertNull(error.get());
        verify(mockCtx, times(1)).write(any(ByteBuffer.class));
    }

    // ================================================================
    //  notifyConsumed — receive window recovery
    // ================================================================

    @Test
    void notifyConsumed_sendsWindowUpdate() throws Exception {
        streamCtx.receiveWindow = 0;
        streamCtx.notifyConsumed(100);

        // New contract: the consumer thread no longer mutates receiveWindow directly.
        // It hands the consumed bytes off to the reader thread via the atomic notifyDelta,
        // which the reader drains (receiveWindow += notifyDelta.getAndSet(0)) on the next
        // DATA frame. The immediate, observable effects of notifyConsumed are:
        //  (1) notifyDelta accumulates the consumed bytes, and
        //  (2) the WU is sent.
        assertEquals(100, streamCtx.notifyDelta.get());
        verify(mockReader).sendWindowUpdatePair(eq(mockCtx), eq(streamCtx), eq(100), eq(false));
    }

    @Test
    void notifyConsumed_zeroOrNegativeIsNoOp() throws Exception {
        streamCtx.receiveWindow = 50;
        streamCtx.notifyConsumed(0);
        assertEquals(50, streamCtx.receiveWindow);
        verify(mockReader, never()).sendWindowUpdatePair(any(), any(), anyInt(), anyBoolean());

        streamCtx.notifyConsumed(-1);
        assertEquals(50, streamCtx.receiveWindow);
    }


}
