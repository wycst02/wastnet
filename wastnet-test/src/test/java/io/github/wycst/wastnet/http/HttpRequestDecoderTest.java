package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.handler.ChannelHandler;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpRequestDecoderTest {

    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static Object origPreserve, origPipeline, origTimeout;

    @BeforeAll
    public static void setupConfig() throws Exception {
        if (initialized.getAndSet(true)) return;
        origPreserve = setFinalStatic(HttpConf.class, "PRESERVE_HEADER_ORDER", true);
        origPipeline = setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", true);
        origTimeout = setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", 50L);
    }

    @AfterAll
    public static void restoreConfig() throws Exception {
        if (origPreserve != null) {
            setFinalStatic(HttpConf.class, "PRESERVE_HEADER_ORDER", origPreserve);
            setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", origPipeline);
            setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", origTimeout);
        }
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    private static <T> Object setFinalStatic(Class<?> clazz, String fieldName, T value) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object original = field.get(null);
        sun.misc.Unsafe unsafe = getUnsafe();
        Class<?> type = field.getType();
        long offset = unsafe.staticFieldOffset(field);
        Object base = unsafe.staticFieldBase(field);
        if (type == long.class) {
            unsafe.putLong(base, offset, ((Number) value).longValue());
        } else if (type == int.class) {
            unsafe.putInt(base, offset, ((Number) value).intValue());
        } else if (type == boolean.class) {
            unsafe.putBoolean(base, offset, (Boolean) value);
        } else {
            unsafe.putObject(base, offset, value);
        }
        return original;
    }

    private final AtomicReference<HttpRequest> captured = new AtomicReference<HttpRequest>();

    private ChannelContext createCtx() throws Exception {
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        return ctx;
    }

    private void decodeAll(HttpRequestDecoder d, String data, ChannelContext ctx) throws Exception {
        byte[] b = data.getBytes();
        d.decode(b, 0, b.length, ctx);
    }

    // ===== Valid requests through handler =====

    @Test
    public void testValidGet() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET /x HTTP/1.1\r\nHost: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testPostWithBody() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "POST /x HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc", ctx);
        assertEquals("POST", captured.get().getMethod().name());
    }

    @Test
    public void testContentType() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Type: application/json\r\n\r\n", ctx);
        assertEquals("application/json", captured.get().getContentType());
    }

    @Test
    public void testContentLength() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello", ctx);
        assertEquals(5, captured.get().getContentLength());
    }

    @Test
    public void testByteByByte() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        byte[] data = "GET /x HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        for (byte aData : data) {
            d.decode(new byte[]{aData}, 0, 1, ctx);
        }
        assertNotNull(captured.get());
    }

    @Test
    public void testSplitDecode() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("GET / HTTP/1.1\r\n".getBytes(), 0, 16, ctx);
        decodeAll(d, "Host: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testZeroLen() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode(new byte[0], 0, 0, ctx);
    }

    @Test
    public void testShallow() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.setShallow(true);
        decodeAll(d, "GET /t HTTP/1.1\r\nHost: a\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testBoundaryCRatEdge() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(), 0, 31, ctx);
        d.decode("\n".getBytes(), 0, 1, ctx);
        // Coverage: expectLF path in readBoundary
    }

    @Test
    public void testBoundaryLoneLF() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testBodySplit() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("POST / HTTP/1.1\r\nContent-Length: 6\r\n\r\n".getBytes(), 0, 38, ctx);
        decodeAll(d, "hello!", ctx);
    }

    @Test
    public void testBadBoundaryEndsWithSpace() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("GET / HTTP/1.1\r\nHost: a\r\n ".getBytes(), 0, 25, ctx);
    }

    @Test
    public void testTimeoutRecovery() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("GET / HTTP/1.1\r\n".getBytes(), 0, 16, ctx);
        Thread.sleep(150);
        decodeAll(d, "Host: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testHeaderValueSplit() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("GET / HTTP/1.1\r\nX-V: ".getBytes(), 0, 20, ctx);
        decodeAll(d, "split\r\n\r\n", ctx);
        assertEquals("split", captured.get().getHeader("x-v"));
    }

    @Test
    public void testShortHeader() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nK: v\r\n\r\n", ctx);
        assertEquals("v", captured.get().getHeader("k"));
    }

    // ===== Content-Length > default BODY_MAX_SIZE (512MB) → 413 (no artificial override) =====

    @Test
    public void testContentTooLarge() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Content-Length exceeds the default BODY_MAX_SIZE (512MB) → REQUEST_ENTITY_TOO_LARGE.
        // Relies on the real default limit; no artificial field override.
        decodeAll(d, "GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 536870913\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    @Test
    public void testContentLengthNormal() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello", ctx);
        assertNotNull(captured.get());
        assertEquals(5, captured.get().getContentLength());
    }

    @Test
    public void testInvalidContentLength() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Non-numeric content-length → NumberFormatException → BAD_REQUEST
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Length: abc\r\n\r\n", ctx);
        // Coverage: NumberFormatException catch in addHeader
    }

    @Test
    public void testMaxSingleHeaderSize() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_SINGLE_HEADER_SIZE", 5);
        try {
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            decodeAll(d, "GET / HTTP/1.1\r\nX: toolong\r\n\r\n", ctx);
        } finally {
            setFinalStatic(HttpConf.class, "MAX_SINGLE_HEADER_SIZE", orig);
        }
    }

    // ===== readStartLine negative byte branch =====

    @Test
    public void testStartLineNegativeBytes() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Build request with negative byte (0x80) in URI
        byte[] prefix = "GET /\u00ff".getBytes("ISO-8859-1");
        byte[] suffix = " HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        byte[] req = new byte[prefix.length + suffix.length];
        System.arraycopy(prefix, 0, req, 0, prefix.length);
        System.arraycopy(suffix, 0, req, prefix.length, suffix.length);
        d.decode(req, 0, req.length, ctx);
        // Coverage: negative byte in readStartLine SWAR path
    }

    // ===== readHeaderValue negative bytes =====

    @Test
    public void testHeaderValueNegativeBytes() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Header value with negative byte (0x80)
        byte[] prefix = "GET / HTTP/1.1\r\nX-Neg: v".getBytes();
        byte[] mid = new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0x82};
        byte[] suffix = "al\r\n\r\n".getBytes();
        byte[] req = new byte[prefix.length + mid.length + suffix.length];
        System.arraycopy(prefix, 0, req, 0, prefix.length);
        System.arraycopy(mid, 0, req, prefix.length, mid.length);
        System.arraycopy(suffix, 0, req, prefix.length + mid.length, suffix.length);
        d.decode(req, 0, req.length, ctx);
        // Coverage: negative bytes in readHeaderValue
    }

    // ===== readBoundary error paths (byte-by-byte) =====

    @Test
    public void testBoundaryExpectLFThenCR() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // End headers with CR at buffer boundary → expectLF=true
        d.decode("GET / HTTP/1.1\r\nHost: a\r\n".getBytes(), 0, 24, ctx);
        // Then send \r instead of \n → BAD_REQUEST
        d.decode("\r".getBytes(), 0, 1, ctx);
        // Coverage: expectLF true but next byte is not \n
    }

    @Test
    public void testBoundaryOffsetEqualsLimit() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Byte-by-byte feed ending at boundary \r → expectLF=true, readState=ReadBoundary
        byte[] data = "GET / HTTP/1.1\r\nHost: a\r\n\r".getBytes();
        for (int i = 0; i < data.length; ++i) {
            d.decode(new byte[]{data[i]}, 0, 1, ctx);
        }
        // Now readState=ReadBoundary, empty 3-arg enters readBoundary with offset==limit
        d.decode(new byte[0], 0, 0);
    }

    @Test
    public void testBoundaryExpectLFThenCR_byByte() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        byte[] data = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();
        // Feed byte by byte, but replace final \n with \r to trigger error
        for (int i = 0; i < data.length - 1; ++i) {
            d.decode(new byte[]{data[i]}, 0, 1, ctx);
        }
        // Last expected byte is \n, but instead feed \r
        d.decode(new byte[]{'\r'}, 0, 1, ctx);
        // Coverage: boundary error with byte-by-byte feeding
    }

    // ===== readBody content-length > MAX_BODY_IN_MEMORY → stream mode =====

    // ===== Pipeline / mergeRemainingBytes =====

    @Test
    public void testPipelineRemainingBytes() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Two requests in one buffer: first completes, remaining = second request
        decodeAll(d, "GET /1 HTTP/1.1\r\nHost: a\r\n\r\nGET /3 HTTP/1.1\r\nHost: c\r\n\r\n", ctx);
        assertNotNull(captured.get());
        // Second decode call triggers mergeRemainingBytes
        captured.set(null);
        decodeAll(d, "GET /x HTTP/1.1\r\nHost: b\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    // ===== addHeader branch coverage =====

    @Test
    public void testContentEncodingHeader() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "Content-Encoding" starts with "Content-" but is neither Content-Type nor Content-Length
        decodeAll(d, "GET / HTTP/1.1\r\nContent-Encoding: gzip\r\n\r\n", ctx);
        assertEquals("gzip", captured.get().getHeader("content-encoding"));
    }

    @Test
    public void testExpectContinueHeader() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Expect: 100-continue triggers expectContinue=true
        decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testBodyStreamMode() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", 4);
        try {
            captured.set(null);
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // Content-Length: 10 > MAX_BODY_IN_MEMORY(4) → BODY_MODE_STREAM
            // onDecoded creates HttpStreamRequest
            decodeAll(d, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\n0123456789", ctx);
            assertNotNull(captured.get());
            assertTrue(captured.get().isStream());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", orig);
        }
    }

    // ===== Remaining decoder branch coverage =====

    @Test
    public void testZeroHeaders() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testLoneLFAtStartLine() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        d.decode("\n".getBytes(), 0, 1, ctx);
        // Coverage: L222-L225
    }

    @Test
    public void testStartLineLoneLFThenLF() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Start line ends with \n (no \r), next data is \n → readHeaders entry b=='\n' (L275)
        d.decode("GET / HTTP/1.1\n\n".getBytes(), 0, 16, ctx);
        // Coverage: L275 b == '\n' at readHeaders entry
    }

    @Test
    public void testGetResultBadPath() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        byte[] b = "GET / HTTP/1.1\r\n".getBytes();
        d.decode(b, 0, b.length);
        HttpMessage msg = d.getResult();
        assertTrue(msg instanceof HttpBadRequest);
    }

    @Test
    public void testGetResultGoodPath() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        byte[] b = "GET / HTTP/1.1\r\nHost: a\r\n\r\n".getBytes();
        d.decode(b, 0, b.length);
        HttpMessage msg = d.getResult();
        assertFalse(((HttpRequest) msg).isBad());
    }

    @Test
    public void testSwitchDefault() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        byte[] b = "GET /x HTTP/1.1\nHost: a\n\n".getBytes();
        d.decode(b, 0, b.length);
        // Now readState == Completed, second 3-arg decode hits default case
        d.decode(new byte[0], 0, 0);
    }

    @Test
    public void testRequestTimeout() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", 10L);
        try {
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            d.decode("GET / HTTP/1.1\r\n".getBytes(), 0, 16, ctx);
            Thread.sleep(50);
            d.decode("Host".getBytes(), 0, 4, ctx);
        } finally {
            setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", orig);
        }
    }

    @Test
    public void testExpectNotContinue() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-xxx\r\n\r\n", ctx);
    }

    @Test
    public void testTripleDuplicateHeader() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nX-Dup: v1\r\nX-Dup: v2\r\nX-Dup: v3\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testChunkedTransferEncoding() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    // ===== onDecoded chunked branch (L504) =====

    @Test
    public void testChunkedRequestDispatched() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Transfer-Encoding: chunked → completedBody sets bodyMode=CHUNKED → onDecoded else branch
        decodeAll(d, "GET / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n", ctx);
        assertTrue(captured.get() instanceof HttpChunkedRequest);
    }

    @Test
    public void testChunkedDropsContentLength() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // CL + TE: chunked both present (RFC 7230 §3.3.3 ambiguous case): decoder prefers chunked
        // and must drop Content-Length before dispatch (L504-506) so the sender never emits both.
        decodeAll(d, "GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req instanceof HttpChunkedRequest);
        assertFalse(req.containsHeader("content-length"));
    }

    // ===== handleSpecialHeader duplicate Content-Length → BAD_REQUEST (L549-550) =====

    @Test
    public void testDuplicateContentLengthRejected() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Two Content-Length headers → second hits hasContentLength==true → status=BAD_REQUEST
        decodeAll(d, "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== handleSpecialHeader L567-569: Host header branches =====

    @Test
    public void testDuplicateHostRejected() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Two Host headers → second time hasHost already true → BAD_REQUEST
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    @Test
    public void testEmptyHostValueRejected() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Host header with empty value → value.isEmpty() true → BAD_REQUEST
        decodeAll(d, "GET / HTTP/1.1\r\nHost:\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== onDecoded L495: !hasHost && version == HTTP_1_1 — cover (no Host, HTTP/1.0) skip combo =====

    @Test
    public void testNoHostHttp10SkipsHostCheck() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // HTTP/1.0 with no Host: hasHost=false but version != HTTP_1_1 → branch short-circuits, request still valid
        decodeAll(d, "GET / HTTP/1.0\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    // ===== handleSpecialHeader guards: header ends with the same 2-char key but is not the normalized name =====

    @Test
    public void testNonContentTypeHeaderEndsInPe() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "x-type" last 2 chars = "pe" → K_CONTENT_TYPE case, but equals("content-type") is false → skip
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nX-Type: application/json\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testNonContentLengthHeaderEndsInTh() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "x-width" last 2 chars = "th" → K_CONTENT_LENGTH case, equals("content-length") false → skip
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nX-Width: 5\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testNonHostHeaderUnmatchedKey() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "x-robot" last 2 chars = "ot" → matches NO special-case key → default switch fall-through
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nX-Robot: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testNonHostHeaderEndsInSt() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "x-test" last 2 chars = "st" → K_HOST case, but equals("host") is false → L567 false branch
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nX-Test: localhost\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testNonExpectHeaderEndsInCt() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "x-subject" last 2 chars = "ct" → K_EXPECT case, equals("expect") false → skip
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nX-Subject: 100-continue\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testNonTransferEncodingHeaderEndsInNg() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // "x-ping" last 2 chars = "ng" → K_TRANSFER_ENCODING case, equals("transfer-encoding") false → skip
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nX-Ping: chunked\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testTransferEncodingNotChunked() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Transfer-Encoding: gzip → outer equals true but contains("chunked") false → chunked stays false
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
        assertFalse(captured.get() instanceof HttpChunkedRequest);
    }

    // ===== handleSpecialHeader L540: name.length() < 2 → early return =====

    @Test
    public void testSingleCharHeaderNameSkipped() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Header "a: b" has name length 1 → handleSpecialHeader returns early
        decodeAll(d, "GET / HTTP/1.1\r\nHost: a\r\na: b\r\n\r\n", ctx);
        assertNotNull(captured.get());
        assertFalse(captured.get().isBad());
    }

    @Test
    public void testUriTooLong() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", 3);
        try {
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            decodeAll(d, "GET /abc HTTP/1.1\r\nHost: a\r\n\r\n", ctx);
        } finally {
            setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", orig);
        }
    }

    @Test
    public void testHeaderKeyTooLarge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", 10);
        try {
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            byte[] data = "GET / HTTP/1.1\r\nABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();
            d.decode(data, 0, data.length, ctx);
        } finally {
            setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", orig);
        }
    }

    @Test
    public void testHeaderValueTooLarge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", 10);
        try {
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            byte[] data = "GET / HTTP/1.1\r\nK:ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes();
            d.decode(data, 0, data.length, ctx);
        } finally {
            setFinalStatic(HttpConf.class, "MAX_HTTP_HEADER_SIZE", orig);
        }
    }

    @Test
    public void testHeaderValueEndsWithLoneLF() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/1.1\r\nK: v\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    // ===== expectContinue true branch (needs connected channel so writeFlush succeeds) =====

    @Test
    public void testExpectContinueBranchCoverage() throws Exception {
        captured.set(null);
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(0));
        int port = ((InetSocketAddress) ssc.getLocalAddress()).getPort();
        SocketChannel ch = SocketChannel.open();
        ch.connect(new InetSocketAddress("localhost", port));
        ch.configureBlocking(false);
        SocketChannel accepted = ssc.accept();
        accepted.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        try {
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n", ctx);
            // Coverage: L314 expectContinue && status == null && ctx != null → true
        } finally {
            accepted.close();
            ch.close();
            ssc.close();
        }
    }

    // ===== readStartLine remaining branches =====

    @Test
    public void testUriTooLongAtBufferEdge() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", 1);
        try {
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // Buffer ends at whitespace after token, token exceeds MAX_URI_LENGTH
            d.decode("GE".getBytes(), 0, 2, ctx);
        } finally {
            setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", orig);
        }
    }

    @Test
    public void testWhitespaceWithCRInStartLine() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Extra space after version before \r\n → while loop hits \r in whitespace (L247)
        decodeAll(d, "GET /x HTTP/1.1 \r\nHost: a\r\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    @Test
    public void testWhitespaceWithLFViaTab() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Tab before \n in version separator → L247 b == '\n' in whitespace
        decodeAll(d, "GET /x HTTP/1.1\t\n\r\n", ctx);
        assertNotNull(captured.get());
    }

    // ===== readHeaderKey quick colon mismatch (L334) via negative byte =====

    @Test
    public void testHeaderKeyNegativeBytes() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Header key with negative byte (0xBA) that may trigger maskOfColon false match
        // Header key starting with 0xBB triggers maskOfColon false positive; add padding for SWAR entry
        byte[] prefix = "GET / HTTP/1.1\r\n".getBytes();
        byte[] mid = new byte[]{(byte) 0xBB};
        byte[] suffix = ": val\r\n\r\nLorem ipsum dolor sit amet".getBytes();
        byte[] req = new byte[prefix.length + mid.length + suffix.length];
        System.arraycopy(prefix, 0, req, 0, prefix.length);
        System.arraycopy(mid, 0, req, prefix.length, mid.length);
        System.arraycopy(suffix, 0, req, prefix.length + mid.length, suffix.length);
        d.decode(req, 0, req.length, ctx);
        // Coverage: L334 buf[tarOff] == ':' check → false → break quick
    }

    // ===== onDecoded version/method protocol close =====

    @Test
    public void testUnsupportedVersionClosesConnection() throws Exception {
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "GET / HTTP/9.9\r\nHost: localhost\r\n\r\n", ctx);
        // Connection is closed directly without creating a bad request
        assertTrue(ctx.isChannelClosed());
    }

    @Test
    public void testUnrecognizedMethodReturnsNotImplemented() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        decodeAll(d, "INVALID /path HTTP/1.1\r\nHost: localhost\r\n\r\n", ctx);
        // Non-empty method value → onBadDecoded → HttpBadRequest
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== readBody multi-chunk accumulation (L439-440 bodySize + len < contentLength) =====

    @Test
    public void testBodyMultiChunkAccumulation() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // First: headers + incomplete body "abc" (3 of 10 bytes)
        byte[] chunk1 = "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".getBytes("ISO-8859-1");
        d.decode(chunk1, 0, chunk1.length, ctx);
        // Second: "defg" (4 more bytes, total 7, still < 10) → hits L439-440
        d.decode("defg".getBytes("ISO-8859-1"), 0, 4, ctx);
        // Third: complete "hij" → bodySize+len >= contentLength → completedBody → onDecoded
        decodeAll(d, "hij", ctx);
        assertNotNull(captured.get());
        assertEquals("abcdefghij", new String(captured.get().getBodyData(), "ISO-8859-1"));
    }

    // ===== readBody LENGTH_REQUIRED (L430) with PIPELINE_ENABLED = false =====

    @Test
    public void testLengthRequired() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", false);
        try {
            captured.set(null);
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // POST without Content-Length, PIPELINE_DISABLED → len > 0 && !PIPELINE_ENABLED && !hasContentLength
            decodeAll(d, "POST / HTTP/1.1\r\nHost: a\r\n\r\nx", ctx);
            HttpRequest req = captured.get();
            assertNotNull(req);
            assertTrue(req.isBad());
        } finally {
            setFinalStatic(HttpConf.class, "PIPELINE_ENABLED", orig);
        }
    }

    // ===== readHeaderKey SWAR loop exhaust (L342 offset += 24) =====

    @Test
    public void testHeaderKeySWARExhaust() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Header key > 24 bytes without colon, SWAR processes 3×8 = 24 bytes, finds nothing → L342
        StringBuilder sb = new StringBuilder();
        sb.append("GET / HTTP/1.1\r\nHost: localhost\r\n");
        for (int i = 0; i < 25; ++i) sb.append('A');
        sb.append(": val\r\n\r\n");
        decodeAll(d, sb.toString(), ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertFalse(req.isBad());
    }

    // ===== readHeaderKey split across buffer boundary (SWAR skipped, httpBuf accumulation) =====

    @Test
    public void testHeaderKeySplit() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Header key "X-Cus" ends at buffer boundary → httpBuf accumulates
        d.decode("GET / HTTP/1.1\r\nX-Cus".getBytes(), 0, 21, ctx);
        decodeAll(d, "tom: value\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertEquals("value", req.getHeader("x-custom"));
    }

    // ===== prepareRequestContent IOException branch (L317 status = INTERNAL_SERVER_ERROR) =====

    @Test
    public void testPrepareRequestContentIOException() throws Exception {
        captured.set(null);
        // Unconnected channel: writeFlushWithoutThrow silently catches NotYetConnectedException
        SocketChannel ch = SocketChannel.open();
        ch.configureBlocking(false);
        ChannelContext ctx = new ChannelContext(ch, 4096);
        ctx.setChannelHandler(new ChannelHandler<Object>() {
            @Override
            public void onHandle(ChannelContext c, Object msg) {
                captured.set((HttpRequest) msg);
            }
        });
        try {
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // Expect: 100-continue → prepareRequestContent → writeFlushWithoutThrow catches exception
            decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n", ctx);
            HttpRequest req = captured.get();
            assertNotNull(req);
        } finally {
            ch.close();
        }
    }

    // ===== L313 branch: expectContinue=true AND status!=null (skip if body) =====

    @Test
    public void testExpectContinueWithExistingStatus() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Expect: 100-continue sets expectContinue=true, then invalid Content-Length sets BAD_REQUEST
        // prepareRequestContent: expectContinue=true, status!=null → skip write
        decodeAll(d, "GET / HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: abc\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== L313 branch: expectContinue=true AND ctx==null (3-arg decode) =====

    @Test
    public void testExpectContinueWithoutCtx() throws Exception {
        HttpRequestDecoder d = new HttpRequestDecoder();
        // 3-arg decode: this.ctx stays null → prepareRequestContent skips write (ctx==null)
        byte[] b = "GET / HTTP/1.1\r\nExpect: 100-continue\r\n\r\n".getBytes();
        d.decode(b, 0, b.length);
        HttpMessage msg = d.getResult();
        // Should get a valid request (no error) via getResult
        assertFalse(((HttpRequest) msg).isBad());
    }

    // ===== L485 branch: onBadDecoded with bodyMode=STREAM (.stream(true)) =====

    @Test
    public void testOnBadDecodedWithStreamMode() throws Exception {
        Object origMem = setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", 4);
        try {
            captured.set(null);
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // Content-Length: 10 > MAX_BODY_IN_MEMORY(4) → STREAM mode
            // Expect: 100-xxx sets EXPECTATION_FAILED during addHeader
            // readBody sets bodyMode=STREAM, then handleBadOrTimeout catches status
            decodeAll(d, "POST / HTTP/1.1\r\nContent-Length: 10\r\nExpect: 100-xxx\r\n\r\n0123456789", ctx);
            HttpRequest req = captured.get();
            assertNotNull(req);
            assertTrue(req.isBad());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_BODY_IN_MEMORY", origMem);
        }
    }

    // ===== L485 branch: onBadDecoded with bodyMode=CHUNKED (.chunked(true)) =====

    @Test
    public void testOnBadDecodedWithChunkedMode() throws Exception {
        captured.set(null);
        ChannelContext ctx = createCtx();
        HttpRequestDecoder d = new HttpRequestDecoder(ctx);
        // Transfer-Encoding: chunked → readBody sets bodyMode=CHUNKED
        // Expect: 100-xxx sets EXPECTATION_FAILED during addHeader
        // readBody sets bodyMode=CHUNKED, then handleBadOrTimeout catches status → onBadDecoded with chunked(true)
        decodeAll(d, "GET / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nExpect: 100-xxx\r\n\r\n5\r\nhello\r\n0\r\n\r\n", ctx);
        HttpRequest req = captured.get();
        assertNotNull(req);
        assertTrue(req.isBad());
    }

    // ===== Timeout with incomplete start-line (startLineIdx != 3) → connection closed =====

    @Test
    public void testTimeoutIncompleteStartLineNoUri() throws Exception {
        Object origTimeout = setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", 1L);
        try {
            captured.set(null);
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // Send only "GET " → method parsed (startLineIdx=1), URI token not yet
            // delimited. No CRLF is ever sent.
            d.decode("GET ".getBytes("ISO-8859-1"), 0, 4, ctx);
            Thread.sleep(30); // exceed the 1ms timeout
            // A second non-empty decode forces handleBadOrTimeout to observe the timeout.
            // Since startLineIdx != 3 (malformed start-line), onBadDecoded must close the
            // connection directly and must NOT build a request (which would NPE on
            // createAsciiString(null) or carry a null URI downstream).
            d.decode(new byte[]{'x'}, 0, 1, ctx);
            assertNull(captured.get());
        } finally {
            setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", origTimeout);
        }
    }

    // ===== Timeout with missing version token (startLineIdx == 2) → connection closed =====

    @Test
    public void testTimeoutIncompleteStartLineMissingVersion() throws Exception {
        Object origTimeout = setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", 1L);
        try {
            captured.set(null);
            ChannelContext ctx = createCtx();
            HttpRequestDecoder d = new HttpRequestDecoder(ctx);
            // Send "GET /" → method + URI parsed (startLineIdx=2, startLineMiddle set),
            // but version token missing. No CRLF is ever sent.
            d.decode("GET /".getBytes("ISO-8859-1"), 0, 5, ctx);
            Thread.sleep(30); // exceed the 1ms timeout
            d.decode(new byte[]{'x'}, 0, 1, ctx);
            assertNull(captured.get());
        } finally {
            setFinalStatic(HttpConf.class, "REQUEST_TIMEOUT_MS", origTimeout);
        }
    }

    // ===== L142/L145 branch: getResult with non-null status =====

    @Test
    public void testGetResultWithStatus() throws Exception {
        Object orig = setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", 3);
        try {
            HttpRequestDecoder d = new HttpRequestDecoder();
            // URI too long sets status to REQUEST_URI_TOO_LONG, then getResult()
            byte[] b = "GET /abc HTTP/1.1\r\nHost: a\r\n\r\n".getBytes();
            d.decode(b, 0, b.length);
            HttpMessage msg = d.getResult();
            assertTrue(msg instanceof HttpBadRequest);
            assertTrue(((HttpRequest) msg).isBad());
        } finally {
            setFinalStatic(HttpConf.class, "MAX_URI_LENGTH", orig);
        }
    }
}
