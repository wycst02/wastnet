package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.env.RuntimeEnv;
import io.github.wycst.wastnet.http.HttpConf;
import io.github.wycst.wastnet.http.upgrade.UpgradeWebSocketHolder;
import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Coverage tests for uncovered WebSocketDecoder branches.
 */
class WebSocketDecoderCoverageTest {

    private ChannelContext ctx;
    private WebSocketResource resource;
    private WebSocketDecoder decoder;
    private List<WebSocketFrame> capturedFrames;
    private boolean closeCalled;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() throws IOException {
        capturedFrames = new ArrayList<>();
        closeCalled = false;
        resource = new WebSocketResource();
        resource.maxPayloadSize(HttpConf.MAX_WS_FRAME_SIZE);
        WebSocketConnection connection = mock(WebSocketConnection.class);
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
        ctx.setChannelHandler(handler);
        decoder = new WebSocketDecoder();
    }

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

    // ===== L58: opcode > 15 (RSV flags set) =====

    @Test
    void testRsvBitsSetReturns1002() throws Exception {
        // Frame with RSV1=1: first byte = 0x80 (FIN) | 0x40 (RSV1) | 0x01 (text opcode) = 0xC1
        // firSign & 0x7F = 0x41 = 65 > 15 → triggers protocol error
        byte[] payload = "hello".getBytes();
        byte[] f = new byte[6 + payload.length];
        f[0] = (byte) 0xC1;                              // FIN + RSV1 + text
        f[1] = (byte) (0x80 | payload.length);            // MASK + length
        // zero mask key
        for (int i = 0; i < payload.length; ++i) {
            f[6 + i] = payload[i];                         // payload not XORed (mask=0)
        }
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    // ===== L36: rem < 6 (incomplete header read) =====

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

    // ===== L52: rem < 8 (16-bit extended length header incomplete) =====

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

    // ===== L60: rem < 14 (64-bit extended length header incomplete) =====

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

    // ===== L60: 64-bit length with MSB set (RFC 6455 §5.2 violation) → 1002 =====

    @Test
    void test64BitLengthMsbSetReturns1002() throws Exception {
        // baseLen == 127 → 64-bit length; set MSB of the 8-byte length (buf[i+2]) to 0x80
        byte[] payload = "hi".getBytes();
        byte[] f = new byte[14 + payload.length];
        f[0] = (byte) 0x81;            // FIN + text opcode
        f[1] = (byte) (0x80 | 127);    // MASK + 64-bit length
        f[2] = (byte) 0x80;            // MSB of 64-bit length MUST be 0 → protocol error
        // remaining length bytes + 4 mask bytes are zero
        for (int i = 0; i < payload.length; ++i) {
            f[14 + i] = payload[i];    // mask=0, payload not XORed
        }
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    // ===== L76: payloadLenLong > maxPayloadSize (16-bit length exceeds) =====

    @Test
    void test16BitLengthExceedsMax() throws Exception {
        resource.maxPayloadSize(100);
        byte[] f = frame(true, 0x1, new byte[200]);
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
    }

    // ===== L87-93: partial payload (rem < payloadLen) =====

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

    // ===== L94: unmask with non-zero mask key =====

    @Test
    void testNonZeroMask() throws Exception {
        // Build masked frame manually with mask key [0x12, 0x34, 0x56, 0x78]
        byte[] payload = "Hello".getBytes();
        byte[] f = new byte[6 + payload.length];
        f[0] = (byte) 0x81;  // FIN + text opcode
        f[1] = (byte) (0x80 | payload.length);  // MASK + length
        f[2] = 0x12; f[3] = 0x34; f[4] = 0x56; f[5] = 0x78;  // mask key
        for (int i = 0; i < payload.length; ++i) {
            f[6 + i] = (byte) (payload[i] ^ f[2 + (i % 4)]);  // pre-XORed payload
        }
        decoder.decode(f, 0, f.length, ctx);
        assertEquals(1, capturedFrames.size());
        assertEquals("Hello", new String(capturedFrames.get(0).getData()));
    }

    // ===== L112: Control frame between continuations (RFC 6455 §5.4) with non-fin control =====

    @Test
    void testNonFinControlBetweenFragments() throws Exception {
        byte[] all = WebSocketDecoderTest.concat(
                frame(false, 0x1, "chunk1".getBytes()),
                frame(false, 0x8, new byte[0]),  // CLOSE without fin (non-control-like)
                frame(true, 0x0, "chunk2".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        // Close called due to protocol error (opcode >= 0x8 but !fin won't reach L112 check)
        assertTrue(closeCalled);
    }

    // ===== L122: mergeLength > maxPayloadSize with BATCH =====

    @Test
    void testBatchMergeTooLargeFlush() throws Exception {
        resource.continuationStrategy(WebSocketResource.ContinuationStrategy.BATCH).maxPayloadSize(10);
        byte[] all = WebSocketDecoderTest.concat(
                frame(false, 0x1, "AAAAA".getBytes()),
                frame(false, 0x0, "BBBBB".getBytes()),
                frame(true, 0x0, "CCCCC".getBytes()));
        decoder.decode(all, 0, all.length, ctx);
        assertEquals(2, capturedFrames.size());
    }

    // ===== L160-174: unmask JDK 8 path (manually unrolled XOR) =====

    private static Object setFinalStatic(Class<?> clazz, String fieldName, boolean value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object original = field.get(null);
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        long offset = unsafe.staticFieldOffset(field);
        Object base = unsafe.staticFieldBase(field);
        if (field.getType() == boolean.class) {
            unsafe.putBoolean(base, offset, value);
        } else {
            unsafe.putObject(base, offset, value);
        }
        return original;
    }

    @Test
    void testUnmaskJdk8Path() throws Exception {
        Object orig = setFinalStatic(RuntimeEnv.class, "JDK9PLUS", false);
        try {
            byte[] payload = "123456789A".getBytes(); // 10 bytes: aligned=8, remainder=2
            byte[] f = new byte[6 + payload.length];
            f[0] = (byte) 0x82;
            f[1] = (byte) (0x80 | payload.length);
            f[2] = (byte) 0xAA; f[3] = (byte) 0xBB; f[4] = (byte) 0xCC; f[5] = (byte) 0xDD;
            for (int i = 0; i < payload.length; ++i) {
                f[6 + i] = (byte) (payload[i] ^ f[2 + (i % 4)]);
            }
            decoder.decode(f, 0, f.length, ctx);
            assertEquals(1, capturedFrames.size());
            assertEquals("123456789A", new String(capturedFrames.get(0).getData()));
        } finally {
            setFinalStatic(RuntimeEnv.class, "JDK9PLUS", (Boolean) orig);
        }
    }

    // ===== L114: rem == 0 after control frame between continuations =====

    @Test
    void testControlFrameRemZeroBetweenContinuations() throws Exception {
        // Fragment (fin=false, opcode=0x1) sets targetFrame, then ping (fin=true, opcode=0x9) is
        // the LAST data → rem==0 after invokeHandle → returns at L114
        byte[] all = WebSocketDecoderTest.concat(
                frame(false, 0x1, "chunk1".getBytes()),
                frame(true, 0x9, new byte[0]));
        decoder.decode(all, 0, all.length, ctx);
        // Only ping should be dispatched (text fragment stored as targetFrame)
        assertEquals(1, capturedFrames.size());
    }

    // ===== L176: unmask switch all remainder cases (1-7) =====

    @Test
    void testUnmaskSwitchAllRemainders() throws Exception {
        // 7 frames with payload lengths 1 through 7 to cover all switch cases
        byte[][] frames = new byte[7][];
        for (int i = 1; i <= 7; ++i) {
            byte[] pl = new byte[i];
            for (int j = 0; j < i; j++) pl[j] = (byte) (j + 1);
            frames[i - 1] = frame(true, 0x2, pl);
        }
        byte[] all = WebSocketDecoderTest.concat(frames);
        capturedFrames.clear();
        decoder.decode(all, 0, all.length, ctx);
        assertEquals(7, capturedFrames.size());
    }

    // ===== L175: aligned == len (payload multiple of 8) =====

    @Test
    void testUnmaskAlignedEqualsLen() throws Exception {
        // 8 bytes payload → aligned=8, len=8, aligned==len → skip switch
        decoder.decode(frame(true, 0x2, "12345678".getBytes()), 0, 14, ctx);
        assertEquals(1, capturedFrames.size());
        assertArrayEquals("12345678".getBytes(), capturedFrames.get(0).getData());
    }

    // ===== L194: getWebSocketErrorMessage default/else branch =====

    @SuppressWarnings("unchecked")
    private String getErrorMessage(int code) throws Exception {
        java.lang.reflect.Method m = WebSocketDecoder.class.getDeclaredMethod("getWebSocketErrorMessage", int.class);
        m.setAccessible(true);
        return (String) m.invoke(decoder, code);
    }

    @Test
    void testGetWebSocketErrorMessageElse() throws Exception {
        // Unknown error code 1006: covers the else/default branch
        String msg = getErrorMessage(1006);
        assertTrue(msg.contains("1006"));
        // Also call 1002 and 1009 for completeness
        assertTrue(getErrorMessage(1002).contains("Protocol Error"));
        assertTrue(getErrorMessage(1009).contains("Message Too Big"));
    }

    // ===== L155: rem == 0 (fin=false, buffer exactly consumed) =====

    // ===== L101: control frame (opcode >= 0x8) with payload > 125 → protocol error 1002 (RFC 6455 §5.5) =====

    @Test
    void testControlFramePayloadTooLarge() throws Exception {
        // Ping (0x9) carrying 200 bytes (> 125) violates RFC 6455 §5.5; must be rejected with 1002
        byte[] payload = new byte[200];
        byte[] f = frame(true, 0x9, payload);
        decoder.decode(f, 0, f.length, ctx);
        assertTrue(closeCalled);
        assertEquals(0, capturedFrames.size());
    }

    @Test
    void testLine155RemZero() throws Exception {
        // Send a fin=false fragment alone so the buffer is fully consumed.
        // After processing, rem==0 at line 155 → i=0. The loop then reads
        // the remaining data (frame2) from the mock.
        byte[] frame1 = frame(false, 0x1, "hello".getBytes());
        byte[] frame2 = frame(true, 0x0, " world".getBytes());
        byte[] all = WebSocketDecoderTest.concat(frame1, frame2);
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
