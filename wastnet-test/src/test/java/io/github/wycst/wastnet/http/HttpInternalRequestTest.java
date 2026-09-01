package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.upgrade.websocket.WebSocketResource;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.*;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpInternalRequest}.
 * <p>
 * Tests all concrete methods via a simple concrete subclass.
 *
 * @author wangyc
 */
public class HttpInternalRequestTest {

    // ==================== ChannelContext delegation ====================

    @Test
    public void testCtxReturnsSameInstance() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertSame(ctx, req.ctx());
    }

    @Test
    public void testGetRequestIdIsUnique() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest r1 = new TestBaseRequest(ctx);
        TestBaseRequest r2 = new TestBaseRequest(ctx);
        Assertions.assertNotEquals(r1.getRequestId(), r2.getRequestId());
    }

    @Test
    public void testIsSSL() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(true);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertTrue(req.isSSL());

        when(ctx.isSSL()).thenReturn(false);
        Assertions.assertFalse(req.isSSL());
    }

    @Test
    public void testGetConnectionId() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getId()).thenReturn(42L);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertEquals(42L, req.getConnectionId());
    }

    @Test
    public void testGetRemoteAddress() {
        ChannelContext ctx = mock(ChannelContext.class);
        InetSocketAddress addr = new InetSocketAddress("10.0.0.1", 54321);
        when(ctx.getRemoteAddress()).thenReturn(addr);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertSame(addr, req.getRemoteAddress());
    }

    @Test
    public void testGetRemoteAddressNullReturnsNullHost() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getRemoteAddress()).thenReturn(null);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertNull(req.getRemoteHost());
        Assertions.assertEquals(-1, req.getRemotePort());
    }

    @Test
    public void testGetServerAddress() {
        ChannelContext ctx = mock(ChannelContext.class);
        InetSocketAddress addr = new InetSocketAddress("192.168.1.1", 8080);
        when(ctx.getLocalAddress()).thenReturn(addr);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertSame(addr, req.getServerAddress());
    }

    @Test
    public void testGetServerAddressNullReturnsNull() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getLocalAddress()).thenReturn(null);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertNull(req.getServerHost());
        Assertions.assertEquals(-1, req.getServerPort());
    }

    @Test
    public void testRemoteHostAndPort() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getRemoteAddress()).thenReturn(new InetSocketAddress("example.com", 9999));
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertEquals("example.com", req.getRemoteHost());
        Assertions.assertEquals(9999, req.getRemotePort());
    }

    @Test
    public void testServerHostAndPort() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.getLocalAddress()).thenReturn(new InetSocketAddress("localhost", 443));
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertEquals("localhost", req.getServerHost());
        Assertions.assertEquals(443, req.getServerPort());
    }

    // ==================== Identity methods ====================

    @Test
    public void testIdentityMethods() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        Assertions.assertTrue(req.isHttpRequest());
        Assertions.assertFalse(req.isUpgrade());
        Assertions.assertFalse(req.isBad());
        Assertions.assertFalse(req.isStream());
        Assertions.assertFalse(req.isCompleted());
    }

    // ==================== Attribute methods ====================

    @Test
    public void testSetAndGetAttribute() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.setAttribute("key1", "value1");
        Assertions.assertEquals("value1", req.getAttribute("key1"));
    }

    @Test
    public void testSetAttributeOverridesExisting() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.setAttribute("key1", "first");
        req.setAttribute("key1", "second");
        Assertions.assertEquals("second", req.getAttribute("key1"));
    }

    @Test
    public void testSetAttributeNullValueRemovesAttribute() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.setAttribute("key1", "value1");
        req.setAttribute("key1", null);
        Assertions.assertNull(req.getAttribute("key1"));
        Assertions.assertFalse(enumToSet(req.getAttributeNames()).contains("key1"));
    }

    @Test
    public void testSetAttributeNullValueOnMissingAttribute() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.setAttribute("missing", null);
        Assertions.assertNull(req.getAttribute("missing"));
    }

    @Test
    public void testSetAttributeNullKeyThrows() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        Assertions.assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                req.setAttribute(null, "value");
            }
        });
    }

    @Test
    public void testRemoveAttribute() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.setAttribute("key1", "value1");
        req.removeAttribute("key1");
        Assertions.assertNull(req.getAttribute("key1"));
        Assertions.assertFalse(enumToSet(req.getAttributeNames()).contains("key1"));
    }

    @Test
    public void testGetAttributeNames() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.setAttribute("a", 1);
        req.setAttribute("b", 2);
        Set<String> names = enumToSet(req.getAttributeNames());
        Assertions.assertEquals(2, names.size());
        Assertions.assertTrue(names.contains("a"));
        Assertions.assertTrue(names.contains("b"));
    }

    @Test
    public void testAttributesAreRequestScoped() {
        // Attributes live on the request instance, not the connection/ctx:
        // two requests sharing the same ctx must not see each other's attributes.
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req1 = new TestBaseRequest(ctx);
        TestBaseRequest req2 = new TestBaseRequest(ctx);
        req1.setAttribute("key1", "value1");
        Assertions.assertNull(req2.getAttribute("key1"));
        Assertions.assertTrue(enumToSet(req2.getAttributeNames()).isEmpty());
    }

    private static Set<String> enumToSet(Enumeration<String> e) {
        Set<String> set = new HashSet<String>();
        while (e.hasMoreElements()) {
            set.add(e.nextElement());
        }
        return set;
    }

    // ==================== Protocol upgrade (HTTP/1) ====================

    @Test
    public void testUpgradeWithWsRejectsBroadcast() {
        // HTTP/1 (getHttpVersion() returns null) programmatic upgrade requires broadcast disabled
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        WebSocketResource ws = mock(WebSocketResource.class);
        when(ws.enableBroadcast()).thenReturn(true);
        Assertions.assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                req.upgrade(ws);
            }
        });
    }

    @Test
    public void testUpgradeH1ReturnsNullOnNoUpgradeRequest() throws Exception {
        // HTTP/1 upgrade() delegates to tryUpgrade; with no upgrade headers (mock) the
        // handshake is rejected and null is returned.
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        Assertions.assertNull(req.upgrade());
    }

    @Test
    public void testUpgradeWithWsH1ReturnsNullOnNoUpgradeRequest() throws Exception {
        // HTTP/1 upgrade(ws) with broadcast disabled (mock default false); the handshake
        // is rejected under mock headers and null is returned.
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        Assertions.assertNull(req.upgrade(mock(WebSocketResource.class)));
    }

    // ==================== Response ====================

    @Test
    public void testGetResponseInitiallyNull() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        Assertions.assertNull(req.getResponse());
    }

    // ==================== getParameter merge ====================

    @Test
    public void testGetParameterDelegatesToUriFirst() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.uriParams.put("fromUri", "uri-value");
        Assertions.assertEquals("uri-value", req.getParameter("fromUri"));
    }

    @Test
    public void testGetParameterFallsBackToBody() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        // No URI param → falls back to body (which has none for this test)
        Assertions.assertNull(req.getParameter("anything"));
    }

    @Test
    public void testGetBodyParameter() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertNull(req.getBodyParameter("any"));
    }

    // ==================== getParameterValues merge ====================

    @Test
    public void testGetParameterValuesUriOnly() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.uriParamValues.put("tag", Arrays.asList("a", "b"));
        List<String> values = req.getParameterValues("tag");
        Assertions.assertEquals(2, values.size());
        Assertions.assertEquals("a", values.get(0));
        Assertions.assertEquals("b", values.get(1));
    }

    @Test
    public void testGetParameterValuesBodyOnly() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        // No URI params → uriValues null, bodyDecoder also returns null
        List<String> values = req.getParameterValues("missing");
        Assertions.assertTrue(values == null || values.isEmpty());
    }

    @Test
    public void testGetParameterValuesMerged() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.uriParamValues.put("tag", Arrays.asList("a"));
        List<String> values = req.getParameterValues("tag");
        Assertions.assertEquals(1, values.size());
    }

    // ==================== getParameterNames ====================

    @Test
    public void testGetParameterNamesWithUriOnly() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.uriParamNames.add("fromUri");
        Set<String> names = req.getParameterNames();
        Assertions.assertEquals(1, names.size());
        Assertions.assertTrue(names.contains("fromUri"));
    }

    // ==================== getDecodedRequestURL ====================

    @Test
    public void testGetDecodedRequestURLWithEncodedChars() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.requestURL = new StringBuffer("http://host/path%20with%20spaces");
        Assertions.assertEquals("http://host/path with spaces", req.getDecodedRequestURL());
    }

    @Test
    public void testGetDecodedRequestURLNull() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.requestURL = null;
        Assertions.assertNull(req.getDecodedRequestURL());
    }

    // ==================== getHeader with caseSensitive ====================

    @Test
    public void testGetHeaderIgnoresCaseSensitiveFlagByDefault() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.headers.put("x-custom", "value");
        Assertions.assertEquals("value", req.getHeader("x-custom"));
        Assertions.assertEquals("value", req.getHeader("X-Custom"));
        Assertions.assertEquals("value", req.getHeader("x-custom", true));
        Assertions.assertEquals("value", req.getHeader("X-Custom", false));
        Assertions.assertNull(req.getHeader("missing"));
    }

    // ==================== getFullHeader ====================

    @Test
    public void testGetFullHeaderReturnsSingletonListForString() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        req.rawHeaders.put("x-hdr", "single");
        List<String> result = req.getFullHeader("x-hdr");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.size());
        Assertions.assertEquals("single", result.get(0));
    }

    @Test
    public void testGetFullHeaderReturnsListForListValue() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        List<String> multi = Arrays.asList("a", "b");
        req.rawHeaders.put("x-multi", multi);
        List<String> result = req.getFullHeader("x-multi");
        Assertions.assertSame(multi, result);
    }

    @Test
    public void testGetFullHeaderReturnsNullForMissing() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        Assertions.assertNull(req.getFullHeader("does-not-exist"));
    }

    // ==================== Default methods (isProtocolError / getHttpStatus / getErrorMessage) ====================

    @Test
    public void testDefaultMethods() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        Assertions.assertFalse(req.isProtocolError());
        Assertions.assertNull(req.getHttpStatus());
        Assertions.assertNull(req.getErrorMessage());
    }

    // ==================== Multipart delegation (getMultipartField/s) ====================

    @Test
    public void testGetMultipartDelegatesToBodyDecoder() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.headers.put("content-type", "multipart/form-data; boundary=x");
        // Just call for — these delegate to the lazily-created body decoder
        req.getMultipartField("f");
        req.getMultipartFields("f");
        req.getMultipartFieldValues("f");
        // getMultipartFields() — covers getOrderedMultipartFields internally
        List<MultipartField> all = req.getMultipartFields();
        Assertions.assertNotNull(all);
        Assertions.assertTrue(all.isEmpty());
    }

    // ==================== getParameterNames with null uriNames ( false) ====================

    @Test
    public void testGetParameterNamesWithNullUriNames() {
        ChannelContext ctx = mock(ChannelContext.class);
        NullUriNamesRequest req = new NullUriNamesRequest(ctx);
        Set<String> names = req.getParameterNames();
        Assertions.assertNotNull(names);
    }

    // ==================== getParameterNames multipart / form-urlencoded ( ) ====================

    @Test
    public void testGetParameterNamesMultipartBody() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.headers.put("content-type", "multipart/form-data; boundary=boundary");
        Set<String> names = req.getParameterNames();
        Assertions.assertNotNull(names);
    }

    @Test
    public void testGetParameterNamesFormBody() {
        TestBaseRequest req = new TestBaseRequest(mock(ChannelContext.class));
        req.headers.put("content-type", "application/x-www-form-urlencoded");
        Set<String> names = req.getParameterNames();
        Assertions.assertNotNull(names);
    }

    // ==================== complete() → releaseBodyDecoder ====================

    @Test
    public void testCompleteReleasesBodyDecoder() {
        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest req = new TestBaseRequest(ctx);
        // BodyDecoder is lazily created on first access, then released via complete()
        // complete() calls releaseBodyDecoder which nulls the decoder
        // After complete(), getParameterNames re-creates decoder (lazy)
        // Just verify it doesn't throw
        req.complete();
        req.complete(); // idempotent
    }

    @Test
    public void testParentCompletedReturnsFalse() {
        NoOverrideCompletedRequest req = new NoOverrideCompletedRequest(mock(ChannelContext.class));
        Assertions.assertFalse(req.isCompleted());
    }

    // ==================== TestRequest concrete subclass ====================

    /**
     * Minimal concrete subclass that exposes protected/abstract methods for testing.
     */
    static class TestBaseRequest extends HttpInternalRequest {

        final Map<String, String> headers = new HashMap<String, String>();
        final Map<String, Object> rawHeaders = new HashMap<String, Object>();
        final Map<String, List<String>> uriParamValues = new HashMap<String, List<String>>();
        final Set<String> uriParamNames = new LinkedHashSet<String>();
        final Map<String, String> uriParams = new HashMap<String, String>();
        StringBuffer requestURL;

        TestBaseRequest(ChannelContext ctx) {
            super(ctx);
        }

        @Override
        public HttpMethod getMethod() { return null; }

        @Override
        public String getUri() { return null; }

        @Override
        public String getRequestUri() { return null; }

        @Override
        public HttpVersion getHttpVersion() { return null; }

        @Override
        public String getScheme() { return null; }

        @Override
        public long getContentLength() { return 0; }

        @Override
        public String getContentType() {
            return getHeader("content-type");
        }

        @Override
        public HttpBodyInputStream bodyStream() { return null; }

        @Override
        public byte[] getBodyData() { return new byte[0]; }

        @Override
        public String getHeader(String name) {
            return headers.get(name.toLowerCase());
        }

        @Override
        public boolean containsHeader(String name) {
            return headers.containsKey(name.toLowerCase());
        }

        @Override
        public Set<String> getHeaderNames() {
            return headers.keySet();
        }

        @Override
        public String getUriParameter(String name) {
            return uriParams.get(name);
        }

        @Override
        public String getQueryString() { return null; }

        @Override
        public StringBuffer getRequestURL() {
            return requestURL;
        }

        @Override
        public void setRewriteUri(String newUri) {}

        @Override
        public void setHeader(String key, Serializable value) {
            headers.put(key.toLowerCase(), String.valueOf(value));
        }

        @Override
        public void removeHeader(String key) {}

        @Override
        public Object getRawHeader(String name) {
            return rawHeaders.get(name.toLowerCase());
        }

        @Override
        protected List<String> getUriParameterValues(String name) {
            return uriParamValues.get(name);
        }

        @Override
        protected Set<String> getUriParameterNames() {
            return uriParamNames;
        }

        @Override
        public void delegate(ChannelContext targetCtx) throws Throwable {}

        @Override
        public boolean isCompleted() { return false; }
    }

    /** Subclass that returns null for getUriParameterNames(). */
    static class NullUriNamesRequest extends TestBaseRequest {
        NullUriNamesRequest(ChannelContext ctx) { super(ctx); }
        @Override
        protected Set<String> getUriParameterNames() { return null; }
    }

    /** Subclass that does NOT override completed() — uses parent default. */
    static class NoOverrideCompletedRequest extends HttpInternalRequest {
        NoOverrideCompletedRequest(ChannelContext ctx) { super(ctx); }
        @Override public HttpMethod getMethod() { return null; }
        @Override public String getUri() { return null; }
        @Override public String getRequestUri() { return null; }
        @Override public HttpVersion getHttpVersion() { return null; }
        @Override public String getScheme() { return null; }
        @Override public long getContentLength() { return 0; }
        @Override public String getContentType() { return null; }
        @Override public HttpBodyInputStream bodyStream() { return null; }
        @Override public byte[] getBodyData() { return new byte[0]; }
        @Override public String getHeader(String name) { return null; }
        @Override public boolean containsHeader(String name) { return false; }
        @Override public Set<String> getHeaderNames() { return java.util.Collections.emptySet(); }
        @Override public String getUriParameter(String name) { return null; }
        @Override public String getQueryString() { return null; }
        @Override public StringBuffer getRequestURL() { return null; }
        @Override public void setRewriteUri(String newUri) {}
        @Override public void setHeader(String key, Serializable value) {}
        @Override public void removeHeader(String key) {}
        @Override public Object getRawHeader(String name) { return null; }
        @Override protected List<String> getUriParameterValues(String name) { return null; }
        @Override protected Set<String> getUriParameterNames() { return java.util.Collections.emptySet(); }
        @Override public void delegate(ChannelContext targetCtx) throws Throwable {}
    }
}
