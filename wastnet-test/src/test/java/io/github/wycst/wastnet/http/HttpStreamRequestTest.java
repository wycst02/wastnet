package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpStreamRequest} and {@link HttpChunkedRequest}.
 *
 * @author wangyc
 */
public class HttpStreamRequestTest {

    private static ChannelContext mockCtx() throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(-1); // no channel data
        return ctx;
    }

    private static ChannelContext mockCtxWithExtraBytes(byte[] extraBytes) throws Exception {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    byte[] buf = invocation.getArgument(0);
                    int off = invocation.getArgument(1);
                    int len = invocation.getArgument(2);
                    if (len > 0 && extraBytes != null && extraBytes.length > 0) {
                        int toCopy = Math.min(len, extraBytes.length);
                        System.arraycopy(extraBytes, 0, buf, off, toCopy);
                        return toCopy;
                    }
                    return -1;
                });
        return ctx;
    }

    // ==================== HttpStreamRequest ====================

    @Test
    public void testIsStreamReturnsTrue() throws Exception {
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(0, new byte[0], mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.GET,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                new byte[0],
                0L,
                null,
                mockCtx(),
                bodyStream
        );

        Assertions.assertTrue(request.isStream());
    }

    @Test
    public void testBodyStreamReturnsInputStream() throws Exception {
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(5, "hello".getBytes(), mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                "hello".getBytes(),
                5L,
                "text/plain",
                mockCtx(),
                bodyStream
        );

        InputStream stream = request.bodyStream();
        Assertions.assertNotNull(stream);
        Assertions.assertSame(bodyStream, stream);
    }

    @Test
    public void testCompletedDelegatesToBodyStream() throws Exception {
        // contentLength > preRead.length so stream is NOT complete until channel is read
        byte[] preRead = "hello".getBytes();
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(10, preRead, mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                preRead,
                10L,
                "text/plain",
                mockCtx(),
                bodyStream
        );

        // Not completed initially (readedLength=0, bodyLength=10, 10>0)
        Assertions.assertFalse(request.isCompleted());

        // Exhaust the stream by reading past preRead into channel
        byte[] buf = new byte[10];
        request.bodyStream().read(buf, 0, 10);

        // Now should be completed (mock returns -1, marks complete)
        Assertions.assertTrue(request.isCompleted());
    }

    @Test
    public void testCompleteMarksStreamCompleted() throws Exception {
        byte[] preRead = "hello".getBytes();
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(5, preRead, mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                preRead,
                5L,
                "text/plain",
                mockCtx(),
                bodyStream
        );

        request.complete();
        Assertions.assertTrue(request.isCompleted());
    }

    @Test
    public void testGetBodyDataExceedsLimitThrows() throws Exception {
        // contentLength must be STRICTLY greater than 2 * MAX_BODY_IN_MEMORY to trigger the throw.
        // (the if uses '>', so 2*MAX alone does NOT enter the branch)
        long overLimit = ((long) HttpConf.MAX_BODY_IN_MEMORY << 1) + 1;
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(overLimit, new byte[0], mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                new byte[0],
                overLimit,
                "text/plain",
                mockCtx(),
                bodyStream
        );

        // Should throw IllegalStateException before attempting to read from stream
        Assertions.assertThrows(IllegalStateException.class, () -> request.getBodyData());
    }

    @Test
    public void testDelegateBodyWithEmptyBody() throws Throwable {
        ChannelContext targetCtx = mock(ChannelContext.class);
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(0, new byte[0], mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.GET,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                new byte[0],
                0L,
                null,
                mockCtx(),
                bodyStream
        );

        request.delegateBody(targetCtx);

        // No data written for empty body
        verify(targetCtx, never()).write(any(byte[].class), anyInt(), anyInt());
    }

    @Test
    public void testHttpStreamRequestWithEmptyParameters() throws Exception {
        // empty map instead of null to avoid NPE
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(0, new byte[0], mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.GET,
                "/test".getBytes(),
                "/test",
                Collections.<String, List<String>>emptyMap(),
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                new byte[0],
                0L,
                null,
                mockCtx(),
                bodyStream
        );

        Assertions.assertNull(request.getUriParameter("foo"));
    }

    @Test
    public void testHttpStreamRequestWithParameters() throws Exception {
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("name", Collections.singletonList("value1"));

        HttpBodyInputStream bodyStream = new HttpBodyInputStream(0, new byte[0], mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.GET,
                "/test".getBytes(),
                "/test",
                params,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                new byte[0],
                0L,
                null,
                mockCtx(),
                bodyStream
        );

        Assertions.assertEquals("value1", request.getUriParameter("name"));
    }

    // ==================== HttpStreamRequest: Additional Branch Tests ====================

    @Test
    public void testGetBodyDataReturnsContentWhenWithinLimit() throws Exception {
        // getBodyData() normal path: contentLength <= 2*MAX_BODY_IN_MEMORY,
        // readFullBytes() reads full body from pre-read + channel data.
        // Note: readFullBytes0 sets pos=byteLen before reading, so remaining
        // bytes must come from channel (hence mockCtxWithExtraBytes).
        byte[] preRead = "hello ".getBytes();
        byte[] extraBytes = "world".getBytes();
        ChannelContext ctx = mockCtxWithExtraBytes(extraBytes);
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(
                preRead.length + extraBytes.length, preRead, ctx);
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                preRead,
                preRead.length + extraBytes.length,
                "text/plain",
                ctx,
                bodyStream
        );

        byte[] result = request.getBodyData();
        Assertions.assertArrayEquals("hello world".getBytes(), result);
        // Subsequent call should return cached result (fullBytes != null)
        Assertions.assertSame(result, request.getBodyData());
    }

    @Test
    public void testDelegateBodyWithNonEmptyBody() throws Throwable {
        // delegateBody() with data: should write pre-read data to target channel
        ChannelContext targetCtx = mock(ChannelContext.class);
        byte[] body = "stream body data".getBytes();
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(body.length, body, mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.GET,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                body,
                body.length,
                null,
                mockCtx(),
                bodyStream
        );

        request.delegateBody(targetCtx);

        verify(targetCtx, atLeastOnce()).write(any(byte[].class), anyInt(), anyInt());
    }

    @Test
    public void testCompletedReturnsTrueWhenAllDataPreRead() throws Exception {
        // bodyLength == readedBytes.length → checkCompleted() returns true immediately
        byte[] body = "hello".getBytes();
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(body.length, body, mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.GET,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                body,
                body.length,
                null,
                mockCtx(),
                bodyStream
        );

        // completed() calls checkCompleted() which sets completed = true
        // since bodyLength (5) == readedLength (5) without any channel read
        Assertions.assertTrue(request.isCompleted());
    }

    @Test
    public void testCompleteIdempotentWhenAlreadyCompleted() throws Exception {
        // complete() is already no-op when bodyStream is completed
        // Verifies the `if (completed) return;` guard in HttpBodyInputStream.complete()
        byte[] body = "data".getBytes();
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(body.length, body, mockCtx());
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                body,
                body.length,
                null,
                mockCtx(),
                bodyStream
        );

        // First call completes normally
        request.complete();
        Assertions.assertTrue(request.isCompleted());

        // Second call should be idempotent — no exception, state unchanged
        request.complete();
        Assertions.assertTrue(request.isCompleted());
    }

    @Test
    public void testGetBodyDataAfterPartialStreamReadThrows() throws Exception {
        // readFullBytes0() guard: `if (pos > byteLen) throw IllegalStateException(...)`
        // When pos advances beyond pre-read region due to channel data, getBodyData is rejected
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isChannelClosed()).thenReturn(false);
        when(ctx.readFully(any(byte[].class), anyInt(), anyInt(), anyLong()))
                .thenReturn(1);  // Return 1 byte from channel to advance pos past pre-read

        byte[] preRead = "hello".getBytes();
        HttpBodyInputStream bodyStream = new HttpBodyInputStream(10, preRead, ctx);
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/test".getBytes(),
                "/test",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                preRead,
                10L,
                "text/plain",
                ctx,
                bodyStream
        );

        // Read 6 bytes (5 pre-read + 1 from channel) → pos = 6 > byteLen = 5
        byte[] buf = new byte[6];
        request.bodyStream().read(buf);

        // Now pos > byteLen, getBodyData should throw
        Assertions.assertThrows(IllegalStateException.class, () -> request.getBodyData());
    }

    @Test
    public void testPublicConstructorCreatesValidRequest() throws Exception {
        // Exercise the public constructor (creates HttpBodyInputStream internally)
        byte[] preRead = "payloa".getBytes();
        byte[] extraBytes = "d".getBytes();
        HttpStreamRequest request = new HttpStreamRequest(
                HttpMethod.POST,
                "/body".getBytes(),
                "/body",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                preRead,
                preRead.length + extraBytes.length,
                "application/json",
                mockCtxWithExtraBytes(extraBytes)
        );

        Assertions.assertTrue(request.isStream());
        Assertions.assertNotNull(request.bodyStream());
        Assertions.assertEquals("payload", new String(request.getBodyData()));
    }

    // ==================== HttpChunkedRequest ====================

    @Test
    public void testHttpChunkedRequestIsStream() throws Exception {
        // HttpChunkedRequest extends HttpStreamRequest
        byte[] wire = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "application/octet-stream",
                mockCtx()
        );

        Assertions.assertTrue(request.isStream());
    }

    @Test
    public void testHttpChunkedRequestBodyStream() throws Exception {
        byte[] wire = "3\r\nabc\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "application/octet-stream",
                mockCtx()
        );

        InputStream stream = request.bodyStream();
        Assertions.assertNotNull(stream);
        Assertions.assertTrue(stream instanceof HttpChunkedStream);
    }


    @Test
    public void testHttpChunkedRequestComplete() throws Exception {
        byte[] wire = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "application/octet-stream",
                mockCtx()
        );

        request.complete();
        Assertions.assertTrue(request.isCompleted());
    }

    // ==================== HttpChunkedRequest: Additional Branch Tests ====================


    @Test
    public void testHttpChunkedRequestGetBodyDataThrows() throws Exception {
        // Single chunk followed by terminator
        byte[] wire = "5\r\nhello\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "text/plain",
                mockCtx()
        );

        Assertions.assertThrows(IllegalStateException.class, () -> request.getBodyData());
    }

    @Test
    public void testHttpChunkedRequestReadViaStreamDirectly() throws Exception {
        // Read chunked data through bodyStream() read() method
        byte[] wire = "6\r\nstream\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "text/plain",
                mockCtx()
        );

        byte[] buf = new byte[32];
        int bytesRead = request.bodyStream().read(buf);
        Assertions.assertEquals(6, bytesRead);
        Assertions.assertArrayEquals("stream".getBytes(), Arrays.copyOf(buf, bytesRead));
    }

    @Test
    public void testHttpChunkedRequestCompletedBeforeAndAfterStreamRead() throws Exception {
        // completed() reflects actual stream state
        byte[] wire = "4\r\ndata\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "text/plain",
                mockCtx()
        );

        // Not completed before reading (chunk data is pre-read but not yet consumed)
        Assertions.assertFalse(request.isCompleted());

        // Read all chunk data via stream
        byte[] buf = new byte[32];
        request.bodyStream().read(buf);

        // Now completed (last chunk terminator was processed)
        Assertions.assertTrue(request.isCompleted());
    }

    @Test
    public void testHttpChunkedRequestWithChunkExtensionFails() throws Exception {
        // Chunk extension (semicolon in chunk-size line) triggers Utils.hexNibble(';') == -1
        // readChunkSize() throws UnexpectedException → wrapped as IllegalStateException
        byte[] wire = "3;ext=1\r\nabc\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "application/octet-stream",
                mockCtx()
        );

        // ';' is not a hex digit, readChunkSize rejects it
        Assertions.assertThrows(IllegalStateException.class, () -> request.getBodyData());
    }


    @Test
    public void testHttpChunkedRequestCompletedAfterCompleteCall() throws Exception {
        // complete() triggers complete0() which reads remaining chunks via readChunkSize
        // until terminator chunk (size=0), then readEndChunked(), then markCompleted()
        byte[] wire = "6\r\nchunks\r\n3\r\ncba\r\n0\r\n\r\n".getBytes();
        HttpChunkedRequest request = new HttpChunkedRequest(
                HttpMethod.POST,
                "/chunked".getBytes(),
                "/chunked",
                null,
                HttpVersion.HTTP_1_1,
                new HashMap<String, Object>(),
                wire,
                -1L,
                "application/octet-stream",
                mockCtx()
        );

        Assertions.assertFalse(request.isCompleted());
        request.complete();
        Assertions.assertTrue(request.isCompleted());
    }
}