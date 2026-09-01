package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

/**
 * Unit tests for {@link HttpChunkedStream} — chunked transfer encoding parsing.
 * <p>
 * All chunk data is pre-loaded into {@code readedBytes}; the mock channel returns -1
 * to avoid real I/O. Tests verify the state machine: chunk-size → data → CRLF → end.
 */
@SuppressWarnings("resource")
public class HttpChunkedStreamTest {

    private ChannelContext mockCtx;

    @BeforeEach
    public void setUp() throws IOException {
        mockCtx = mock(ChannelContext.class);
        when(mockCtx.isChannelClosed()).thenReturn(false);
        // No channel reads needed — all data is in readedBytes
        when(mockCtx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1);
    }

    @Test
    public void testReadSingleChunk() throws IOException {
        // chunked: size=5\r\ndata=hello\r\nfinal=0\r\n\r\n
        byte[] wire = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] buf = new byte[1024];
        int n = stream.read(buf, 0, 1024);

        Assertions.assertEquals(5, n);
        Assertions.assertArrayEquals("hello".getBytes(), java.util.Arrays.copyOf(buf, n));
    }

    @Test
    public void testReadMultipleChunksIntoOneBuf() throws IOException {
        byte[] wire = "3\r\nabc\r\n5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] buf = new byte[1024];
        int n1 = stream.read(buf, 0, 1024);
        Assertions.assertEquals(8, n1);
        Assertions.assertArrayEquals("abchello".getBytes(), java.util.Arrays.copyOf(buf, n1));

        // Next read returns -1 (all data consumed, completed flag set)
        int n2 = stream.read(buf, 0, 1024);
        Assertions.assertEquals(-1, n2);
        Assertions.assertTrue(stream.completed);
    }

    @Test
    public void testReadMultipleChunksSequential() throws IOException {
        byte[] wire = "3\r\nabc\r\n5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] buf = new byte[3];
        int n1 = stream.read(buf, 0, 3);
        Assertions.assertEquals(3, n1);
        Assertions.assertArrayEquals("abc".getBytes(), buf);

        byte[] buf2 = new byte[5];
        int n2 = stream.read(buf2, 0, 5);
        Assertions.assertEquals(5, n2);
        Assertions.assertArrayEquals("hello".getBytes(), buf2);

        // End marker
        byte[] buf3 = new byte[1];
        int n3 = stream.read(buf3, 0, 1);
        Assertions.assertEquals(0, n3);
    }

    @Test
    public void testReadFullBytesReturnsAllChunkedData() throws IOException {
        byte[] wire = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] all = stream.readFullBytes();
        Assertions.assertArrayEquals("hello".getBytes(), all);
    }

    @Test
    public void testReadFullBytesMultipleChunks() throws IOException {
        byte[] wire = "3\r\nabc\r\n2\r\nxy\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] all = stream.readFullBytes();
        Assertions.assertArrayEquals("abcxy".getBytes(), all);
    }

    @Test
    public void testEmptyChunkedBody() throws IOException {
        byte[] wire = "0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] buf = new byte[1024];
        int n = stream.read(buf, 0, 1024);
        Assertions.assertEquals(0, n);
        Assertions.assertTrue(stream.completed);
    }

    @Test
    public void testReadAfterCompletedReturnsMinusOne() throws IOException {
        byte[] wire = "0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        stream.read(new byte[1024], 0, 1024); // exhaust

        int n = stream.read(new byte[1024], 0, 1024);
        Assertions.assertEquals(-1, n);
    }

    @Test
    public void testReadChunkSizeHexDigits() throws Exception {
        // size in hex: 0xa = 10, but only "hello" (5 bytes) in buffer
        // readFully will fail because buffer doesn't have enough data for chunk size=10
        // So this tests error handling via readFully returning -1
        byte[] wire = "a\r\nhello\r\n0\r\n\r\n".getBytes(); // size=10 but only 5 bytes of data
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] buf = new byte[1024];
        // Should throw IllegalStateException("chunk data error") wrapping an IOException
        Assertions.assertThrows(IllegalStateException.class, () -> stream.read(buf, 0, 1024));
        Assertions.assertTrue(stream.completed);
    }

    @Test
    public void testReadEndChunkedWithTrailingCRLF() throws IOException {
        // Normal case: "0\r\n\r\n"
        byte[] wire = "2\r\nab\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] all = stream.readFullBytes();
        Assertions.assertArrayEquals("ab".getBytes(), all);
        Assertions.assertTrue(stream.completed);
    }

    @Test
    public void testChunkedReadExactBufferSize() throws IOException {
        // Read where buffer exactly matches chunk size
        byte[] wire = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);

        byte[] buf = new byte[5];
        int n = stream.read(buf, 0, 5);
        Assertions.assertEquals(5, n);
        Assertions.assertArrayEquals("hello".getBytes(), buf);
    }

    // ==================== readCRLF error paths ====================

    @Test
    public void testReadCRLFWrongCR() {
        // Chunk data followed by 'X' instead of '\r'
        byte[] wire = "3\r\nabcX\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertThrows(IllegalStateException.class,
                () -> stream.read(buf, 0, 1024));
    }

    @Test
    public void testReadCRLFWrongLFAfterCorrectCR() {
        // Chunk data followed by '\r' then 'X' instead of '\n'
        byte[] wire = "3\r\nabc\rX0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertThrows(IllegalStateException.class,
                () -> stream.read(buf, 0, 1024));
    }

    // ==================== readChunkSize error paths ====================

    @Test
    public void testReadChunkSizeNonHexDigit() {
        // 'Z' is not a hex digit
        byte[] wire = "Z\r\nabc\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertThrows(IllegalStateException.class,
                () -> stream.read(buf, 0, 1024));
    }

    @Test
    public void testReadChunkSizeWrongCharAfterCR() {
        // '\r' then 'X' instead of '\n'
        byte[] wire = "3\rXabc\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertThrows(IllegalStateException.class,
                () -> stream.read(buf, 0, 1024));
    }

    // ==================== readEndChunked trailer path ====================

    @Test
    public void testReadEndChunkedWithTrailer() throws IOException {
        // Wire with trailer headers after "0\r\n" instead of "\r\n"
        byte[] wire = "3\r\nabc\r\n0\r\nTrailer: val\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        // Mock ctx.read(ByteBuffer) to avoid IOException (default returns 0)
        when(mockCtx.read(any(ByteBuffer.class))).thenReturn(0);
        byte[] buf = new byte[1024];
        int n = stream.read(buf, 0, 1024);
        Assertions.assertEquals(3, n);
        Assertions.assertArrayEquals("abc".getBytes(), java.util.Arrays.copyOf(buf, n));
    }

    // ==================== complete0() ====================

    @Test
    public void testComplete0AlreadyCompleted() throws IOException {
        byte[] wire = "0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        stream.read(new byte[1], 0, 1); // exhaust → sets completed
        // Calling complete() on already completed stream should be no-op
        stream.complete();
        Assertions.assertTrue(stream.completed);
    }

    @Test
    public void testComplete0DiscardsRemainingData() {
        byte[] wire = "3\r\nabc\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        // Call complete() directly without reading → complete0() discards data
        stream.complete();
        Assertions.assertTrue(stream.completed);
    }

    // ==================== read() throws UnsupportedOperationException ====================

    @Test
    public void testReadSingleByteThrows() {
        byte[] wire = "0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        Assertions.assertThrows(UnsupportedOperationException.class, () -> stream.read());
    }

    // ==================== readCRLF channel closure  ====================

    @Test
    public void testReadCRLFChannelClosure() {
        // Chunk data followed by CR at buffer end, LF requires channel read → -1
        byte[] wire = "3\r\nabc\r".getBytes(); // 7 bytes, CR at end of buffer
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertThrows(IllegalStateException.class,
                () -> stream.read(buf, 0, 1024));
    }

    // ==================== readChunkSize > Integer.MAX_VALUE  ====================

    @Test
    public void testReadChunkSizeTooLarge() {
        // 0x80000000 = 2,147,483,648 > Integer.MAX_VALUE (2,147,483,647)
        byte[] wire = "80000000\r\n0\r\n\r\n".getBytes();
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertThrows(IllegalStateException.class,
                () -> stream.read(buf, 0, 1024));
    }

    // ==================== readEndChunked IOException  ====================

    @Test
    public void testReadEndChunkedIOException() throws IOException {
        // Trailer bytes trigger the try block where ctx.read throws
        byte[] wire = "3\r\nabc\r\n0\r\nXY".getBytes(); // X, Y = non-CRLF trailer
        when(mockCtx.read(any(ByteBuffer.class))).thenAnswer(invocation -> { throw new IOException("simulated"); });
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        byte[] buf = new byte[1024];
        Assertions.assertDoesNotThrow(() -> stream.read(buf, 0, 1024));
        Assertions.assertTrue(stream.completed);
    }

    // ==================== read() body size exceeds limit  ====================
    // Note: BODY_MAX_SIZE now defaults to 536870912 (512MB). This path is only reachable when
    // a single chunked read exceeds BODY_MAX_SIZE; the value is a runtime constant loaded from
    // config (not inlined), so it can be overridden via Unsafe for isolated.

    // ==================== complete0() discard from channel  ====================

    @Test
    public void testComplete0DiscardFromChannel() throws Exception {
        // First chunk fully in buffer, second chunk size in buffer but data needs channel read
        byte[] wire = "3\r\nabc\r\n5\r\n".getBytes(); // chunk size 5, but only chunk size in buffer
        // Mock readFully to return data for discard
        when(mockCtx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] b = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    b[off] = 'Y'; b[off+1] = 'Y';  // data
                    b[off+2] = '\r'; b[off+3] = '\n'; // CRLF
                    return 4; // only 4 bytes available (need 5, but partial is OK for discardBuf)
                });
        HttpChunkedStream stream = new HttpChunkedStream(wire, mockCtx);
        stream.read(new byte[3]); // consume first chunk "abc"
        stream.complete(); // should discard remaining, no exception
        Assertions.assertTrue(stream.completed);
    }
}
