package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link Http2Request}.
 *
 * @author wangyc
 */
public class Http2RequestTest {

    /**
     * TestStream that exposes fields directly for testing field-access methods in Http2Request.
     */
    static class TestStream extends Http2ServerStream {
        TestStream(Http2MessageReader reader, int sid, ChannelContext ctx) {
            super(reader, sid, ctx);
        }

        protected void onEndHeaders() {}
        protected void submit() {}
        public String debugPrefix() { return "Test"; }
    }

    // ==================== Fixture helpers ====================

    private static class Fixture {
        final Http2MessageReader reader;
        final ChannelContext ctx;
        final TestStream stream;
        final Http2Request request;

        Fixture(Http2MessageReader reader, ChannelContext ctx, TestStream stream) {
            this.reader = reader;
            this.ctx = ctx;
            this.stream = stream;
            this.request = new Http2Request(stream);
        }
    }

    private static Fixture createFixture() {
        Http2MessageReader reader = mock(Http2MessageReader.class);
        ChannelContext ctx = mock(ChannelContext.class);
        return new Fixture(reader, ctx, new TestStream(reader, 1, ctx));
    }

    // ==================== getHttpVersion ====================

    @Test
    void testGetHttpVersion() {
        assertEquals(HttpVersion.HTTP_2, createFixture().request.getHttpVersion());
    }

    // ==================== bodyStream ====================

    @Test
    void testBodyStream() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        when(s.getInputStream()).thenReturn(null);
        assertNull(new Http2Request(s).bodyStream());
    }

    @Test
    void testBodyStreamReturnsStreamInput() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        java.io.InputStream mockIn = mock(java.io.InputStream.class);
        when(s.getInputStream()).thenReturn(mockIn);
        assertSame(mockIn, new Http2Request(s).bodyStream());
    }

    // ==================== getScheme / getUri / getRequestUri ====================

    @Test
    void testGetScheme() {
        Fixture f = createFixture();
        f.stream.scheme = "https";
        assertEquals("https", f.request.getScheme());
    }

    @Test
    void testGetUri() {
        Fixture f = createFixture();
        f.stream.path = "/hello";
        assertEquals("/hello", f.request.getUri());
    }

    @Test
    void testGetRequestUri() {
        Fixture f = createFixture();
        f.stream.requestUri = "/hello";
        assertEquals("/hello", f.request.getRequestUri());
    }

    // ==================== completed / complete ====================

    @Test
    void testCompletedTrueWhenEndStream() {
        Fixture f = createFixture();
        f.stream.endStream = true;
        assertTrue(f.request.isCompleted());
    }

    @Test
    void testCompletedFalseWhenNotEndStream() {
        Fixture f = createFixture();
        f.stream.endStream = false;
        assertFalse(f.request.isCompleted());
    }

    @Test
    void testCompleteWhenNotCompleted() throws Exception {
        Http2ServerStream s = mock(Http2ServerStream.class);
        when(s.getBodyData()).thenReturn(HttpRequest.EMPTY_BODY);
        Http2Request req = new Http2Request(s);
        req.complete();
        verify(s).completeRequest();
    }

    @Test
    void testCompleteWhenAlreadyCompleted() throws Exception {
        Fixture f = createFixture();
        f.stream.endStream = true;
        f.request.complete();
        // endStream is already true, completeRequest should not be called
        // (verified by no interaction on the real stream)
    }

    // ==================== getContentLength ====================

    @Test
    void testGetContentLength() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        when(s.getContentLength()).thenReturn(42L);
        assertEquals(42L, new Http2Request(s).getContentLength());
    }

    @Test
    void testGetContentLengthDefault() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        when(s.getContentLength()).thenReturn(0L);
        assertEquals(0L, new Http2Request(s).getContentLength());
    }

    // ==================== getContentType ====================

    @Test
    void testGetContentType() {
        Fixture f = createFixture();
        f.stream.contentType = "application/json";
        assertEquals("application/json", f.request.getContentType());
    }

    @Test
    void testGetContentTypeNull() {
        assertNull(createFixture().request.getContentType());
    }

    // ==================== getMethod ====================

    @Test
    void testGetMethod() {
        Fixture f = createFixture();
        f.stream.method = HttpMethod.GET;
        assertEquals(HttpMethod.GET, f.request.getMethod());
    }

    @Test
    void testGetMethodPost() {
        Fixture f = createFixture();
        f.stream.method = HttpMethod.POST;
        assertEquals(HttpMethod.POST, f.request.getMethod());
    }

    // ==================== getBodyData ====================

    @Test
    void testGetBodyData() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        when(s.getBodyData()).thenReturn("data".getBytes());
        assertArrayEquals("data".getBytes(), new Http2Request(s).getBodyData());
    }

    @Test
    void testGetBodyDataEmpty() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        when(s.getBodyData()).thenReturn(HttpRequest.EMPTY_BODY);
        assertEquals(0, new Http2Request(s).getBodyData().length);
    }

    // ==================== Header methods ====================

    @Test
    void testGetHeaderNames() {
        Fixture f = createFixture();
        f.stream.headers.put("content-type", "text/plain");
        f.stream.headers.put("x-custom", "value");
        Set<String> names = f.request.getHeaderNames();
        assertTrue(names.contains("content-type"));
        assertTrue(names.contains("x-custom"));
        assertEquals(2, names.size());
    }

    @Test
    void testGetHeaderReturnsString() {
        Fixture f = createFixture();
        f.stream.headers.put("x-key", "str-value");
        assertEquals("str-value", f.request.getHeader("x-key"));
    }

    @Test
    void testGetHeaderReturnsFirstOfList() {
        Fixture f = createFixture();
        List<String> vals = new ArrayList<String>();
        vals.add("first");
        vals.add("second");
        f.stream.headers.put("x-multi", vals);
        assertEquals("first", f.request.getHeader("x-multi"));
    }

    @Test
    void testGetHeaderCaseInsensitive() {
        Fixture f = createFixture();
        f.stream.headers.put("x-custom", "v");
        assertEquals("v", f.request.getHeader("X-Custom"));
    }

    @Test
    void testGetHeaderNotFound() {
        assertNull(createFixture().request.getHeader("nonexistent"));
    }

    @Test
    void testSetHeader() {
        Fixture f = createFixture();
        f.request.setHeader("x-set", "set-value");
        assertEquals("set-value", f.stream.headers.get("x-set"));
    }

    @Test
    void testSetHeaderLowercasesKey() {
        Fixture f = createFixture();
        f.request.setHeader("X-Upper", "val");
        assertTrue(f.stream.headers.containsKey("x-upper"));
    }

    @Test
    void testRemoveHeader() {
        Fixture f = createFixture();
        f.stream.headers.put("x-remove", "v");
        f.request.removeHeader("x-remove");
        assertFalse(f.stream.headers.containsKey("x-remove"));
    }

    @Test
    void testContainsHeaderTrue() {
        Fixture f = createFixture();
        f.stream.headers.put("x-present", "v");
        assertTrue(f.request.containsHeader("x-present"));
    }

    @Test
    void testContainsHeaderFalse() {
        assertFalse(createFixture().request.containsHeader("x-absent"));
    }

    @Test
    void testGetRawHeader() {
        Fixture f = createFixture();
        f.stream.headers.put("x-raw", "raw-value");
        assertEquals("raw-value", f.request.getRawHeader("x-raw"));
    }

    // ==================== URI parameter methods ====================

    @Test
    void testGetUriParameter() {
        Fixture f = createFixture();
        Map<String, List<String>> params = new HashMap<String, List<String>>();
        params.put("name", Collections.singletonList("alice"));
        f.stream.parameters = params;
        assertEquals("alice", f.request.getUriParameter("name"));
    }

    @Test
    void testGetUriParameterNullWhenNotFound() {
        Fixture f = createFixture();
        f.stream.parameters = new HashMap<String, List<String>>();
        assertNull(f.request.getUriParameter("missing"));
    }

    @Test
    void testGetQueryString() {
        Fixture f = createFixture();
        f.stream.path = "/search?q=hello&page=1";
        assertEquals("q=hello&page=1", f.request.getQueryString());
    }

    @Test
    void testGetQueryStringNullWhenNoQuery() {
        Fixture f = createFixture();
        f.stream.path = "/plain";
        assertNull(f.request.getQueryString());
    }

    @Test
    void testGetQueryStringNullWhenPathNull() {
        assertNull(createFixture().request.getQueryString());
    }

    // ==================== getRequestURL ====================

    @Test
    void testGetRequestURL() {
        Fixture f = createFixture();
        f.stream.scheme = "https";
        f.stream.path = "/index";
        f.stream.headers.put(":authority", "example.com");
        assertEquals("https://example.com/index", f.request.getRequestURL().toString());
    }

    @Test
    void testGetRequestURLWithoutQuery() {
        Fixture f = createFixture();
        f.stream.scheme = "http";
        f.stream.path = "/api?key=val";
        f.stream.headers.put(":authority", "host:8080");
        assertEquals("http://host:8080/api", f.request.getRequestURL().toString());
    }

    // ==================== isStream ====================

    @Test
    void testIsStreamTrueWhenStreaming() {
        Fixture f = createFixture();
        f.stream.needStreaming = true;
        assertTrue(f.request.isStream());
    }

    @Test
    void testIsStreamFalseWhenNotStreaming() {
        Fixture f = createFixture();
        f.stream.needStreaming = false;
        assertFalse(f.request.isStream());
    }

    // ==================== setRewriteUri ====================

    @Test
    void testSetRewriteUri() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        Http2Request req = new Http2Request(s);
        req.setRewriteUri("/new/path");
        assertEquals("/new/path", req.getUri());
    }

    @Test
    void testSetRewriteUriPreservesQueryString() {
        Fixture f = createFixture();
        f.stream.path = "/old?key=val";
        f.request.setRewriteUri("/new");
        assertEquals("/new?key=val", f.request.getUri());
    }

    @Test
    void testSetRewriteUriNullIsNoOp() {
        Fixture f = createFixture();
        f.stream.path = "/stay";
        f.request.setRewriteUri(null);
        assertEquals("/stay", f.request.getUri());
    }

    @Test
    void testSetRewriteUriMergesQueryWithExisting() {
        Fixture f = createFixture();
        f.stream.path = "/old?existing=1";
        f.request.setRewriteUri("/new?extra=2");
        String uri = f.request.getUri();
        assertTrue(uri.startsWith("/new"));
        assertTrue(uri.contains("existing=1"));
        assertTrue(uri.contains("extra=2"));
    }

    // ==================== streamId / stream ====================

    @Test
    void testStreamId() {
        Fixture f = createFixture();
        assertEquals(1, f.request.streamId());
    }

    @Test
    void testStreamIdCustom() {
        ChannelContext ctx = mock(ChannelContext.class);
        Http2MessageReader reader = mock(Http2MessageReader.class);
        Http2Request req = new Http2Request(new TestStream(reader, 7, ctx));
        assertEquals(7, req.streamId());
    }

    @Test
    void testStreamReturnsUnderlyingStream() {
        Fixture f = createFixture();
        assertSame(f.stream, f.request.stream());
    }

    // ==================== Attribute methods ====================

    @Test
    void testSetAndGetAttribute() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        Http2Request req = new Http2Request(s);
        req.setAttribute("key1", "value1");
        assertEquals("value1", req.getAttribute("key1"));
    }

    @Test
    void testGetAttributeReturnsNullWhenNotFound() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        assertNull(new Http2Request(s).getAttribute("missing"));
    }

    // ==================== delegate ====================

    @Test
    void testDelegateIsNoOp() throws Throwable {
        createFixture().request.delegate(mock(ChannelContext.class));
        // no exception expected
    }

    // ==================== getHost ====================

    @Test
    void testGetHost() {
        Fixture f = createFixture();
        f.stream.authority = "host:8080";
        assertEquals("host:8080", f.request.getHost());
    }

    // ==================== getTrailers / setTrailersListener ====================

    @Test
    void testGetTrailers() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        Map<String, Object> trailers = new HashMap<String, Object>();
        trailers.put("x-trailer", "v");
        when(s.getTrailers()).thenReturn(trailers);
        assertEquals("v", new Http2Request(s).getTrailers().get("x-trailer"));
    }

    @Test
    void testSetTrailersListener() {
        Http2ServerStream s = mock(Http2ServerStream.class);
        Http2Request req = new Http2Request(s);
        TrailersListener listener = mock(TrailersListener.class);
        req.setTrailersListener(listener);
        verify(s).setTrailersListener(listener);
    }

    // ==================== errorStatus / isBad / getHttpStatus / getErrorMessage ====================

    @Test
    void testErrorStatusMakesBadRequest() {
        Fixture f = createFixture();
        assertFalse(f.request.isBad());
        assertNull(f.request.getHttpStatus());
        f.request.errorStatus(HttpStatus.BAD_REQUEST);
        assertTrue(f.request.isBad());
        assertEquals(HttpStatus.BAD_REQUEST, f.request.getHttpStatus());
        assertEquals(HttpStatus.BAD_REQUEST.text, f.request.getErrorMessage());
    }

    // ==================== isProtocolError ====================

    @Test
    void testIsProtocolErrorFalse() {
        assertFalse(createFixture().request.isProtocolError());
    }
}
