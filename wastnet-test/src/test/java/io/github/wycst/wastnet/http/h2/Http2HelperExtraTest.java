package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * Supplementary coverage for {@link Http2Helper} branch/streaming paths that the
 * existing {@link Http2HelperTest} does not exercise:
 * <ul>
 *   <li>chunked (streaming) server-&gt;H1 / H1-&gt;H2 / H2-&gt;H2 body forwarding</li>
 *   <li>absolute-form URI parsing (and malformed URI catch) in H2 request header emission</li>
 *   <li>multi-value header emission and the non-GET/POST, https, custom-path pseudo-header branches</li>
 * </ul>
 */
public class Http2HelperExtraTest {

    private static Http2ServerReader mockServerReader() {
        Http2ServerReader reader = mock(Http2ServerReader.class);
        H2TestHelper.initMockReader(reader);
        H2TestHelper.installCodec(reader);
        H2TestHelper.setConnectRecvWindow(reader, 65535);
        reader.streamInitSendWindowSize = 65535;
        reader.maxSendPayloadSize = 16384;
        return reader;
    }

    private static Http2ClientReader mockClientReader() {
        Http2ClientReader reader = mock(Http2ClientReader.class);
        H2TestHelper.initMockReader(reader);
        H2TestHelper.installCodec(reader);
        H2TestHelper.setConnectRecvWindow(reader, 65535);
        reader.streamInitSendWindowSize = 65535;
        reader.maxSendPayloadSize = 16384;
        return reader;
    }

    private static Http2ServerStream serverStream() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65535);
        return new Http2ServerStream(mockServerReader(), 1, ctx);
    }

    private static Http2ClientStream clientStream() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getWriteBufferSize()).thenReturn(65535);
        return new Http2ClientStream(mockClientReader(), 1, ctx);
    }

    @Test
    public void testEncodeH2RequestHeadersVariants() throws Exception {
        // DELETE + https + /index.html -> non-GET/POST, https (0x87), /index.html (0x85)
        Http2ServerStream s1 = serverStream();
        s1.method = HttpMethod.DELETE;
        s1.scheme = "https";
        s1.authority = "host";
        s1.path = "/index.html";
        s1.headers.put(":method", "DELETE");
        s1.headers.put(":scheme", "https");
        s1.headers.put(":authority", "host");
        s1.headers.put(":path", "/index.html");
        s1.headers.put("x-test", "1");
        Http2Helper.encodeH2RequestHeaders(s1);

        // PUT + https + /custom -> custom path (writeHpackLiteral)
        Http2ServerStream s2 = serverStream();
        s2.method = HttpMethod.PUT;
        s2.scheme = "https";
        s2.authority = "host";
        s2.path = "/custom";
        s2.headers.put(":method", "PUT");
        s2.headers.put(":scheme", "https");
        s2.headers.put(":authority", "host");
        s2.headers.put(":path", "/custom");
        s2.headers.put("x-test", "1");
        Http2Helper.encodeH2RequestHeaders(s2);

        // GET + http + / -> GET, http (0x86) and "/" (0xc0) branches
        Http2ServerStream s3 = serverStream();
        s3.method = HttpMethod.GET;
        s3.scheme = "http";
        s3.authority = "host";
        s3.path = "/";
        s3.headers.put(":method", "GET");
        s3.headers.put(":scheme", "http");
        s3.headers.put(":authority", "host");
        s3.headers.put(":path", "/");
        s3.headers.put("x-test", "1");
        Http2Helper.encodeH2RequestHeaders(s3);
    }

    @Test
    public void testSendH1RequestChunked() throws Exception {
        Http2ServerStream stream = serverStream();
        stream.method = HttpMethod.POST;
        stream.path = "/x";
        stream.needStreaming = true;
        stream.bodyData = null;
        stream.bodyStream = new Http2BodyInputStream("hello".getBytes(), stream);
        stream.bodyStream.endStream();
        ChannelContext targetCtx = mock(ChannelContext.class);
        Http2Helper.sendH1Request(stream, targetCtx);
        verify(targetCtx).flush();
    }

    @Test
    public void testSendH2RequestBodyChunked() throws Exception {
        Http2ServerStream server = serverStream();
        server.needStreaming = true;
        server.bodyData = null;
        server.bodyStream = new Http2BodyInputStream("hello".getBytes(), server);
        server.bodyStream.endStream();
        Http2ClientStream target = clientStream();
        Http2Helper.sendH2RequestBody(server, target);
    }

    @Test
    public void testSendH1RequestBodyChunked() throws Exception {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getBodyData()).thenReturn(null);
        when(request.isStream()).thenReturn(true);
        when(request.bodyStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        Http2ClientStream target = clientStream();
        Http2Helper.sendH1RequestBody(request, target);
    }

    @Test
    public void testSendH2RequestHeadersFromH1Absolute() throws Exception {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getUri()).thenReturn("https://example.com/p?q=1");
        when(request.getHeader("host")).thenReturn(null);
        when(request.getHeaderNames()).thenReturn(Collections.<String>emptySet());
        when(request.getScheme()).thenReturn("https");
        Http2ClientStream target = clientStream();
        Http2Helper.sendH2RequestHeadersFromH1(request, target);
    }

    @Test
    public void testSendH2RequestHeadersFromH1Malformed() throws Exception {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getUri()).thenReturn("http://[bad");
        when(request.getHeader("host")).thenReturn(null);
        when(request.getHeaderNames()).thenReturn(Collections.<String>emptySet());
        Http2ClientStream target = clientStream();
        Http2Helper.sendH2RequestHeadersFromH1(request, target);
    }

    @Test
    public void testSendH2RequestHeadersFromH1ListHeader() throws Exception {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getUri()).thenReturn("/");
        when(request.getHeader("host")).thenReturn(null);
        Set<String> names = new HashSet<String>();
        names.add("x-multi");
        names.add("x-single");
        when(request.getHeaderNames()).thenReturn(names);
        when(request.getRawHeader("x-multi")).thenReturn(Arrays.asList("a", "b"));
        when(request.getRawHeader("x-single")).thenReturn("v");
        Http2ClientStream target = clientStream();
        Http2Helper.sendH2RequestHeadersFromH1(request, target);
    }
}
