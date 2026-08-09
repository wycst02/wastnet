package io.github.wycst.wastnet.socket.channel;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LengthFrameCodec}.
 * <p>
 * Tests cover parameter validation, readLength/writeLength for all lengths
 * with both byte orders, encode/decode flow, and behavior flags.
 * <p>
 * Note: ChannelContext.invokeHandle() is final and cannot be verified via Mockito.
 * Instead we inject a mock ChannelHandler and verify against it.
 */
public class LengthFrameCodecTest {

    // ==================== Parameter validation ====================

    @Test
    public void testValidateHeaderLengthLessThanOne() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(0, 0, 1, 1024));
        Assertions.assertTrue(ex.getMessage().contains("headerLength"));
    }

    @Test
    public void testValidateLengthFieldOffsetNegative() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(8, -1, 2, 1024));
        Assertions.assertTrue(ex.getMessage().contains("lengthFieldOffset"));
    }

    @Test
    public void testValidateLengthFieldOffsetBeyondHeader() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(8, 7, 2, 1024));
        Assertions.assertTrue(ex.getMessage().contains("exceeds header length"));
    }

    @Test
    public void testValidateLengthFieldLengthLessThanOne() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(8, 0, 0, 1024));
        Assertions.assertTrue(ex.getMessage().contains("lengthFieldLength"));
    }

    @Test
    public void testValidateLengthFieldLengthGreaterThanFour() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(8, 0, 5, 1024));
        Assertions.assertTrue(ex.getMessage().contains("lengthFieldLength"));
    }

    @Test
    public void testValidateMaxFrameLengthZero() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(4, 0, 2, 0));
        Assertions.assertTrue(ex.getMessage().contains("maxFrameLength"));
    }

    @Test
    public void testValidateTrailerLengthOutOfRange() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(4, 0, 2, 1024, 5, false));
        Assertions.assertTrue(ex.getMessage().contains("trailerLength"));
    }

    @Test
    public void testValidateMaxFrameLengthExceedsFieldCapacity() {
        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LengthFrameCodec<byte[]>(4, 0, 1, 300));
        Assertions.assertTrue(ex.getMessage().contains("maxFrameLength"));
    }

    // ==================== byteOrder ====================

    @Test
    public void testByteOrderNullThrows() {
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        Assertions.assertThrows(IllegalArgumentException.class, () -> codec.byteOrder(null));
    }

    @Test
    public void testByteOrderReturnsThis() {
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        Assertions.assertSame(codec, codec.byteOrder(ByteOrder.LITTLE_ENDIAN));
    }

    // ==================== readLength / writeLength round trips ====================

    @Test
    public void testReadWriteLength1Byte() {
        innerTestReadWriteLength(1, ByteOrder.BIG_ENDIAN, 200);
        innerTestReadWriteLength(1, ByteOrder.LITTLE_ENDIAN, 200);
    }

    @Test
    public void testReadWriteLength2Bytes() {
        innerTestReadWriteLength(2, ByteOrder.BIG_ENDIAN, 0xABCD);
        innerTestReadWriteLength(2, ByteOrder.LITTLE_ENDIAN, 0xABCD);
    }

    @Test
    public void testReadWriteLength3Bytes() {
        innerTestReadWriteLength(3, ByteOrder.BIG_ENDIAN, 0xABCDEF);
        innerTestReadWriteLength(3, ByteOrder.LITTLE_ENDIAN, 0xABCDEF);
    }

    @Test
    public void testReadWriteLength4Bytes() {
        innerTestReadWriteLength(4, ByteOrder.BIG_ENDIAN, 0x12345678);
        innerTestReadWriteLength(4, ByteOrder.LITTLE_ENDIAN, 0x12345678);
    }

    @Test
    public void testReadWriteLength4BytesMaxValue() {
        innerTestReadWriteLength(4, ByteOrder.BIG_ENDIAN, Integer.MAX_VALUE);
        innerTestReadWriteLength(4, ByteOrder.LITTLE_ENDIAN, Integer.MAX_VALUE);
    }

    private void innerTestReadWriteLength(int fieldLength, ByteOrder order, int value) {
        int headerLen = Math.max(fieldLength, 4);
        // Use maxFrameLength that fits within the field capacity
        long fieldMax = (1L << (fieldLength << 3)) - 1;
        int maxFrame = (int) Math.min(fieldMax, Integer.MAX_VALUE);
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(headerLen, 0, fieldLength, maxFrame);
        codec.byteOrder(order);

        byte[] header = new byte[headerLen];
        codec.writeLength(header, 0, value);
        int result = codec.readLength(header, 0);
        Assertions.assertEquals(value, result);
    }

    // ==================== write ====================

    @Test
    public void testWriteReturnsByteBufferWithCorrectFrame() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);

        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        byte[] body = "hello".getBytes();
        ByteBuffer buf = codec.write(ctx, body);

        Assertions.assertNotNull(buf);
        int frameLen = 4 + body.length;
        Assertions.assertEquals(frameLen, buf.remaining());

        byte[] frame = new byte[frameLen];
        buf.get(frame);
        // Length field (2 bytes) at offset 0
        int wireLen = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        Assertions.assertEquals(body.length, wireLen);
        // body
        byte[] actualBody = new byte[body.length];
        System.arraycopy(frame, 4, actualBody, 0, body.length);
        Assertions.assertArrayEquals(body, actualBody);
    }

    @Test
    public void testWriteNullBodyReturnsNull() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        ByteBuffer buf = codec.write(ctx, null);
        Assertions.assertNull(buf);
    }

    @Test
    public void testWriteExceedsMaxFrameLengthThrows() {
        ChannelContext ctx = mock(ChannelContext.class);
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 10);
        byte[] body = "this is way too long body".getBytes();
        IOException ex = Assertions.assertThrows(IOException.class, () -> codec.write(ctx, body));
        Assertions.assertTrue(ex.getMessage().contains("maxFrameLength"));
    }

    @Test
    public void testWriteWithLengthIncludesHeader() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);

        int headerLen = 4;
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(headerLen, 0, 2, 1024, 0, true);
        byte[] body = "data".getBytes();
        ByteBuffer buf = codec.write(ctx, body);

        Assertions.assertNotNull(buf);
        byte[] frame = new byte[buf.remaining()];
        buf.get(frame);
        int wireLen = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        Assertions.assertEquals(headerLen + body.length, wireLen);
    }

    @Test
    public void testWriteWithTrailer() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);

        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024, 2, false);
        byte[] body = "trailer".getBytes();
        ByteBuffer buf = codec.write(ctx, body);

        Assertions.assertNotNull(buf);
        int frameLen = 4 + body.length + 2;
        Assertions.assertEquals(frameLen, buf.remaining());

        byte[] frame = new byte[frameLen];
        buf.get(frame);
        Assertions.assertEquals(0, frame[frameLen - 2]);
        Assertions.assertEquals(0, frame[frameLen - 1]);
    }

    @Test
    public void testWriteEncodeErrorWithCloseOnCodecErrorTrue() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);

        LengthFrameCodec<String> codec = new LengthFrameCodec<String>(4, 0, 2, 1024);
        codec.closeOnCodecError(true);
        ByteBuffer buf = codec.write(ctx, "not-byte[]");
        Assertions.assertNull(buf);
        // close() should have been called via onProtocolError
        verify(ctx).close();
    }

    @Test
    public void testWriteEncodeErrorWithCloseOnCodecErrorFalse() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);

        LengthFrameCodec<String> codec = new LengthFrameCodec<String>(4, 0, 2, 1024);
        codec.closeOnCodecError(false);
        ByteBuffer buf = codec.write(ctx, "not-byte[]");
        Assertions.assertNull(buf);
        verify(ctx, never()).close();
    }

    // ==================== closeOnCodecError chaining ====================

    @Test
    public void testCloseOnCodecErrorReturnsThis() {
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        Assertions.assertSame(codec, codec.closeOnCodecError(true));
        Assertions.assertSame(codec, codec.closeOnCodecError(false));
    }

    // ==================== Override method defaults ====================

    @Test
    public void testComputeActualHeaderLengthDefaultsToHeaderLength() {
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(8, 2, 2, 1024);
        Assertions.assertEquals(8, codec.computeActualHeaderLength(100));
    }

    @Test
    public void testOnProtocolErrorClosesContext() {
        ChannelContext ctx = mock(ChannelContext.class);
        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        codec.onProtocolError(ctx, new IOException("test"));
        verify(ctx).close();
    }

    // ==================== Decode path (invokeHandle verification via handler injection) ====================

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeCompleteFrame() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        ChannelHandler<byte[]> handler = mock(ChannelHandler.class);
        injectChannelHandler(ctx, handler);

        LengthFrameCodec<byte[]> codec = new LengthFrameCodec<byte[]>(4, 0, 2, 1024);
        byte[] body = "hello".getBytes();
        ByteBuffer frame = buildCompleteFrame(body, 4, 2);

        codec.decode(ctx, frame);

        // Verify that the handler received the body via decodeBody -> invokeHandle -> handler.onHandle
        verify(handler).onHandle(same(ctx), eq(body));
    }

    // ==================== encodeBody default ====================

    @Test
    public void testEncodeBodyDefaultForNonByteArrayReturnsNullWithError() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        LengthFrameCodec<String> codec = new LengthFrameCodec<String>(4, 0, 2, 1024);
        // closeOnCodecError defaults to true, so write() catches the exception,
        // calls onProtocolError -> ctx.close(), and returns null
        ByteBuffer result = codec.write(ctx, "string");
        Assertions.assertNull(result);
    }

    @Test
    public void testEncodeBodyDefaultForNonByteArrayWithCloseOnCodecErrorFalse() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        LengthFrameCodec<String> codec = new LengthFrameCodec<String>(4, 0, 2, 1024);
        codec.closeOnCodecError(false);
        ByteBuffer result = codec.write(ctx, "string");
        Assertions.assertNull(result);
        verify(ctx, never()).close();
    }

    // ==================== Helpers ====================

    /** Inject a mock ChannelHandler into the mocked ChannelContext for decode path testing. */
    private static void injectChannelHandler(ChannelContext ctx, ChannelHandler<byte[]> handler) throws Exception {
        Field handlerField = ChannelContext.class.getDeclaredField("channelHandler");
        handlerField.setAccessible(true);
        handlerField.set(ctx, handler);
    }

    /** Build a complete frame in a ByteBuffer, ready for decode(). */
    private static ByteBuffer buildCompleteFrame(byte[] body, int headerLen, int lengthFieldLen) {
        int totalLen = headerLen + body.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        byte[] header = new byte[headerLen];
        if (lengthFieldLen == 2) {
            header[0] = (byte) (body.length >> 8);
            header[1] = (byte) body.length;
        }
        buf.put(header);
        buf.put(body);
        buf.flip();
        return buf;
    }
}
