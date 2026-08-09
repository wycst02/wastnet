package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;

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

    // ==================== Boundary not found first time (L214 false, L237) ====================

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

    // ==================== Content ends with \n without \r (L218/220) ====================

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

    // ==================== remaining <= 0 after boundary (L230) ====================

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

    // ==================== Boundary at position 0 after read (L216) ====================

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

    // ==================== contentEnd == 0 (L224 false) ====================

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

    // ==================== Form-urlencoded: chunked exceeds maxSize (L316-317) ====================

    @Test
    public void testFormUrlencodedChunkedExceedsMaxSize() throws Exception {
        // Build a chunked payload > MAX_BODY_IN_MEMORY (now =1 via system property)
        // decodeFormUrlencoded throws, caught by getUrlencodedParameterNames try-catch → empty result
        int payloadSize = HttpConf.MAX_BODY_IN_MEMORY + 1;
        StringBuilder payload = new StringBuilder(payloadSize);
        for (int i = 0; i < payloadSize; ++i) payload.append('x');

        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        io.github.wycst.wastnet.socket.tcp.ChannelContext ctx =
                new io.github.wycst.wastnet.socket.tcp.ChannelContext(ch, 4096);
        io.github.wycst.wastnet.socket.handler.ChannelHandler<Object> handler =
                new io.github.wycst.wastnet.socket.handler.ChannelHandler<Object>() {
                    public void onHandle(io.github.wycst.wastnet.socket.tcp.ChannelContext c, Object msg) {}
                };
        java.lang.reflect.Field f = io.github.wycst.wastnet.socket.tcp.ChannelContext.class
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

    // ==================== Form-urlencoded: chunked read throws IOException (L323-324) ====================

    @Test
    public void testFormUrlencodedChunkedReadThrowsIoException() throws Exception {
        // Chunked stream: pre-read data consumed, next chunk read fails → IOException caught
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        io.github.wycst.wastnet.socket.tcp.ChannelContext ctx =
                new io.github.wycst.wastnet.socket.tcp.ChannelContext(ch, 4096);
        io.github.wycst.wastnet.socket.handler.ChannelHandler<Object> handler =
                new io.github.wycst.wastnet.socket.handler.ChannelHandler<Object>() {
                    public void onHandle(io.github.wycst.wastnet.socket.tcp.ChannelContext c, Object msg) {}
                };
        java.lang.reflect.Field hf = io.github.wycst.wastnet.socket.tcp.ChannelContext.class
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
        Method m = HttpBodyStreamDecoder.class.getDeclaredMethod("doDecodeMultipartFields", byte[].class);
        m.setAccessible(true);
        m.invoke(dec, new Object[]{b});
    }
}
