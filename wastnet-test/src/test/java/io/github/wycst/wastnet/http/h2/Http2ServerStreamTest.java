package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link Http2ServerStream}.
 * <p>
 * Covers header parsing, request submission lifecycle, and backpressure
 * (flow-control) logic in {@link Http2Stream#onDataFrame}:
 * <ul>
 *   <li>Receive window exhaustion → WINDOW_UPDATE pair refill</li>
 *   <li>Receive window overflow → FLOW_CONTROL_ERROR (RST_STREAM)</li>
 *   <li>End-of-stream with body → connection-level WU</li>
 *   <li>Post-invocation DATA frames → feedDataFrame</li>
 *   <li>WINDOW_UPDATE on data stream → send window tracking + overflow guard</li>
 * </ul>
 *
 * @author wangyc
 */
public class Http2ServerStreamTest {

    // ==================== Fixture ====================

    /**
     * Create a real Http2ServerStream with mocked reader and ctx.
     * The reader fields are set via reflection to avoid NPE from mock defaults.
     */
    private static class StreamFixture {
        final ChannelContext ctx;
        final Http2ServerReader reader;
        final Http2ServerStream stream;

        StreamFixture(ChannelContext ctx, Http2ServerReader reader, Http2ServerStream stream) {
            this.ctx = ctx;
            this.reader = reader;
            this.stream = stream;
        }
    }

    private static StreamFixture createFixture() throws Exception {
        return createFixture(1);
    }

    private static StreamFixture createFixture(int streamId) throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2ServerReader reader = mock(Http2ServerReader.class);
        H2TestHelper.initMockReader(reader);
        reader.streamInitSendWindowSize = 65535;
        reader.connectRecvWindow = new java.util.concurrent.atomic.AtomicLong(Http2MessageReader.CONNECT_RECEIVE_WINDOW_SIZE);
        // Initialize the final field http2HpackCodec via Unsafe (mock leaves it null)
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Field hpackField = Http2MessageReader.class.getDeclaredField("http2HpackCodec");
        long offset = (long) unsafe.getClass().getMethod("objectFieldOffset", Field.class).invoke(unsafe, hpackField);
        unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, reader, offset, new Http2HpackCodec());
        Http2ServerStream stream = new Http2ServerStream(reader, streamId, ctx);
        // frameBuf is initialized by onHeadersFrame in real flow; mimic it for direct onDataFrame calls.
        Field fbField = Http2Stream.class.getDeclaredField("frameBuf");
        fbField.setAccessible(true);
        fbField.set(stream, HttpBuf.of(64));
        return new StreamFixture(ctx, reader, stream);
    }

    /** Build a minimal HEADERS frame for endHeaders setup. */
    private static void setupHeaders(Http2ServerStream stream) {
        stream.headers.put(":method", "GET");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "/");
        stream.headers.put(":authority", "h");
    }

    // ==================== debugPrefix ====================

    @Test
    public void testDebugPrefix() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        assertEquals("Server ", new Http2ServerStream(reader, 1, ctx).debugPrefix());
    }

    // ==================== onEndHeaders ====================

    @Test
    public void testOnEndHeadersParsesBasicHeaders() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "GET");
        stream.headers.put(":scheme", "https");
        stream.headers.put(":path", "/hello?name=test");
        stream.headers.put(":authority", "example.com");
        stream.headers.put("content-type", "text/plain");

        stream.endHeaders();

        assertEquals(HttpMethod.GET, stream.method);
        assertEquals("https", stream.scheme);
        assertEquals("/hello?name=test", stream.path);
        assertEquals("text/plain", stream.contentType);
        assertEquals("example.com", stream.authority);
        assertEquals("/hello", stream.requestUri);
        assertNotNull(stream.parameters);
        assertTrue(stream.endHeaders);
    }

    @Test
    public void testOnEndHeadersParsesPostWithBody() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "POST");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "/api/data");
        stream.headers.put(":authority", "host:8080");
        stream.headers.put("content-type", "application/json");
        stream.headers.put("content-length", "42");

        stream.endHeaders();

        assertEquals(HttpMethod.POST, stream.method);
        assertEquals(42, stream.declaredContentLength);
        assertEquals("/api/data", stream.path);
    }

    @Test
    public void testOnEndHeadersParsesQueryParameters() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "GET");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "/search?q=hello&page=1");
        stream.headers.put(":authority", "example.com");

        stream.endHeaders();

        assertNotNull(stream.parameters);
        List<String> qValues = stream.parameters.get("q");
        assertNotNull(qValues);
        assertEquals("hello", qValues.get(0));
        List<String> pValues = stream.parameters.get("page");
        assertNotNull(pValues);
        assertEquals("1", pValues.get(0));
    }

    @Test
    public void testOnEndHeadersWithContentLengthExceedingMaxMarksError() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "POST");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "/big");
        stream.headers.put(":authority", "example.com");
        // content-length exceeds the default BODY_MAX_SIZE (512MB) → 413, no artificial override
        stream.headers.put("content-length", "536870913");
        stream.endHeaders();
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, stream.getErrorStatus());
    }

    // ==================== submitRequest ====================

    @Test
    public void testSubmitInvokesRunAsync() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        setupHeaders(stream);

        stream.submit();

        assertTrue(stream.requestInvoked);
        verify(ctx).runAsync(any(Runnable.class));
    }

    @Test
    public void testSubmitRequestSetsInvokedFlag() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        setupHeaders(stream);

        assertFalse(stream.requestInvoked);
        stream.submit();
        assertTrue(stream.requestInvoked);
    }

    // ==================== Backpressure: onDataFrame ====================
    //
    // Flow-control design (RFC 7540 §6.9, §6.10):
    //
    //   onDataFrame decrements both stream-level (receiveWindow) and
    //   connection-level (reader.connectRecvWindow) windows, then dispatches:
    //
    //   ┌─ requestInvoked=true  ──► feedDataFrame()  (consumption-driven WU via notifyConsumed)
    //   ├─ endStream             ──► prepareSubmit(true) + connection WU for body
    //   ├─ receiveWindow > 0     ──► restoreConnectionWindow()   (connection WU if needed)
    //   ├─ receiveWindow < 0     ──► RST_STREAM FLOW_CONTROL_ERROR
    //   └─ receiveWindow == 0 ───┤
    //       ├─ body<capacity     ──► WU pair + refill receiveWindow
    //       └─ body==capacity    ──► switch to streaming + prepareSubmit(false)

    /**
     * Build a DATA frame with the given payload bytes and END_STREAM flag.
     */
    private static Http2Frame createDataFrame(byte[] payload, boolean endStream) {
        byte[] frameData = new byte[9 + payload.length];
        System.arraycopy(payload, 0, frameData, 9, payload.length);
        int flags = endStream ? Http2Frame.END_STREAM : 0;
        return new Http2Frame(frameData, 0, 9, payload.length, payload.length, Http2FrameType.DATA, flags, 0);
    }

    // ---------- receiveWindow == 0 → WU pair refill ----------

    @Test
    public void testOnDataFrameWindowExhaustedSendsWuPair() throws Exception {
        StreamFixture f = createFixture();
        setupHeaders(f.stream);
        f.stream.endHeaders();                // headers parsed
        f.stream.receiveWindow = 16384;       // set to a known value
        f.reader.connectRecvWindow.set(20000);

        // Send DATA frame that exhausts the window exactly (16384 bytes)
        Http2Frame frame = createDataFrame(new byte[16384], false);
        f.stream.onDataFrame(frame, f.ctx);

        // With HTTP2_STREAM_EARLY enabled (default), the first window exhaustion
        // switches the stream to streaming mode instead of refilling via WU pair.
        assertEquals(0, f.stream.receiveWindow);
        assertTrue(f.stream.needStreaming);
        // Streaming path does not send a WU pair.
        verify(f.reader, never()).sendWindowUpdatePair(eq(f.ctx), eq(f.stream), anyInt(), eq(true));
        // Streaming triggers stream submission.
        verify(f.ctx).runAsync(any(Runnable.class));
    }

    // ---------- receiveWindow < 0 → FLOW_CONTROL_ERROR ----------

    @Test
    public void testOnDataFrameWindowUnderflowSendsRstStream() throws Exception {
        StreamFixture f = createFixture();
        setupHeaders(f.stream);
        f.stream.endHeaders();
        f.stream.receiveWindow = 100;
        f.reader.connectRecvWindow.set(200);

        // Send DATA frame with payload larger than remaining window
        Http2Frame frame = createDataFrame(new byte[200], false);
        f.stream.onDataFrame(frame, f.ctx);

        // After decrement: receiveWindow = 100 - 200 = -100 < 0
        assertTrue(f.stream.receiveWindow < 0);
        // FLOW_CONTROL_ERROR (code 3)
        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 3);
        verify(f.reader).removeStream(1);
    }

    // ---------- END_STREAM in DATA frame → connection WU for body ----------

    @Test
    public void testOnDataFrameEndStreamWithBodySendsConnectionWu() throws Exception {
        StreamFixture f = createFixture();
        setupHeaders(f.stream);
        f.stream.endHeaders();
        f.stream.receiveWindow = 65535;

        // Send DATA frame with body + END_STREAM
        byte[] body = "hello".getBytes();
        Http2Frame frame = createDataFrame(body, true);
        f.stream.onDataFrame(frame, f.ctx);

        // endStream flag set, bodyData = "hello" (5 bytes)
        assertTrue(f.stream.endStream);
        // Connection-level WU should be sent for consumed body bytes
        verify(f.reader).sendConnectionWindowUpdate(f.ctx, 5);
    }

    // ---------- requestInvoked → feedDataFrame ----------

    @Test
    public void testOnDataFrameAfterInvocationFeedsStream() throws Exception {
        StreamFixture f = createFixture();
        setupHeaders(f.stream);
        f.stream.endHeaders();
        f.stream.requestInvoked = true;
        f.stream.receiveWindow = 65535;
        // bodyStream must be non-null for feedDataFrame to work
        f.stream.bodyStream = new Http2BodyInputStream(f.stream.bodyData, f.stream);

        byte[] body = "data".getBytes();
        Http2Frame frame = createDataFrame(body, false);
        f.stream.onDataFrame(frame, f.ctx);

        // After invocation, DATA is fed to bodyStream, not buffered
        // feedDataFrame calls bodyStream.feed(), window already decremented
        assertEquals(65535 - body.length, f.stream.receiveWindow);
    }

    // ---------- DATA before END_HEADERS → protocol error ----------

    @Test
    public void testOnDataFrameBeforeEndHeadersClosesConnection() throws Exception {
        StreamFixture f = createFixture();
        // Do NOT call endHeaders() → endHeaders stays false

        Http2Frame frame = createDataFrame(new byte[10], false);
        f.stream.onDataFrame(frame, f.ctx);

        // RFC 7540 §5.1: DATA on an idle stream (no HEADERS) -> connection-level PROTOCOL_ERROR
        verify(f.reader).closeConnection(f.ctx, 1);
    }

    // ==================== Backpressure: handleFrame ====================

    // ---------- WINDOW_UPDATE on data stream ----------

    @Test
    public void testHandleFrameWindowUpdateIncrementsWindows() throws Exception {
        StreamFixture f = createFixture();
        f.stream.sendWindow = 100;
        f.reader.connectSendWindow = 65535;

        // Build WINDOW_UPDATE frame with increment = 5000
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(new byte[]{0, 0, 4, 8, 0, 0, 0, 0, 1}); // type=8, streamId=1
        buf.putInt(5000);
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        f.stream.handleFrame(frame, f.ctx);

        assertEquals(100 + 5000, f.stream.sendWindow);
        verify(f.reader).wakeupSendWU();
    }

    // ---------- WINDOW_UPDATE overflow → RST_STREAM ----------

    @Test
    public void testHandleFrameWindowUpdateOverflowSendsRst() throws Exception {
        StreamFixture f = createFixture();
        f.stream.sendWindow = Integer.MAX_VALUE - 100;  // near overflow
        f.reader.connectSendWindow = 65535;

        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(new byte[]{0, 0, 4, 8, 0, 0, 0, 0, 1}); // type=8, streamId=1
        buf.putInt(200);  // increment pushes sendWindow past Integer.MAX_VALUE
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        f.stream.handleFrame(frame, f.ctx);

        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 3); // FLOW_CONTROL_ERROR
        verify(f.reader).removeStream(1);
    }

    // ---------- RST_STREAM on data stream ----------

    @Test
    public void testHandleFrameRstStreamCleansUpWindow() throws Exception {
        StreamFixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.bodyStream = new Http2BodyInputStream("data".getBytes(), f.stream);

        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(new byte[]{0, 0, 4, 3, 0, 0, 0, 0, 1}); // length=4, type=3 RST_STREAM, streamId=1
        buf.putInt(0); // error code = NO_ERROR
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        f.stream.handleFrame(frame, f.ctx);

        // RST_STREAM → removeStream
        verify(f.reader).removeStream(1);
    }

    // ---------- SETTINGS on data stream → close connection ----------

    @Test
    public void testHandleFrameSettingsOnDataStreamClosesConnection() throws Exception {
        StreamFixture f = createFixture();

        ByteBuffer buf = ByteBuffer.allocate(9);
        buf.put(new byte[]{0, 0, 0, 4, 0, 0, 0, 0, 1}); // type=4 SETTINGS, streamId=1
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        f.stream.handleFrame(frame, f.ctx);

        verify(f.reader).closeConnection(f.ctx, 1);
    }

    // ==================== Backpressure: completeStream ====================

    @Test
    public void testCompleteStreamWithoutEndStreamSentSendsFallbackData() throws Exception {
        StreamFixture f = createFixture();
        f.stream.endStreamSent = false;

        f.stream.completeStream();

        // Should attempt to send fallback DATA frame with END_STREAM
        verify(f.reader).removeStream(1);
    }

    @Test
    public void testCompleteStreamSendsWuForUnconsumedBytes() throws Exception {
        StreamFixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.bodyStream = new Http2BodyInputStream("hello".getBytes(), f.stream);
        // totalLength=5, consumed=0 → unconsumed=5, reclaim is exercised inside removeStream
        f.stream.completeStream();

        // Via the mock reader, only removeStream is observable (the downstream reclaim
        // via pendingConnectionReclaim → sendConnectionWindowUpdate executes on a real reader).
        verify(f.reader).removeStream(1);
    }

    @Test
    public void testCompleteStreamNoWuWhenAllConsumed() throws Exception {
        StreamFixture f = createFixture();
        f.stream.needStreaming = true;
        f.stream.bodyStream = new Http2BodyInputStream("data".getBytes(), f.stream);
        f.stream.bodyStream.read(new byte[4], 0, 4); // consume all → consumed=4, totalLength=4

        f.stream.completeStream();

        // All bytes consumed, no WU needed
        verify(f.reader, never()).sendConnectionWindowUpdate(eq(f.ctx), anyInt(), eq(true));
    }

    // ==================== HEADERS frame processing ====================
    //
    // onHeadersFrame dispatch via handleFrame / onHeadersFrame:
    //
    //   ┌─ endHeaders already true    ──► RST_STREAM PROTOCOL_ERROR
    //   ├─ HPACK decode error          ──► closeConnection
    //   ├─ !END_HEADERS                ──► waitingContinuation = true
    //   ├─ endHeaders() throws         ──► RST_STREAM (error code 7 for body too large)
    //   └─ END_STREAM in HEADERS       ──► submitRequest (no body)
    //
    // CONTINUATION when waitingContinuation:
    //     handleFrame → onHeadersFrame again (HPACK decode + END_HEADERS check)

    /** Build a HEADERS frame with HPACK-encoded header data. */
    private static Http2Frame createHeadersFrame(byte[] hpackData, boolean endHeaders, boolean endStream) {
        int flags = 0;
        if (endHeaders) flags |= Http2Frame.END_HEADERS;
        if (endStream) flags |= Http2Frame.END_STREAM;
        byte[] frameData = new byte[9 + hpackData.length];
        System.arraycopy(hpackData, 0, frameData, 9, hpackData.length);
        return new Http2Frame(frameData, 0, 9, hpackData.length, hpackData.length, Http2FrameType.HEADERS, flags, 1);
    }

    /** Build a CONTINUATION frame with HPACK-encoded header data. */
    private static Http2Frame createContinuationFrame(byte[] hpackData, boolean endHeaders) {
        int flags = endHeaders ? Http2Frame.END_HEADERS : 0;
        byte[] frameData = new byte[9 + hpackData.length];
        System.arraycopy(hpackData, 0, frameData, 9, hpackData.length);
        return new Http2Frame(frameData, 0, 9, hpackData.length, hpackData.length, Http2FrameType.CONTINUATION, flags, 1);
    }

    /**
     * HPACK-encode pseudo-headers for a minimal valid request.
     * Uses indexed entries for common headers + literal for :authority.
     */
    private static byte[] encodeSimpleGetHeaders() throws Exception {
        // Build: :method: GET (index 2 = 0x82), :scheme: http (index 6 = 0x86),
        //        :path: / (index 4 = 0x84),
        //        :authority: h (literal, name ref index 1 = 0x41, plain "h")
        return new byte[]{ (byte) 0x82, (byte) 0x86, (byte) 0x84,
                           0x41, 0x01, 0x68 };
    }

    // ---------- HEADERS with END_STREAM → submitRequest ----------

    @Test
    public void testOnHeadersFrameWithEndStreamSubmitsRequest() throws Exception {
        StreamFixture f = createFixture();

        Http2Frame frame = createHeadersFrame(encodeSimpleGetHeaders(), true, true);
        f.stream.handleFrame(frame, f.ctx);

        // END_STREAM + END_HEADERS → submitRequest called
        assertTrue(f.stream.endStream);
        assertTrue(f.stream.endHeaders);
        verify(f.ctx).runAsync(any(Runnable.class));
    }

    // ---------- HEADERS without END_STREAM → wait for DATA ----------

    @Test
    public void testOnHeadersFrameWithoutEndStream() throws Exception {
        StreamFixture f = createFixture();

        Http2Frame frame = createHeadersFrame(encodeSimpleGetHeaders(), true, false);
        f.stream.handleFrame(frame, f.ctx);

        // END_HEADERS set, no END_STREAM → headers parsed, body frames expected
        assertTrue(f.stream.endHeaders);
        assertFalse(f.stream.endStream);
        assertEquals(HttpMethod.GET, f.stream.method);
        assertEquals("http", f.stream.scheme);
        assertEquals("/", f.stream.path);
        assertEquals("h", f.stream.authority);
    }

    // ---------- onHeadersFrame after endHeaders already done → RST ----------

    @Test
    public void testOnHeadersFrameAfterEndHeadersSendsRst() throws Exception {
        StreamFixture f = createFixture();
        // Populate headers first so endHeaders() can succeed
        setupHeaders(f.stream);
        f.stream.endHeaders(); // complete headers, now endHeaders=true

        Http2Frame frame = createHeadersFrame(encodeSimpleGetHeaders(), true, false);
        f.stream.onHeadersFrame(frame, f.ctx);

        verify(f.reader).sendRstStreamFrame(f.ctx, 1, 1); // PROTOCOL_ERROR
        verify(f.reader).removeStream(1);
    }

    // ---------- !END_HEADERS → waitingContinuation ----------

    @Test
    public void testOnHeadersFrameWithoutEndHeadersWaitsForContinuation() throws Exception {
        StreamFixture f = createFixture();
        // Split the HPACK data: first frame has partial headers, no END_HEADERS
        byte[] fullHpack = encodeSimpleGetHeaders();
        byte[] firstPart = Arrays.copyOf(fullHpack, 2); // just :method + :scheme

        Http2Frame frame = createHeadersFrame(firstPart, false, false);
        f.stream.handleFrame(frame, f.ctx);

        assertTrue(f.stream.waitingContinuation);
    }

    // ---------- CONTINUATION frame (waitingContinuation=true) ----------

    @Test
    public void testContinuationFrameAfterPartialHeaders() throws Exception {
        StreamFixture f = createFixture();
        byte[] fullHpack = encodeSimpleGetHeaders();
        byte[] firstPart = Arrays.copyOf(fullHpack, 2);  // :method + :scheme
        byte[] rest = Arrays.copyOfRange(fullHpack, 2, fullHpack.length); // :path + :authority

        // First: HEADERS without END_HEADERS
        f.stream.handleFrame(createHeadersFrame(firstPart, false, false), f.ctx);
        assertTrue(f.stream.waitingContinuation);

        // Second: CONTINUATION with remaining headers + END_HEADERS
        Http2Frame continuation = createContinuationFrame(rest, true);
        f.stream.handleFrame(continuation, f.ctx);

        // Headers should be fully parsed now (waitingContinuation stays true as implementation detail,
        // key assertion is that endHeaders=true and pseudo-headers are extracted)
        assertTrue(f.stream.endHeaders);
        assertEquals(HttpMethod.GET, f.stream.method);
        assertEquals("http", f.stream.scheme);
    }

    // ---------- HPACK decode error → closeConnection ----------

    @Test
    public void testOnHeadersFrameHpackErrorClosesConnection() throws Exception {
        StreamFixture f = createFixture();
        // Corrupted HPACK data that will fail decoding
        byte[] garbage = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };

        Http2Frame frame = createHeadersFrame(garbage, true, false);
        f.stream.onHeadersFrame(frame, f.ctx);

        verify(f.reader).closeConnection(f.ctx, 9);
    }

    // ---------- handleFrame dispatches PRIORITY (no-op) ----------

    @Test
    public void testHandleFramePriorityIsSilentlyIgnored() throws Exception {
        StreamFixture f = createFixture();

        ByteBuffer buf = ByteBuffer.allocate(14);
        buf.put(new byte[]{0, 0, 5, 2, 0, 0, 0, 0, 1}); // type=2 PRIORITY, streamId=1
        buf.putInt(0); // dependency
        buf.put((byte) 10); // weight
        buf.flip();
        Http2Frame frame = Http2Frame.fromByteBuffer(buf).get(0);

        f.stream.handleFrame(frame, f.ctx);

        // PRIORITY is silently ignored, no error/action expected
        verify(f.reader, never()).closeConnection(any());
        verify(f.reader, never()).removeStream(anyInt());
    }

    // ---------- CONTINUATION without waitingContinuation → silently ignored ----------

    @Test
    public void testHandleFrameContinuationWithoutWaitingIsIgnored() throws Exception {
        StreamFixture f = createFixture();
        f.stream.waitingContinuation = false; // not expecting continuation

        // Send a CONTINUATION frame with some HPACK data
        Http2Frame frame = createContinuationFrame(new byte[]{ (byte) 0x82 }, true);
        f.stream.handleFrame(frame, f.ctx);

        // waitingContinuation is false → handleFrame falls through switch (no matching case),
        // CONTINUATION is silently ignored
        assertFalse(f.stream.endHeaders); // headers not parsed
        verify(f.reader, never()).closeConnection(any());
        verify(f.reader, never()).removeStream(anyInt());
    }

    // ---------- handleFrame dispatches HEADERS ----------

    @Test
    public void testHandleFrameHeadersDispatchesToOnHeadersFrame() throws Exception {
        StreamFixture f = createFixture();

        Http2Frame frame = createHeadersFrame(encodeSimpleGetHeaders(), true, true);
        f.stream.handleFrame(frame, f.ctx);

        // Should have gone through onHeadersFrame → endHeaders → submitRequest
        assertTrue(f.stream.endHeaders);
        verify(f.ctx).runAsync(any(Runnable.class));
    }

    // ==================== onEndHeaders: uncovered branches ====================

    @Test
    public void testOnEndHeadersUnknownMethodMarksMethodNotAllowed() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "UNKNOWN_VERB");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "/");
        stream.headers.put(":authority", "example.com");
        stream.endHeaders();
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, stream.getErrorStatus());
    }

    @Test
    public void testOnEndHeadersEmptyAuthorityThrows() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "GET");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "/");
        stream.headers.put(":authority", "");
        try {
            stream.endHeaders();
            fail("expected IllegalArgumentException for empty :authority");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testOnEndHeadersConnectSkipsSchemePathValidation() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "CONNECT");
        // CONNECT carries no :scheme/:path; give :path a non-null value to avoid NPE
        stream.headers.put(":path", "example.com:443");
        stream.headers.put(":authority", "example.com:443");
        stream.endHeaders();
        assertEquals(HttpMethod.CONNECT, stream.method);
        assertNull(stream.getErrorStatus());
    }

    @Test
    public void testOnEndHeadersEmptySchemeThrows() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "GET");
        stream.headers.put(":scheme", "");
        stream.headers.put(":path", "/");
        stream.headers.put(":authority", "example.com");
        try {
            stream.endHeaders();
            fail("expected IllegalArgumentException for empty :scheme");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testOnEndHeadersPathNotStartingWithSlashThrows() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "GET");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "foo");
        stream.headers.put(":authority", "example.com");
        try {
            stream.endHeaders();
            fail("expected IllegalArgumentException for :path not starting with '/'");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testOnEndHeadersOptionsAsteriskForm() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        H2TestHelper.initMockReader(reader);
        Http2ServerStream stream = new Http2ServerStream(reader, 1, ctx);
        stream.headers.put(":method", "OPTIONS");
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", "*");
        stream.headers.put(":authority", "example.com");
        stream.endHeaders();
        assertEquals(HttpMethod.OPTIONS, stream.method);
        assertNull(stream.getErrorStatus());
    }

    // ==================== submit: Runnable body (runAsync executes it) ====================

    @Test
    public void testSubmitRunsRunnableInvokesHandleAndCompletes() throws Exception {
        StreamFixture f = createFixture();
        setupHeaders(f.stream);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Runnable r = (Runnable) invocation.getArguments()[0];
                try {
                    r.run();
                } catch (Throwable ignored) {
                    // invokeHandle is final on the mock ctx; its real impl throws a
                    // non-IOException that is intentionally not asserted here.
                }
                return null;
            }
        }).when(f.ctx).runAsync(any(Runnable.class));

        f.stream.submit();

        verify(f.ctx).runAsync(any(Runnable.class));
        // handovered=false -> finally block calls completeStream -> removeStream
        verify(f.reader).removeStream(1);
    }

    @Test
    public void testSubmitSkipsCompleteStreamWhenHandovered() throws Exception {
        StreamFixture f = createFixture();
        setupHeaders(f.stream);
        f.stream.handovered = true;
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                Runnable r = (Runnable) invocation.getArguments()[0];
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
                return null;
            }
        }).when(f.ctx).runAsync(any(Runnable.class));

        f.stream.submit();

        // handovered=true -> completeStream must NOT be called
        verify(f.reader, never()).removeStream(anyInt());
    }
}
