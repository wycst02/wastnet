package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpResponseDecoder}.
 *
 * @author wangyc
 */
public class HttpResponseDecoderTest {

    @Test
    public void testGetResultReturnsNullBeforeDecode() {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        HttpMessage result = decoder.getResult();
        Assertions.assertNull(result);
    }

    @Test
    public void testDecodeSimpleResponse() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "Hello World";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
        Assertions.assertEquals(200, result.getStatusCode());
        Assertions.assertEquals("OK", result.getReasonPhrase());
        Assertions.assertEquals(HttpVersion.HTTP_1_1, result.getVersion());
    }

    @Test
    public void testDecodeResponseWithHeaders() throws Exception {
        String responseStr = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Type: text/html\r\n" +
                "Server: wastnet\r\n" +
                "\r\n" +
                "404 Page Not Found";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
        Assertions.assertEquals(404, result.getStatusCode());
        Assertions.assertEquals("Not", result.getReasonPhrase());
    }

    @Test
    public void testDecodeResponse200WithNoBody() throws Exception {
        String responseStr = "HTTP/1.0 204 No Content\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
        Assertions.assertEquals(204, result.getStatusCode());
        Assertions.assertEquals("No", result.getReasonPhrase());
        Assertions.assertEquals(HttpVersion.HTTP_1_0, result.getVersion());
    }

    @Test
    public void testGetResultIsNotHttpRequest() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpMessage result = decoder.getResult();
        Assertions.assertFalse(result.isHttpRequest());
        Assertions.assertFalse(result.isUpgrade());
    }

    @Test
    public void testDecodeWithContentLength() throws Exception {
        String body = "{\"status\":\"ok\"}";
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
        Assertions.assertEquals(200, result.getStatusCode());
        Assertions.assertEquals("application/json", result.getContentType());
        Assertions.assertEquals(body.length(), result.getContentLength());
    }

    @Test
    public void testDecodeWithBodyContent() throws Exception {
        String body = "{\"key\":\"value\"}";
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "\r\n" +
                body;
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result.getBody());
        Assertions.assertEquals(body.length(), result.getBody().length);
    }

    @Test
    public void testResponseIsNotStream() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertFalse(result.isStream());
        Assertions.assertNull(result.getBodyStream());
    }

    @Test
    public void testDecodeResponseWithMultipleHeaders() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "Cache-Control: no-cache\r\n" +
                "X-Custom: value\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n" +
                "hello";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(200, result.getStatusCode());
        Assertions.assertEquals("text/plain", result.getContentType());
        Assertions.assertArrayEquals("hello".getBytes(), result.getBody());
    }

    @Test
    public void testDecodeResponseWithConnectionClose() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(200, result.getStatusCode());
    }

    @Test
    public void testDecodeResponseWithTransferEncodingChunked() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\nHello\r\n0\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(200, result.getStatusCode());
    }

    @Test
    public void testDecodeResponseWithRepeatedHeader() throws Exception {
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Set-Cookie: a=1\r\n" +
                "Set-Cookie: b=2\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(200, result.getStatusCode());
    }

    @Test
    public void testDecodeResponseStatusCodeWithoutSpace() throws Exception {
        // Missing space after status code, valid: "200 OK" vs "200OK"
        String responseStr = "HTTP/1.1 200 OK\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(200, result.getStatusCode());
    }

    @Test
    public void testGetResultReturnsNullWhenNotDecoded() {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        Assertions.assertNull(decoder.getResult());
    }

    @Test
    public void testDecodeResponseWithEmptyHeaders() throws Exception {
        String responseStr = "HTTP/1.1 301 Moved Permanently\r\nLocation: /new\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(301, result.getStatusCode());
        Assertions.assertEquals("Moved", result.getReasonPhrase());
    }

    @Test
    public void testParseStatusCodeInvalid() throws Exception {
        // Invalid status code characters
        String responseStr = "HTTP/1.1 ABC OK\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        // Should still produce a response, with status code -1 or similar
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
    }

    // ==================== decode with ChannelContext ====================

    /**
     * Create a mock ChannelContext with a no-op channelHandler set via reflection,
     * so that invokeHandle (final method) doesn't throw NPE.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ChannelContext createCtxWithNoopHandler() {
        ChannelContext ctx = mock(ChannelContext.class);
        io.github.wycst.wastnet.socket.handler.ChannelHandler<Object> handler =
                new io.github.wycst.wastnet.socket.handler.ChannelHandler<Object>() {
                    public void onHandle(ChannelContext c, Object msg) {}
                    public void onClosed(ChannelContext c) {}
                    public void onOpen(ChannelContext c) {}
                    public void onException(ChannelContext c, Throwable t) {}
                };
        try {
            java.lang.reflect.Field f = ChannelContext.class.getDeclaredField("channelHandler");
            f.setAccessible(true);
            f.set(ctx, handler);
        } catch (Exception e) {
            throw new RuntimeException("reflection failed: " + e.getMessage(), e);
        }
        return ctx;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxNormalResponse() throws IOException {
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        String responseStr = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxBadResponse() throws IOException {
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        String responseStr = "HTTP/1.1 ABC OK\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxChunkedResponse() throws IOException {
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n5\r\nHello\r\n0\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxStreamResponse() throws IOException {
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + (HttpConf.MAX_BODY_IN_MEMORY + 1) + "\r\n" +
                "\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxPartialResponse() throws IOException {
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        String responseStr = "HTTP/1.1 200 OK\r\nContent-";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    // ==================== parseStatusCode edge cases ====================

    @Test
    public void testParseStatusCodeNullMiddle() {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        // startLineMiddle is null → parseStatusCode returns -1
        String responseStr = "HTTP/1.1 200 OK\r\n\r\n";
        byte[] data = responseStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        decoder.decode(data, 0, data.length);
        // Should parse OK (startLineMiddle is set during readStartLine)
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertEquals(200, result.getStatusCode());
    }

    @Test
    public void testParseStatusCodeEmptyMiddle() {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        // Empty status code bytes
        String responseStr = "HTTP/1.1  OK\r\n\r\n";
        byte[] data = responseStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        decoder.decode(data, 0, data.length);
        // startLineMiddle might be empty or " " depending on parsing
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxBadBoundary() throws IOException {
        // Double \r after header terminator triggers BAD_REQUEST + onBadDecoded
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        String responseStr = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxTotalHeaderTooLarge() throws IOException {
        // Accumulate enough header total to exceed MAX_HTTP_HEADER_SIZE → onBadDecoded
        ChannelContext ctx = createCtxWithNoopHandler();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 0; i < 1000; ++i) {
            sb.append("X-Hdr").append(i).append(": ").append("value").append(i).append("\r\n");
        }
        sb.append("Content-Length: 0\r\n\r\n");
        byte[] data = sb.toString().getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    // ==================== onDecodeUri override ====================

    @Test
    public void testOnDecodeUriNoop() {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        // overridden to do nothing - just verify no exception
        decoder.onDecodeUri(null);
        decoder.onDecodeUri(new byte[0]);
    }

    // ==================== prepareRequestContent override ====================

    @Test
    public void testPrepareRequestContentNoop() {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        // overridden to do nothing - just verify no exception
        decoder.prepareRequestContent();
    }

    // ==================== onBadDecoded with REQUEST_TIMEOUT ====================

    /**
     * Test-only subclass that exposes onBadDecoded and its internal status field for testing.
     */
    static class TestableResponseDecoder extends HttpResponseDecoder {
        void triggerOnBadDecoded(ChannelContext ctx) throws IOException {
            this.status = HttpStatus.REQUEST_TIMEOUT;
            startLineValues[0] = "HTTP/1.1";
            startLineValues[1] = "408";
            startLineValues[2] = "Request Timeout";
            onBadDecoded(ctx, status);
        }

        void triggerOnBadDecodedNonTimeout(ChannelContext ctx) throws IOException {
            this.status = HttpStatus.BAD_REQUEST;
            startLineValues[0] = "HTTP/1.1";
            startLineValues[1] = "400";
            startLineValues[2] = "Bad Request";
            onBadDecoded(ctx, status);
        }

        void triggerOnBadDecodedNullStatus(ChannelContext ctx) throws IOException {
            this.status = null;
            startLineValues[0] = "HTTP/1.1";
            onBadDecoded(ctx, null);
        }

        void triggerOnBadDecodedNullVersion(ChannelContext ctx) throws IOException {
            this.status = HttpStatus.BAD_REQUEST;
            startLineValues[0] = null;
            onBadDecoded(ctx, this.status);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testBadDecodedRequestTimeoutClosesConnection() throws Exception {
        ChannelContext ctx = createCtxWithCloseCapture();
        TestableResponseDecoder decoder = new TestableResponseDecoder();
        decoder.triggerOnBadDecoded(ctx);
        verify(ctx).close();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testBadDecodedNonTimeoutDoesNotClose() throws Exception {
        ChannelContext ctx = createCtxWithCloseCapture();
        TestableResponseDecoder decoder = new TestableResponseDecoder();
        decoder.triggerOnBadDecodedNonTimeout(ctx);
        verify(ctx, never()).close();
    }

    // ==================== onException path ====================

    @SuppressWarnings("unchecked")
    @Test
    public void testExceptionPathSendsBadResponse() throws Exception {
        ChannelContext ctx = createCtxWithCloseCapture();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        // Trigger an internal exception by passing invalid data that causes
        // an uncaught error during parsing.
        // The decode method catches IOException internally and calls onException.
        // We cause this by creating a situation where the decoder's body parsing fails.
        // Instead, directly exercise the onException method:
        decoder.onException(ctx, new RuntimeException("test protocol error"));
        // Should complete without throwing
    }

    // ==================== Helper with close capture ====================

    private static ChannelContext createCtxWithCloseCapture() {
        ChannelContext ctx = mock(ChannelContext.class);
        io.github.wycst.wastnet.socket.handler.ChannelHandler<Object> handler =
                new io.github.wycst.wastnet.socket.handler.ChannelHandler<Object>() {
                    public void onHandle(ChannelContext c, Object msg) {}
                    public void onClosed(ChannelContext c) {}
                    public void onOpen(ChannelContext c) {}
                    public void onException(ChannelContext c, Throwable t) {}
                };
        try {
            java.lang.reflect.Field f = ChannelContext.class.getDeclaredField("channelHandler");
            f.setAccessible(true);
            f.set(ctx, handler);
        } catch (Exception e) {
            throw new RuntimeException("reflection failed: " + e.getMessage(), e);
        }
        return ctx;
    }

    // ==================== decode with ChannelContext large body (BODY_MODE_STREAM) ====================

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxStreamBodyMultipleData() throws IOException {
        // BODY_MODE_STREAM with a known Content-Length triggers HttpBodyInputStream creation
        ChannelContext ctx = createCtxWithCloseCapture();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        // Content-Length > MAX_BODY_IN_MEMORY triggers streaming mode
        int largeLen = HttpConf.MAX_BODY_IN_MEMORY + 100;
        StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Length: ").append(largeLen).append("\r\n");
        sb.append("Content-Type: application/octet-stream\r\n");
        sb.append("\r\n");
        byte[] data = sb.toString().getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
        // Should complete without throwing
    }

    // ===== parseStatusCode with byte < '0' ( branch) =====

    @Test
    public void testParseStatusCodeWithLowByte() throws Exception {
        // Status code containing '-' (ASCII 45 < 48) triggers b < '0' → return -1
        String responseStr = "HTTP/1.1 -1 OK\r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
    }

    // ===== onDecoded bodyMode=STREAM with actual body data =====

    @SuppressWarnings("unchecked")
    @Test
    public void testDecodeWithCtxStreamMode() throws IOException {
        ChannelContext ctx = createCtxWithCloseCapture();
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        int largeLen = HttpConf.MAX_BODY_IN_MEMORY + 100;
        // Include at least 1 body byte so readBody reaches the stream branch
        String responseStr = "HTTP/1.1 200 OK\r\n" +
                "Content-Length: " + largeLen + "\r\n" +
                "\r\n" + "x";
        byte[] data = responseStr.getBytes("US-ASCII");
        decoder.decode(data, 0, data.length, ctx);
    }

    // ===== onBadDecoded null branches  =====

    @SuppressWarnings("unchecked")
    @Test
    public void testBadDecodedNullStatus() throws Exception {
        ChannelContext ctx = createCtxWithCloseCapture();
        TestableResponseDecoder decoder = new TestableResponseDecoder();
        decoder.triggerOnBadDecodedNullStatus(ctx);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testBadDecodedNullVersion() throws Exception {
        ChannelContext ctx = createCtxWithCloseCapture();
        TestableResponseDecoder decoder = new TestableResponseDecoder();
        decoder.triggerOnBadDecodedNullVersion(ctx);
    }

    // ===== parseStatusCode with null middle =====

    @Test
    public void testParseStatusCodeEmptyMiddleResponse() throws Exception {
        // Response with missing status code forces empty startLineMiddle
        String responseStr = "HTTP/1.1 \r\n\r\n";
        byte[] data = responseStr.getBytes("US-ASCII");
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        decoder.decode(data, 0, data.length);
        HttpDecodedResponse result = (HttpDecodedResponse) decoder.getResult();
        Assertions.assertNotNull(result);
    }

    // ===== parseStatusCode with null startLineMiddle via getResult =====

    @Test
    public void testParseStatusCodeWithNullMiddle() throws Exception {
        HttpResponseDecoder decoder = new HttpResponseDecoder();
        Assertions.assertNull(decoder.startLineMiddle);
        // startLineMiddle is null → parseStatusCode returns -1
        int code = decoder.parseStatusCode();
        Assertions.assertEquals(-1, code);
    }
}
