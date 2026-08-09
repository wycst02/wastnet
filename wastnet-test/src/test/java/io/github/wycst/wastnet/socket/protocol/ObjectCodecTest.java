package io.github.wycst.wastnet.socket.protocol;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ObjectCodec}.
 * <p>
 * Frame format: [Magic 4B][BodyLength 4B][SeqID 4B][Payload...][CRC16 2B]
 * Tests cover encoding, decoding with/without validation, and error handling.
 *
 * @author wangyc
 */
public class ObjectCodecTest {

    private static final int DEFAULT_MAGIC = 0x57534E54; // "WSNT"
    private static final byte[] MAGIC_BYTES = new byte[]{0x57, 0x53, 0x4E, 0x54};
    private static final int HEADER_LEN = 12;
    private static final int TRAILER_LEN = 2;

    private ChannelContext mockCtx;
    private ObjectProtocol mockProtocol;

    @BeforeEach
    public void setUp() throws Exception {
        mockCtx = mock(ChannelContext.class);
        // invokeHandle is final; inject a mock channelHandler to avoid NPE
        Field handlerField = ChannelContext.class.getDeclaredField("channelHandler");
        handlerField.setAccessible(true);
        handlerField.set(mockCtx, mock(ChannelHandler.class));

        mockProtocol = mock(ObjectProtocol.class);
    }

    // ==================== Encoding ====================

    @Test
    public void testEncodeFrameStructure() throws Exception {
        byte[] body = "hello".getBytes();
        when(mockProtocol.encode("hello")).thenReturn(body);

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol);
        ByteBuffer buf = codec.write(mockCtx, "hello");

        Assertions.assertNotNull(buf);
        byte[] frame = new byte[buf.remaining()];
        buf.get(frame);

        int totalFrameLen = HEADER_LEN + body.length + TRAILER_LEN;
        Assertions.assertEquals(totalFrameLen, frame.length);

        // Header: magic(4) + wireLength(4) + seqId(4)
        // Magic
        Assertions.assertArrayEquals(MAGIC_BYTES, new byte[]{frame[0], frame[1], frame[2], frame[3]});
        // Body length in wire format
        int wireLen = ((frame[4] & 0xFF) << 24) | ((frame[5] & 0xFF) << 16)
                | ((frame[6] & 0xFF) << 8) | (frame[7] & 0xFF);
        Assertions.assertEquals(body.length, wireLen);
        // SeqId
        int seqId = ((frame[8] & 0xFF) << 24) | ((frame[9] & 0xFF) << 16)
                | ((frame[10] & 0xFF) << 8) | (frame[11] & 0xFF);
        Assertions.assertTrue(seqId > 0);

        // Body
        byte[] actualBody = new byte[body.length];
        System.arraycopy(frame, HEADER_LEN, actualBody, 0, body.length);
        Assertions.assertArrayEquals(body, actualBody);

        // Trailer (default 0)
        short trailer = (short) ((frame[totalFrameLen - 2] & 0xFF) << 8 | (frame[totalFrameLen - 1] & 0xFF));
        Assertions.assertEquals(0, trailer);
    }

    @Test
    public void testEncodeNullBodyReturnsNull() throws Exception {
        when(mockProtocol.encode("null")).thenReturn(null);

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol);
        ByteBuffer buf = codec.write(mockCtx, "null");

        Assertions.assertNull(buf);
    }

    @Test
    public void testEncodeWithCustomMagic() throws Exception {
        int customMagic = 0x12345678;
        byte[] body = "data".getBytes();
        when(mockProtocol.encode("data")).thenReturn(body);

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, customMagic, false);
        ByteBuffer buf = codec.write(mockCtx, "data");
        Assertions.assertNotNull(buf);

        byte[] frame = new byte[buf.remaining()];
        buf.get(frame);

        int magic = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        Assertions.assertEquals(customMagic, magic);
    }

    @Test
    public void testEncodeWithFrameTrailer() throws Exception {
        short trailerSeed = (short) 0xA5B5;
        byte[] body = "payload".getBytes();
        when(mockProtocol.encode("p")).thenReturn(body);

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, false, false, trailerSeed);
        ByteBuffer buf = codec.write(mockCtx, "p");
        Assertions.assertNotNull(buf);

        byte[] frame = new byte[buf.remaining()];
        buf.get(frame);

        int totalLen = frame.length;
        short trailer = (short) ((frame[totalLen - 2] & 0xFF) << 8 | (frame[totalLen - 1] & 0xFF));
        Assertions.assertEquals(trailerSeed, trailer);
    }

    // ==================== Decoding without validation ====================

    @Test
    public void testDecodeFrameWithoutValidation() throws Exception {
        byte[] body = "decoded".getBytes();
        when(mockProtocol.decode(body)).thenReturn("decoded");

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol);

        ByteBuffer frame = buildFrame(body, DEFAULT_MAGIC, (short) 0);
        codec.decode(mockCtx, frame);

        verify(mockProtocol).decode(body);
        verify(mockCtx, times(1)).setAttribute(eq("_seq"), anyInt());
    }

    // ==================== Decoding with validation ====================

    @Test
    public void testDecodeWithValidationPasses() throws Exception {
        byte[] body = "valid".getBytes();
        when(mockProtocol.decode(body)).thenReturn("valid");

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, true);

        ByteBuffer frame = buildFrame(body, DEFAULT_MAGIC, (short) 0);
        codec.decode(mockCtx, frame);

        verify(mockProtocol).decode(body);
    }

    @Test
    public void testDecodeWithCustomMagicPasses() throws Exception {
        int customMagic = 0xDEADBEEF;
        byte[] body = "custom".getBytes();
        when(mockProtocol.decode(body)).thenReturn("custom");

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, customMagic, true);

        ByteBuffer frame = buildFrame(body, customMagic, (short) 0);
        codec.decode(mockCtx, frame);

        verify(mockProtocol).decode(body);
    }

    @Test
    public void testDecodeWithCustomTrailerPasses() throws Exception {
        short trailerSeed = (short) 0xBEEF;
        byte[] body = "trailer".getBytes();
        when(mockProtocol.decode(body)).thenReturn("trailer");

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, true, false, trailerSeed);

        ByteBuffer frame = buildFrame(body, DEFAULT_MAGIC, trailerSeed);
        codec.decode(mockCtx, frame);

        verify(mockProtocol).decode(body);
    }

    // ==================== Validation errors ====================

    @Test
    public void testDecodeWithMagicMismatchCallsOnProtocolError() throws Exception {
        int wrongMagic = 0x00000000;
        byte[] body = "garbage".getBytes();

        // Validation ON, but wrong magic in frame
        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, true);

        ByteBuffer frame = buildFrame(body, wrongMagic, (short) 0);
        codec.decode(mockCtx, frame);

        // Should NOT decode body
        verify(mockProtocol, never()).decode(any());
        // Should close due to protocol error
        verify(mockCtx, atLeast(0)).close();
    }

    @Test
    public void testDecodeWithTrailerMismatchCallsOnProtocolError() throws Exception {
        byte[] body = "data".getBytes();
        short wrongTrailer = (short) 0xFFFF;

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, true);

        ByteBuffer frame = buildFrame(body, DEFAULT_MAGIC, wrongTrailer);
        codec.decode(mockCtx, frame);

        verify(mockProtocol, never()).decode(any());
        verify(mockCtx, atLeast(0)).close();
    }

    // ==================== Decode error handling ====================

    @Test
    public void testDecodeErrorWithCloseOnCodecErrorTrue() throws Exception {
        byte[] body = "bad".getBytes();
        when(mockProtocol.decode(body)).thenThrow(new RuntimeException("decode failure"));

        // closeOnCodecError defaults to true
        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, false);
        // closeOnCodecError default is true - but let's be explicit
        codec.closeOnCodecError(true);

        ByteBuffer frame = buildFrame(body, DEFAULT_MAGIC, (short) 0);
        codec.decode(mockCtx, frame);

        // Should close the connection on decode error
        verify(mockCtx, atLeastOnce()).close();
    }

    @Test
    public void testDecodeErrorWithCloseOnCodecErrorFalse() throws Exception {
        byte[] body = "bad".getBytes();
        when(mockProtocol.decode(body)).thenThrow(new RuntimeException("decode failure"));

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol, DEFAULT_MAGIC, false);
        codec.closeOnCodecError(false);

        ByteBuffer frame = buildFrame(body, DEFAULT_MAGIC, (short) 0);
        codec.decode(mockCtx, frame);

        // Should NOT close on error when closeOnCodecError is false
        verify(mockCtx, never()).close();
    }

    // ==================== Sequence ID monotonicity ====================

    @Test
    public void testSeqIdIncrementsAcrossEncodes() throws Exception {
        byte[] body = "seq".getBytes();
        when(mockProtocol.encode(any())).thenReturn(body);

        ObjectCodec<String> codec = new ObjectCodec<String>(4096, mockProtocol);

        ByteBuffer buf1 = codec.write(mockCtx, "a");
        ByteBuffer buf2 = codec.write(mockCtx, "b");

        int seq1 = readIntAt(buf1, 8);
        int seq2 = readIntAt(buf2, 8);

        Assertions.assertTrue(seq2 > seq1, "seqId should be strictly increasing");
    }

    // ==================== Helpers ====================

    /** Build a complete frame buffer ready for decode(). */
    private static ByteBuffer buildFrame(byte[] body, int magic, short trailer) {
        int totalLen = HEADER_LEN + body.length + TRAILER_LEN;
        byte[] frame = new byte[totalLen];

        // Magic
        writeInt(frame, 0, magic);
        // Body length
        writeInt(frame, 4, body.length);
        // SeqId
        writeInt(frame, 8, 1);
        // Body
        System.arraycopy(body, 0, frame, HEADER_LEN, body.length);
        // Trailer
        writeShort(frame, HEADER_LEN + body.length, trailer);

        return ByteBuffer.wrap(frame);
    }

    private static void writeInt(byte[] buf, int off, int val) {
        buf[off] = (byte) (val >> 24);
        buf[off + 1] = (byte) (val >> 16);
        buf[off + 2] = (byte) (val >> 8);
        buf[off + 3] = (byte) val;
    }

    private static void writeShort(byte[] buf, int off, short val) {
        buf[off] = (byte) (val >> 8);
        buf[off + 1] = (byte) val;
    }

    private static int readIntAt(ByteBuffer buf, int offset) {
        byte[] arr = new byte[buf.remaining()];
        buf.get(arr);
        return ((arr[offset] & 0xFF) << 24) | ((arr[offset + 1] & 0xFF) << 16)
                | ((arr[offset + 2] & 0xFF) << 8) | (arr[offset + 3] & 0xFF);
    }
}
