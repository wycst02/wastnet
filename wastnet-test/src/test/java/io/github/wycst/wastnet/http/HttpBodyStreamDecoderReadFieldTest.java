package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.SocketChannel;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link HttpBodyStreamDecoder}'s readFieldToFile branches.
 * <p>
 * Sets {@code wastnet.http.max-body-in-memory=1} so that BUFFER_SIZE=4096.
 * <b>Must run in isolation</b>:
 * {@code mvn test -pl wastnet-test -am -Dtest="HttpBodyStreamDecoderReadFieldTest" -DfailIfNoTests=false}
 */
public class HttpBodyStreamDecoderReadFieldTest {

    static {
        System.setProperty("wastnet.http.max-body-in-memory", "1");
    }

    /** Build a large field payload: boundary + headers + content + boundary + trailer */
    private static byte[] buildLargeField(int contentSize, String extraHeaders, String trailingData) {
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n");
        if (extraHeaders != null) sb.append(extraHeaders);
        sb.append("\r\n");
        for (int i = 0; i < contentSize; ++i) sb.append('Z');
        sb.append("\r\n--boundary");
        if (trailingData != null) sb.append(trailingData);
        return sb.toString().getBytes();
    }

    // ==================== Basic: boundary found after read ====================

    @Test
    public void testReadFieldToFileBasic() throws Exception {
        // Field > 4096 bytes → initial buffer fills, boundary in stream → readFieldToFile
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(buildLargeField(4500, null, "--\r\n")));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("f", dec.getMultipartField("f").getName());
    }

    // ==================== skipContent = true (fieldName == null) ====================

    @Test
    public void testReadFieldToFileSkipContent() throws Exception {
        // Content-Disposition without name= → fieldName=null → skipContent=true
        // Must have CRLFCRLF to be parsed (empty headers won't find it)
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data\r\n\r\n");
        for (int i = 0; i < 4500; ++i) sb.append('Z');
        sb.append("\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // ==================== Boundary not found first time ( false, ) ====================

    @Test
    public void testReadFieldToFileBoundaryNotFoundFirst() throws Exception {
        // First read chunk doesn't contain boundary → boundaryPos=-1 → loop continues
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 5000; ++i) sb.append('Z');
        sb.append("\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("f", dec.getMultipartField("f").getName());
    }

    // ==================== Content ends with \n without \r (/220) ====================

    @Test
    public void testReadFieldToFileContentEndsWithNewlineOnly() throws Exception {
        // Content ends with \n (no \r) before boundary in readFieldToFile
        // Content: ZZZ...ZZZ\n--boundary
        // Need to construct data so the content portion before boundary has \n as last char
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 4498; ++i) sb.append('Z');
        sb.append("\n--boundary--\r\n"); // \n before boundary, no \r
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    // ==================== remaining <= 0 after boundary  ====================

    @Test
    public void testReadFieldToFileRemainingZero() throws Exception {
        // Boundary at exact end of data → remaining = 0
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 4500; ++i) sb.append('Z');
        sb.append("--boundary"); // boundary at exact end, no trailing data
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    // ==================== Boundary at position 0 after read  ====================

    @Test
    public void testReadFieldToFileBoundaryPosZero() throws Exception {
        // After tail reserve copy and new read, boundary starts at position 0
        // This happens when content fills the buffer + tail reserve exactly
        // Strategy: content size = 4096 - headers(50) - tailReserve(12) = 4034
        // Then boundary starts at position 0 in the buffer after tail reserve copy
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 4030; ++i) sb.append('Z');
        sb.append("\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
    }

    // ==================== contentEnd == 0 ( false) ====================

    @Test
    public void testReadFieldToFileNoContent() throws Exception {
        // Field with only content before boundary but boundaryPos = 0
        // This means the content to write is empty → contentEnd = 0
        // Strategy: after tail reserve copy, the first bytes in buffer are boundary
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\n");
        for (int i = 0; i < 5000; ++i) sb.append('Z');
        sb.append("\r\n--boundary\r\nContent-Disposition: form-data; name=\"g\"\r\n\r\nval\r\n--boundary--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary",
                new ByteArrayInputStream(sb.toString().getBytes()));
        invokeDecode(dec, "--boundary".getBytes());
        assertNotNull(dec.getMultipartField("f"));
        assertEquals("val", dec.getMultipartFieldValue("g"));
    }

    // ==================== Form-urlencoded: chunked exceeds maxSize  ====================

    @Test
    public void testFormUrlencodedChunkedExceedsMaxSize() throws Exception {
        // Build a chunked payload > MAX_BODY_IN_MEMORY (now =1 via system property)
        // decodeFormUrlencoded throws, caught by getUrlencodedParameterNames try-catch → empty result
        int payloadSize = HttpConf.MAX_BODY_IN_MEMORY + 1;
        StringBuilder payload = new StringBuilder(payloadSize);
        for (int i = 0; i < payloadSize; ++i) payload.append('x');

        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx =
                new ChannelContext(ch, 4096);
        io.github.wycst.wastnet.socket.handler.ChannelHandler<Object> handler =
                new io.github.wycst.wastnet.socket.handler.ChannelHandler<Object>() {
                    public void onHandle(ChannelContext c, Object msg) {}
                };
        java.lang.reflect.Field f = ChannelContext.class
                .getDeclaredField("channelHandler");
        f.setAccessible(true);
        f.set(ctx, handler);

        String hexLen = Integer.toHexString(payloadSize);
        String chunkedWire = hexLen + "\r\n" + payload.toString() + "\r\n0\r\n\r\n";
        HttpChunkedStream chunkedStream = new HttpChunkedStream(chunkedWire.getBytes(), ctx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);

        assertThrows(IllegalStateException.class,
                () -> decoder.getUrlencodedParameterNames());
        ch.close();
    }

    // ==================== Form-urlencoded: chunked read throws IOException  ====================

    @Test
    public void testFormUrlencodedChunkedReadThrowsIoException() throws Exception {
        // Chunked stream: pre-read data consumed, next chunk read fails → IOException caught
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx =
                new ChannelContext(ch, 4096);
        io.github.wycst.wastnet.socket.handler.ChannelHandler<Object> handler =
                new io.github.wycst.wastnet.socket.handler.ChannelHandler<Object>() {
                    public void onHandle(ChannelContext c, Object msg) {}
                };
        java.lang.reflect.Field hf = ChannelContext.class
                .getDeclaredField("channelHandler");
        hf.setAccessible(true);
        hf.set(ctx, handler);

        // Pre-read chunk data, close channel so next read fails with IOException
        String preChunk = "5\r\nhello\r\n";
        ch.close();
        HttpChunkedStream chunkedStream = new HttpChunkedStream(preChunk.getBytes(), ctx);
        HttpBodyStreamDecoder decoder = new HttpBodyStreamDecoder(
                "application/x-www-form-urlencoded", chunkedStream);

        assertThrows(IllegalStateException.class,
                () -> decoder.getUrlencodedParameterNames());
    }

    private static void invokeDecode(HttpBodyStreamDecoder dec, byte[] b) throws Exception {
        // doDecodeMultipartFields is protected (same package), callable directly
        dec.doDecodeMultipartFields(b);
    }

    // ==================== Large-field coverage for readFieldToFile ====================
    // bufferSize = max(8192, MAX_BODY_IN_MEMORY); with MAX_BODY_IN_MEMORY=1 it is 8192,
    // so a field with content > ~8120 bytes cannot fit in the initial buffer and
    // doDecodeMultipartFields delegates to readFieldToFile (boundary not in buffer).

    private static byte[] buildField(int contentSize, String disposition, String trailing) {
        StringBuilder sb = new StringBuilder();
        sb.append("--boundary\r\n").append(disposition).append("\r\n\r\n");
        for (int i = 0; i < contentSize; ++i) sb.append('Z');
        sb.append("\r\n--boundary");
        if (trailing != null) sb.append(trailing);
        return sb.toString().getBytes();
    }

    private static ChannelContext newCtx(boolean enableTempFile) throws IOException {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        NioConfig cfg = new NioConfig().option(HttpOptions.MAX_BODY_IN_MEMORY, 1);
        if (!enableTempFile) {
            cfg.option(HttpOptions.ENABLE_TEMP_FILE, false);
        }
        ctx.attachNioConfig(cfg);
        return ctx;
    }

    /** First read fills the buffer (parses headers); the 2nd read (inside readFieldToFile, after the
     *  temp file is created) throws, so the finally block must delete the temp file (L274). */
    static class FailingInputStream extends InputStream {
        private final byte[] data;
        private int pos;
        private int calls;
        FailingInputStream(byte[] data) { this.data = data; }
        @Override public int read() {
            if (calls >= 1) throw new UncheckedIOException("forced read failure", new IOException("forced"));
            if (pos >= data.length) return -1;
            int v = data[pos++] & 0xFF;
            calls++;
            return v;
        }
        @Override public int read(byte[] b, int off, int len) {
            if (calls >= 1) throw new UncheckedIOException("forced read failure", new IOException("forced"));
            int max = calls == 0 ? (data.length - pos) : Math.min(8, data.length - pos);
            int n = Math.min(len, max);
            if (n <= 0) return -1;
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            calls++;
            return n;
        }
    }

    // skipContent = true via fieldName == null (large unnamed field reaches readFieldToFile)
    @Test
    public void testReadFieldToFileSkipContentUnnamedLarge() throws Exception {
        byte[] body = buildField(9000, "Content-Disposition: form-data", "--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(body), newCtx(true));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // skipContent = true via !ENABLE_TEMP_FILE (large named field, option disabled)
    @Test
    public void testReadFieldToFileSkipContentTempFileDisabled() throws Exception {
        byte[] body = buildField(9000, "Content-Disposition: form-data; name=\"f\"", "--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(body), newCtx(false));
        invokeDecode(dec, "--boundary".getBytes());
        assertTrue(dec.getMultipartFieldNames().isEmpty());
    }

    // read throws after temp file created -> finally deletes temp file (failure cleanup / L274)
    @Test
    public void testReadFieldToFileFailureCleanup() throws Exception {
        byte[] body = buildField(9000, "Content-Disposition: form-data; name=\"f\"", "--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new FailingInputStream(body), newCtx(true));
        assertThrows(UncheckedIOException.class, () -> invokeDecode(dec, "--boundary".getBytes()));
        assertNull(dec.getMultipartField("f"));
    }

    // successful large named field -> content streamed to temp file, MultipartFieldFile returned
    /** Returns at most {@code chunk} bytes per read so the boundary is found only across multiple reads. */
    static class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int chunk;
        private int pos;
        ChunkedInputStream(byte[] data, int chunk) { this.data = data; this.chunk = chunk; }
        @Override public int read() { return pos < data.length ? data[pos++] & 0xFF : -1; }
        @Override public int read(byte[] b, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(len, Math.min(chunk, data.length - pos));
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    // successful large named field -> content streamed to temp file, MultipartFieldFile returned
    // (covers L240 true, L242 true, L266 false)
    @Test
    public void testReadFieldToFileSuccessNamedLarge() throws Exception {
        byte[] body = buildField(9000, "Content-Disposition: form-data; name=\"f\"", "--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ByteArrayInputStream(body), newCtx(true));
        invokeDecode(dec, "--boundary".getBytes());
        MultipartField field = dec.getMultipartField("f");
        assertNotNull(field);
        assertTrue(field.isTempFile());
        assertEquals(9000L, field.size());
        // verify content integrity (all 'Z'), JDK8-safe
        int total = 0, b;
        try (InputStream in = field.getInputStream()) {
            while ((b = in.read()) != -1) {
                assertEquals('Z', b);
                ++total;
            }
        }
        assertEquals(9000, total);
        dec.release();
    }

    // boundary not found within a single internal read -> loop continues (cross-buffer / L240 false)
    @Test
    public void testReadFieldToFileBoundaryCrossBuffer() throws Exception {
        byte[] body = buildField(9000, "Content-Disposition: form-data; name=\"f\"", "--\r\n");
        HttpBodyStreamDecoder dec = new HttpBodyStreamDecoder(
                "multipart/form-data; boundary=boundary", new ChunkedInputStream(body, 500), newCtx(true));
        invokeDecode(dec, "--boundary".getBytes());
        MultipartField field = dec.getMultipartField("f");
        assertNotNull(field);
        assertTrue(field.isTempFile());
        assertEquals(9000L, field.size());
        dec.release();
    }
}
