package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Pure unit test for {@link WebSocketDecoder}.
 *
 * <p>All frames are RFC 6455 masked client frames with zero mask key.
 *
 * <p>NOTE: {@code ChannelContext.invokeHandle()} is declared {@code final} and
 * cannot be proxied by Mockito. The tests work by setting a mock
 * {@link ChannelHandler} on the ChannelContext via {@code setChannelHandler()},
 * so the real {@code invokeHandle} delegates to the mock handler which we
 * can verify or capture.
 */
class WebSocketDecoderTest {

    private ChannelContext ctx;
    private WebSocketResource resource;
    private WebSocketConnection connection;
    private WebSocketDecoder decoder;
    private List<WebSocketFrame> capturedFrames;
    private boolean closeCalled;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() throws IOException {
        capturedFrames = new ArrayList<WebSocketFrame>();
        closeCalled = false;
        resource = new WebSocketResource();
        resource.maxPayloadSize(HttpConf.MAX_WS_FRAME_SIZE);
        connection = mock(WebSocketConnection.class);
        UpgradeWebSocketHolder holder = new UpgradeWebSocketHolder(resource, connection);

        ChannelHandler handler = mock(ChannelHandler.class);
        doAnswer(inv -> {
            capturedFrames.add(inv.getArgument(1));
            return null;
        }).when(handler).onHandle(any(), any());

        ctx = mock(ChannelContext.class, inv -> {
            String n = inv.getMethod().getName();
            if ("binding".equals(n)) return holder;
            if ("setChannelHandler".equals(n)) return inv.callRealMethod();
            if ("close".equals(n)) { closeCalled = true; return null; }
            if ("readFully".equals(n)) return -1;
            return null;
        });
        // set a real ChannelHandler so the final invokeHandle() doesn't NPE
        ctx.setChannelHandler(handler);

        decoder = new WebSocketDecoder();
    }

    // ==================== helpers ====================

    static byte[] frame(boolean fin, int opcode, byte[] payload) {
        int hdr = payload.length < 126 ? 6 : (payload.length < 65536 ? 8 : 14);
        byte[] buf = new byte[hdr + payload.length];
        buf[0] = (byte) ((fin ? 0x80 : 0) | (opcode & 0x0F));
        long len = payload.length;
        if (len < 126) {
            buf[1] = (byte) (0x80 | len);
        } else if (len < 65536) {
            buf[1] = (byte) (0x80 | 126);
            buf[2] = (byte) (len >>> 8);
            buf[3] = (byte) len;
        } else {
            buf[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; ++i) buf[2 + i] = (byte) (len >>> (56 - i * 8));
        }
        System.arraycopy(payload, 0, buf, hdr, payload.length);
        return buf;
    }

    static byte[] unmasked(boolean fin, int opcode, byte[] payload) {
        int hdr = payload.length < 126 ? 2 : (payload.length < 65536 ? 4 : 10);
        byte[] buf = new byte[hdr + payload.length];
        buf[0] = (byte) ((fin ? 0x80 : 0) | (opcode & 0x0F));
        long len = payload.length;
        if (len < 126) {
            buf[1] = (byte) len;
        } else if (len < 65536) {
            buf[1] = (byte) 126;
            buf[2] = (byte) (len >>> 8);
            buf[3] = (byte) len;
        } else {
            buf[1] = (byte) 127;
            for (int i = 0; i < 8; ++i) buf[2 + i] = (byte) (len >>> (56 - i * 8));
        }
        System.arraycopy(payload, 0, buf, hdr, payload.length);
        return buf;
    }

    static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] r = new byte[total];
        int p = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, r, p, a.length); p += a.length; }
        return r;
    }

    // ================================================================
    //  Basic frame decoding
    // ================================================================

    @Test
    void testTextFrame() throws Exception {
        decoder.decode(frame(true, 0x1, "Hello".getBytes()), 0, 11, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testBinaryFrame() throws Exception {
        byte[] data = {0x00, 0x01, 0x02, (byte) 0xFE, (byte) 0xFF};
        decoder.decode(frame(true, 0x2, data), 0, 11, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(data, capturedFrames.get(0).getData());
    }

    @Test
    void testCloseFrame() throws Exception {
        decoder.decode(frame(true, 0x8, new byte[0]), 0, 6, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals(WebSocketFrame.FrameType.CLOSE, capturedFrames.get(0).getType());
    }

    @Test
    void testPingFrame() throws Exception {
        decoder.decode(frame(true, 0x9, "ping".getBytes()), 0, 10, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals(WebSocketFrame.FrameType.PING, capturedFrames.get(0).getType());
    }

    @Test
    void testPongFrame() throws Exception {
        decoder.decode(frame(true, 0xA, "pong".getBytes()), 0, 10, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals(WebSocketFrame.FrameType.PONG, capturedFrames.get(0).getType());
    }

    @Test
    void testEmptyPayload() throws Exception {
        decoder.decode(frame(true, 0x1, new byte[0]), 0, 6, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals(0, capturedFrames.get(0).getData().length);
    }

    // ================================================================
    //  Extended length
    // ================================================================

    @Test
    void test16BitLength() throws Exception {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; ++i) payload[i] = (byte) i;
        byte[] f = frame(true, 0x2, payload);
        decoder.decode(f, 0, f.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void test64BitLength() throws Exception {
        byte[] payload = new byte[70000];
        for (int i = 0; i < payload.length; ++i) payload[i] = (byte) (i & 0xFF);
        byte[] f = frame(true, 0x2, payload);
        decoder.decode(f, 0, f.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void test64BitLengthExceedsMax() throws Exception {
        int oversized = HttpConf.MAX_WS_FRAME_SIZE + 1;
        byte[] h = new byte[14];
        h[0] = (byte) 0x82;
        h[1] = (byte) (0x80 | 127);
        for (int i = 0; i < 8; ++i) h[2 + i] = (byte) (oversized >>> (56 - i * 8));
        decoder.decode(h, 0, 14, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    // ================================================================
    //  Fragmented (MERGE default)
    // ================================================================

    @Test
    void testTwoFragmentMerge() throws Exception {
        byte[] all = concat(frame(false, 0x1, "Hello ".getBytes()), frame(true, 0x0, "World".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello World", new String(capturedFrames.get(0).getData()));
        assertTrue(capturedFrames.get(0).isFin());
    }

    @Test
    void testThreeFragmentMerge() throws Exception {
        byte[] all = concat(
                frame(false, 0x2, new byte[]{0x01, 0x02}),
                frame(false, 0x0, new byte[]{0x03, 0x04}),
                frame(true, 0x0, new byte[]{0x05, 0x06}));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06}, capturedFrames.get(0).getData());
        assertTrue(capturedFrames.get(0).isFin());
    }

    // ================================================================
    //  BATCH
    // ================================================================

    @Test
    void testBatchSplitWhenExceedsLimit() throws Exception {
        resource.continuationStrategy(WebSocketResource.ContinuationStrategy.BATCH).maxPayloadSize(10);
        byte[] all = concat(
                frame(false, 0x1, "AAAAA".getBytes()),
                frame(false, 0x0, "BBBBB".getBytes()),
                frame(true, 0x0, "CCCCC".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(2, capturedFrames.size());
        assertEquals("AAAAABBBBB", new String(capturedFrames.get(0).getData()));
        assertFalse(capturedFrames.get(0).isFin());
        assertEquals("CCCCC", new String(capturedFrames.get(1).getData()));
        assertTrue(capturedFrames.get(1).isFin());
    }

    @Test
    void testBatchWithinLimit() throws Exception {
        resource.continuationStrategy(WebSocketResource.ContinuationStrategy.BATCH).maxPayloadSize(100);
        byte[] all = concat(frame(false, 0x1, "Hello ".getBytes()), frame(true, 0x0, "World".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello World", new String(capturedFrames.get(0).getData()));
        assertTrue(capturedFrames.get(0).isFin());
    }

    // ================================================================
    //  STREAM
    // ================================================================

    @Test
    void testStreamEveryFrameDispatched() throws Exception {
        resource.continuationStrategy(WebSocketResource.ContinuationStrategy.STREAM);
        byte[] all = concat(
                frame(false, 0x1, "chunk1".getBytes()),
                frame(false, 0x0, "chunk2".getBytes()),
                frame(true, 0x0, "chunk3".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(3, capturedFrames.size());
        assertEquals("chunk1", new String(capturedFrames.get(0).getData()));
        assertFalse(capturedFrames.get(0).isFin());
        assertEquals("chunk2", new String(capturedFrames.get(1).getData()));
        assertFalse(capturedFrames.get(1).isFin());
        assertEquals("chunk3", new String(capturedFrames.get(2).getData()));
        assertTrue(capturedFrames.get(2).isFin());
    }

    // ================================================================
    //  Protocol errors
    // ================================================================

    @Test
    void testUnmaskedFrameReturns1002() throws Exception {
        decoder.decode(unmasked(true, 0x1, "hello".getBytes()), 0, 7, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testOrphanContinuationReturns1002() throws Exception {
        decoder.decode(frame(true, 0x0, "orphan".getBytes()), 0, 12, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testFragmentedControlFrameReturns1002() throws Exception {
        // RFC 6455 §5.5: control frames MUST NOT be fragmented.
        // A control frame with fin=0 followed by a continuation frame must be
        // rejected (1002) and must NOT be merged into a control frame.
        byte[] all = concat(frame(false, 0x9, "p".getBytes()), frame(true, 0x0, "q".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testWrongOpcodeAfterFin0Returns1002() throws Exception {
        byte[] all = concat(frame(false, 0x1, "first".getBytes()), frame(true, 0x2, "bad".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertTrue(closeCalled);
        // The first frame (fin=false) is NOT dispatched before the error occurs
        // because the decoder only dispatches when fin=true or STREAM strategy
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testOversizedMergedPayloadReturns1009() throws Exception {
        resource.maxPayloadSize(10);
        byte[] all = concat(frame(false, 0x1, "AAAAA".getBytes()), frame(true, 0x0, "BBBBBB".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    // ================================================================
    //  Control frame interjection (RFC 6455 §5.4)
    // ================================================================

    @Test
    void testPingBetweenFragments() throws Exception {
        byte[] all = concat(
                frame(false, 0x1, "chunk1".getBytes()),
                frame(true, 0x9, "ping".getBytes()),
                frame(true, 0x0, "chunk2".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(2, capturedFrames.size());
        assertEquals(WebSocketFrame.FrameType.PING, capturedFrames.get(0).getType());
        assertEquals(WebSocketFrame.FrameType.TEXT, capturedFrames.get(1).getType());
        assertEquals("chunk1chunk2", new String(capturedFrames.get(1).getData()));
        assertTrue(capturedFrames.get(1).isFin());
    }

    @Test
    void testCloseBetweenFragments() throws Exception {
        byte[] all = concat(
                frame(false, 0x1, "partial".getBytes()),
                frame(true, 0x8, new byte[0]),
                frame(true, 0x0, "rest".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(2, capturedFrames.size());
        assertEquals(WebSocketFrame.FrameType.CLOSE, capturedFrames.get(0).getType());
        assertEquals(WebSocketFrame.FrameType.TEXT, capturedFrames.get(1).getType());
        assertEquals("partialrest", new String(capturedFrames.get(1).getData()));
    }

    // ================================================================
    //  Partial data (readFully mocked)
    // ================================================================

    @Test
    void testIncompleteHeaderTriggersRead() throws Exception {
        byte[] f = frame(true, 0x1, "Hello World".getBytes());
        byte[] chunk = new byte[4];
        System.arraycopy(f, 0, chunk, 0, 4);
        final int[] readOffset = {4};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = f.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(f, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(chunk, 0, 4, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello World", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testIncompletePayloadTriggersReadInternal() throws Exception {
        byte[] payload = "This is a long message that will be split".getBytes();
        byte[] f = frame(true, 0x1, payload);
        int split = f.length - 10;
        byte[] chunk = new byte[split];
        System.arraycopy(f, 0, chunk, 0, split);
        final int[] readOffset = {split};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = f.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(f, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(chunk, 0, split, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void testMultipleFramesAcrossCalls() throws Exception {
        resource.continuationStrategy(WebSocketResource.ContinuationStrategy.STREAM);
        decoder.decode(frame(true, 0x1, "first".getBytes()), 0, 11, ctx);
        assertEquals(1, capturedFrames.size());
        assertEquals("first", new String(capturedFrames.get(0).getData()));

        decoder.decode(frame(true, 0x1, "second".getBytes()), 0, 12, ctx);
        assertEquals(2, capturedFrames.size());
        assertEquals("second", new String(capturedFrames.get(1).getData()));

        assertFalse(closeCalled);
    }

    // ================================================================
    //  Multiple frames in one call
    // ================================================================

    @Test
    void testTwoFramesInOneCall() throws Exception {
        byte[] all = concat(frame(true, 0x1, "alpha".getBytes()), frame(true, 0x2, new byte[]{0x01, 0x02}));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(2, capturedFrames.size());
        assertEquals("alpha", new String(capturedFrames.get(0).getData()));
        assertArrayEquals(new byte[]{0x01, 0x02}, capturedFrames.get(1).getData());
    }

    @Test
    void testThreeFramesInOneCall() throws Exception {
        byte[] all = concat(
                frame(true, 0x9, "ping".getBytes()),
                frame(true, 0xA, "pong".getBytes()),
                frame(true, 0x1, "text".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(3, capturedFrames.size());
    }

    // ================================================================
    //  Unmask switch fallthrough (line 176-184)
    // ================================================================

    @Test
    void testUnmaskRemainder1() throws Exception {
        decoder.decode(frame(true, 0x1, "A".getBytes()), 0, 7, ctx);
        assertEquals("A", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskRemainder2() throws Exception {
        decoder.decode(frame(true, 0x1, "AB".getBytes()), 0, 8, ctx);
        assertEquals("AB", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskRemainder3() throws Exception {
        decoder.decode(frame(true, 0x1, "ABC".getBytes()), 0, 9, ctx);
        assertEquals("ABC", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskRemainder4() throws Exception {
        decoder.decode(frame(true, 0x1, "ABCD".getBytes()), 0, 10, ctx);
        assertEquals("ABCD", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskRemainder5() throws Exception {
        decoder.decode(frame(true, 0x1, "ABCDE".getBytes()), 0, 11, ctx);
        assertEquals("ABCDE", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskRemainder7() throws Exception {
        decoder.decode(frame(true, 0x1, "ABCDEFG".getBytes()), 0, 13, ctx);
        assertEquals("ABCDEFG", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskExactAligned() throws Exception {
        // 8 bytes (aligned == len, return early)
        decoder.decode(frame(true, 0x1, "12345678".getBytes()), 0, 14, ctx);
        assertEquals("12345678", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testUnmaskJdk8Path() throws Exception {
        // Force the JDK 8 fallback unmask path (unrolled byte loop) regardless of the
        // actual runtime JDK, by overriding the white-box hook useNativeUnmask().
        WebSocketDecoder jdk8Decoder = new WebSocketDecoder() {
            @Override
            protected boolean useNativeUnmask() {
                return false;
            }
        };
        byte[] payload = "Hello, JDK8!".getBytes();
        jdk8Decoder.decode(frame(true, 0x1, payload), 0, 6 + payload.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello, JDK8!", new String(capturedFrames.get(0).getData()));
    }

    // ================================================================
    //  Partial extended-length header (readFully needed)
    // ================================================================

    private void setupReadFully(byte[] fullFrame, int[] readOffset) throws IOException {
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = fullFrame.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(fullFrame, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
    }

    @Test
    void testPartial16BitHeader() throws Exception {
        byte[] payload = new byte[200];
        byte[] fullFrame = frame(true, 0x2, payload);
        byte[] partial = new byte[6]; // only first 6 bytes (header minus 2-byte length)
        System.arraycopy(fullFrame, 0, partial, 0, 6);
        int[] readOffset = {6};
        setupReadFully(fullFrame, readOffset);
        decoder.decode(partial, 0, 6, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void testPartial64BitHeader() throws Exception {
        byte[] payload = new byte[70000];
        byte[] fullFrame = frame(true, 0x2, payload);
        byte[] partial = new byte[6]; // only first 6 bytes (header minus 8-byte length)
        System.arraycopy(fullFrame, 0, partial, 0, 6);
        int[] readOffset = {6};
        setupReadFully(fullFrame, readOffset);
        decoder.decode(partial, 0, 6, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void testPayloadRemZero() throws Exception {
        // Send exactly 6 bytes (header+mask) of a small frame, no payload bytes
        byte[] fullFrame = frame(true, 0x1, "hello".getBytes());
        byte[] onlyHeader = new byte[6];
        System.arraycopy(fullFrame, 0, onlyHeader, 0, 6);
        int[] readOffset = {6};
        setupReadFully(fullFrame, readOffset);
        decoder.decode(onlyHeader, 0, 6, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("hello", new String(capturedFrames.get(0).getData()));
    }

    // ================================================================
    //  Fragmentation attack defenses (continuation count + deadline)
    // ================================================================

    @Test
    void testContinuationCountExceededReturns1009() throws Exception {
        resource.maxContinuations(3); // allow at most 3 continuation frames
        byte[] all = concat(
                frame(false, 0x1, "A".getBytes()),
                frame(false, 0x0, "B".getBytes()),
                frame(false, 0x0, "C".getBytes()),
                frame(false, 0x0, "D".getBytes()), // 4th continuation → exceeds limit
                frame(true, 0x0, "E".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testContinuationCountAtLimitSucceeds() throws Exception {
        resource.maxContinuations(3); // exactly 3 continuation frames allowed
        byte[] all = concat(
                frame(false, 0x1, "H".getBytes()),
                frame(false, 0x0, "e".getBytes()),
                frame(false, 0x0, "l".getBytes()),
                frame(true, 0x0, "o".getBytes())); // 3rd continuation, fin=true
        decoder.decode(all, 0, all.length, ctx);
        assertFalse(closeCalled);
        assertEquals(1, capturedFrames.size());
        assertEquals("Helo", new String(capturedFrames.get(0).getData()));
        assertTrue(capturedFrames.get(0).isFin());
    }

    @Test
    void testContinuationDeadlineExceededReturns1009() throws Exception {
        resource.maxContinuations(256).fragmentMergeTimeoutMs(-1); // deadline already expired
        byte[] all = concat(
                frame(false, 0x1, "A".getBytes()),
                frame(true, 0x0, "B".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    //

    @Test
    void testRsvBitsSetReturns1002() throws Exception {
        byte[] payload = "hello".getBytes();
        byte[] f = new byte[6 + payload.length];
        f[0] = (byte) 0xC1;
        f[1] = (byte) (0x80 | payload.length);
        for (int i = 0; i < payload.length; ++i) {
            f[6 + i] = payload[i];
        }
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testIncompleteHeaderRead() throws Exception {
        byte[] f = frame(true, 0x1, "hello".getBytes());
        byte[] partial = new byte[4];
        System.arraycopy(f, 0, partial, 0, 4);
        final int[] readOffset = {4};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = f.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(f, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(partial, 0, 4, ctx);
        assertEquals("hello", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testIncomplete16BitHeader() throws Exception {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; ++i) payload[i] = (byte) i;
        byte[] f = frame(true, 0x2, payload);
        byte[] partial = new byte[6];
        System.arraycopy(f, 0, partial, 0, 6);
        final int[] readOffset = {6};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = f.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(f, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(partial, 0, 6, ctx);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void testIncomplete64BitHeader() throws Exception {
        byte[] payload = new byte[70000];
        for (int i = 0; i < payload.length; ++i) payload[i] = (byte) (i & 0xFF);
        byte[] f = frame(true, 0x2, payload);
        byte[] partial = new byte[10];
        System.arraycopy(f, 0, partial, 0, 10);
        final int[] readOffset = {10};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = f.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(f, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(partial, 0, 10, ctx);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void test64BitLengthMsbSetReturns1002() throws Exception {
        byte[] payload = "hi".getBytes();
        byte[] f = new byte[14 + payload.length];
        f[0] = (byte) 0x81;
        f[1] = (byte) (0x80 | 127);
        f[2] = (byte) 0x80;
        for (int i = 0; i < payload.length; ++i) {
            f[14 + i] = payload[i];
        }
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void test16BitLengthExceedsMax() throws Exception {
        resource.maxPayloadSize(100);
        byte[] f = frame(true, 0x1, new byte[200]);
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
    }

    @Test
    void testPartialPayloadRead() throws Exception {
        byte[] payload = "This is a long message that spans multiple chunks".getBytes();
        byte[] f = frame(true, 0x1, payload);
        int split = f.length - 15;
        byte[] chunk = new byte[split];
        System.arraycopy(f, 0, chunk, 0, split);
        final int[] readOffset = {split};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = f.length - readOffset[0];
            int toCopy = Math.min(len, avail);
            System.arraycopy(f, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(chunk, 0, split, ctx);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals(payload, capturedFrames.get(0).getData());
    }

    @Test
    void testNonZeroMask() throws Exception {
        byte[] payload = "Hello".getBytes();
        byte[] f = new byte[6 + payload.length];
        f[0] = (byte) 0x81;
        f[1] = (byte) (0x80 | payload.length);
        f[2] = 0x12; f[3] = 0x34; f[4] = 0x56; f[5] = 0x78;
        for (int i = 0; i < payload.length; ++i) {
            f[6 + i] = (byte) (payload[i] ^ f[2 + (i % 4)]);
        }
        decoder.decode(f, 0, f.length, ctx);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello", new String(capturedFrames.get(0).getData()));
    }

    @Test
    void testNonFinControlBetweenFragments() throws Exception {
        byte[] all = concat(
                frame(false, 0x1, "chunk1".getBytes()),
                frame(false, 0x8, new byte[0]),
                frame(true, 0x0, "chunk2".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertTrue(closeCalled);
    }

    @Test
    void testBatchMergeTooLargeFlush() throws Exception {
        resource.continuationStrategy(WebSocketResource.ContinuationStrategy.BATCH).maxPayloadSize(10);
        byte[] all = concat(
                frame(false, 0x1, "AAAAA".getBytes()),
                frame(false, 0x0, "BBBBB".getBytes()),
                frame(true, 0x0, "CCCCC".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertEquals(2, capturedFrames.size());
    }

    @Test
    void testControlFrameRemZeroBetweenContinuations() throws Exception {
        byte[] all = concat(
                frame(false, 0x1, "chunk1".getBytes()),
                frame(true, 0x9, new byte[0]));
        decoder.decode(all, 0, all.length, ctx);
        assertEquals(1, capturedFrames.size());
    }

    @Test
    void testUnmaskSwitchAllRemainders() throws Exception {
        byte[][] frames = new byte[7][];
        for (int i = 1; i <= 7; ++i) {
            byte[] pl = new byte[i];
            for (int j = 0; j < i; j++) pl[j] = (byte) (j + 1);
            frames[i - 1] = frame(true, 0x2, pl);
        }
        byte[] all = concat(frames);
        capturedFrames.clear();
        decoder.decode(all, 0, all.length, ctx);
        assertEquals(7, capturedFrames.size());
    }

    @Test
    void testUnmaskAlignedEqualsLen() throws Exception {
        decoder.decode(frame(true, 0x2, "12345678".getBytes()), 0, 14, ctx);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals("12345678".getBytes(), capturedFrames.get(0).getData());
    }

    @Test
    void testControlFramePayloadTooLarge() throws Exception {
        byte[] payload = new byte[200];
        byte[] f = frame(true, 0x9, payload);
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testLine155RemZero() throws Exception {
        byte[] frame1 = frame(false, 0x1, "hello".getBytes());
        byte[] frame2 = frame(true, 0x0, " world".getBytes());
        byte[] all = concat(frame1, frame2);
        int frame1Len = frame1.length;
        final int[] readOffset = {frame1Len};
        doAnswer(inv -> {
            byte[] buf = inv.getArgument(0);
            int off = inv.getArgument(1);
            int len = inv.getArgument(2);
            int avail = all.length - readOffset[0];
            if (avail <= 0) return -1;
            int toCopy = Math.min(len, avail);
            System.arraycopy(all, readOffset[0], buf, off, toCopy);
            readOffset[0] += toCopy;
            return toCopy;
        }).when(ctx).readFully(any(byte[].class), anyInt(), anyInt(), anyLong());
        decoder.decode(frame1, 0, frame1Len, ctx);
        assertEquals(1, capturedFrames.size());
        assertEquals("hello world", new String(capturedFrames.get(0).getData()));
    }
}
