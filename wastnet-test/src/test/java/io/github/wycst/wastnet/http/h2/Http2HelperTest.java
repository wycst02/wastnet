package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2Helper}.
 *
 * @author wangyc
 */
public class Http2HelperTest {

    // ==================== Fixture ====================

    private static Http2ServerStream createServerStream(int streamId, ChannelContext ctx) {
        Http2ServerReader reader = mock(Http2ServerReader.class);
        H2TestHelper.initMockReader(reader);
        reader.streamInitSendWindowSize = 65535;
        reader.connectSendWindow = 65535;
        reader.maxSendPayloadSize = 16384;
        when(ctx.getWriteBufferSize()).thenReturn(65535);
        return new Http2ServerStream(reader, streamId, ctx);
    }

    private static void setupStream(Http2ServerStream stream, String method, String path, String authority, byte[] body) {
        stream.headers.put(":method", method);
        stream.headers.put(":scheme", "http");
        stream.headers.put(":path", path);
        stream.headers.put(":authority", authority);
        stream.method = HttpMethod.valueOf(method);
        stream.path = path;
        stream.authority = authority;
        stream.bodyData = body;
    }

    // ==================== sendGatewayError ====================

    @Test
    public void testSendGatewayError() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2Stream stream = createServerStream(1, mockCtx);

        Http2Helper.sendGatewayError(stream);
        verify(mockCtx, atLeastOnce()).writeFlush(any(java.nio.ByteBuffer.class));
    }

    // ==================== sendH1Request ====================

    @Test
    public void testSendH1RequestNoBody() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(1, mockCtx);
        setupStream(stream, "GET", "/test", "example.com", HttpRequest.EMPTY_BODY);

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    @Test
    public void testSendH1RequestWithBody() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(2, mockCtx);
        setupStream(stream, "POST", "/submit", "example.com", "hello".getBytes());
        stream.headers.put("content-type", "text/plain");

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    @Test
    public void testSendH1RequestWithCustomHeaders() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(3, mockCtx);
        setupStream(stream, "GET", "/api", "example.com", HttpRequest.EMPTY_BODY);
        stream.headers.put("x-custom", "custom-val");
        stream.headers.put("accept", "application/json");

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    @Test
    public void testSendH1RequestNullPathDefaultsToSlash() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(4, mockCtx);
        setupStream(stream, "GET", null, "example.com", HttpRequest.EMPTY_BODY);

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx).write(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testSendH1RequestSkippedHeaders() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(5, mockCtx);
        setupStream(stream, "GET", "/skip", "example.com", HttpRequest.EMPTY_BODY);
        // These should be skipped
        stream.headers.put("content-length", "100");
        stream.headers.put("transfer-encoding", "chunked");

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    @Test
    public void testSendH1RequestWithHostHeaderInMap() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(6, mockCtx);
        setupStream(stream, "GET", "/host", "example.com", HttpRequest.EMPTY_BODY);
        // Explicit HOST header in map → sets writeHost flag
        stream.headers.put("host", "explicit-host.com");

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    @Test
    public void testSendH1RequestWithListHeader() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(7, mockCtx);
        setupStream(stream, "GET", "/list", "example.com", HttpRequest.EMPTY_BODY);
        // Header with multiple values (List)
        List<String> multiValues = new ArrayList<String>();
        multiValues.add("v1");
        multiValues.add("v2");
        stream.headers.put("x-multi", multiValues);

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    @Test
    public void testSendH1RequestWithNullHeaderValue() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(8, mockCtx);
        setupStream(stream, "GET", "/null", "example.com", HttpRequest.EMPTY_BODY);
        // Header with null value → should be skipped
        stream.headers.put("x-null", null);

        Http2Helper.sendH1Request(stream, mockCtx);
        verify(mockCtx, atLeastOnce()).flush();
    }

    // ==================== writeResponse ====================

    @Test
    public void testWriteResponseWithBody() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(4, mockCtx);

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("content-type", "text/plain");
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK",
                headers, "hello world".getBytes(), 11, "text/plain",
                false, null);

        Http2Helper.writeResponse(stream, response);
        verify(mockCtx, atLeast(2)).writeFlush(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testWriteResponseNoBody() throws Exception {
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2ServerStream stream = createServerStream(5, mockCtx);

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 204, "No Content",
                headers, new byte[0], 0, null,
                false, null);

        Http2Helper.writeResponse(stream, response);
        verify(mockCtx).writeFlush(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testWriteResponseWithBodyExceedsChunkSize() throws Exception {
        // Body > 16384 chunkSize to trigger writeDataFrame multiple times
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2Stream stream = createServerStream(6, mockCtx);

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("content-type", "application/octet-stream");
        byte[] body = new byte[17000];
        Arrays.fill(body, (byte) 'X');
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 200, "OK",
                headers, body, 17000, "application/octet-stream",
                false, null);

        Http2Helper.writeResponse(stream, response);
        verify(mockCtx, atLeast(2)).writeFlush(any(java.nio.ByteBuffer.class));
    }

    @Test
    public void testWriteResponseWithZeroContentLengthStillHasBody() throws Exception {
        // contentLength=0 but body has data → hasBody should be false (0 > 0 is false)
        ChannelContext mockCtx = mock(ChannelContext.class);
        Http2Stream stream = createServerStream(7, mockCtx);

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        HttpDecodedResponse response = new HttpDecodedResponse(
                HttpVersion.HTTP_1_1, 304, "Not Modified",
                headers, new byte[0], 0, null,
                false, null);

        Http2Helper.writeResponse(stream, response);
        verify(mockCtx).writeFlush(any(java.nio.ByteBuffer.class));
    }

    // 流式 body 的 writeResponse 需要 NIO 数据流支持，不适合纯单元测试。
}
