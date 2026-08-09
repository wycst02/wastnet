package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpDecodedRequest}.
 *
 * @author wangyc
 */
public class HttpDecodedRequestTest {

    @Test
    public void testSetRewriteUri() {
        DefaultRequestWithQuery req = createRequestWithQuery("/old/path", "name=value");
        Assertions.assertEquals("/old/path?name=value", req.getUri());
        req.setRewriteUri("/new/path");
        Assertions.assertEquals("/new/path?name=value", req.getUri());
    }

    @Test
    public void testSetRewriteUriPreservesQueryString() {
        DefaultRequestWithQuery req = createRequestWithQuery("/old", "a=1");
        req.setRewriteUri("/new");
        Assertions.assertEquals("/new?a=1", req.getUri());
    }

    @Test
    public void testSetRewriteUriWithNewQueryString() {
        DefaultRequestWithQuery req = createRequestWithQuery("/old", null);
        req.setRewriteUri("/new?b=2");
        Assertions.assertEquals("/new?b=2", req.getUri());
    }

    @Test
    public void testSetRewriteUriNullIgnored() {
        DefaultRequestWithQuery req = createRequestWithQuery("/path", "k=v");
        req.setRewriteUri(null);
        Assertions.assertEquals("/path?k=v", req.getUri());
    }

    @Test
    public void testSetRewriteUriWithNoQuery() {
        DefaultRequestWithQuery req = createRequestWithQuery("/path", null);
        req.setRewriteUri("/rewritten");
        Assertions.assertEquals("/rewritten", req.getUri());
    }

    @Test
    public void testSetRewriteUriEndsWithQuestionMark() {
        // rewrite URI ending with ? → query appended directly
        DefaultRequestWithQuery req = createRequestWithQuery("/old", "a=1");
        req.setRewriteUri("/new?");
        Assertions.assertEquals("/new?a=1", req.getUri());
    }

    @Test
    public void testSetRewriteUriWithExistingQueryAppend() {
        // rewrite URI already has ? + params → append with &
        DefaultRequestWithQuery req = createRequestWithQuery("/old", "a=1");
        req.setRewriteUri("/new?existing=keep");
        Assertions.assertEquals("/new?existing=keep&a=1", req.getUri());
    }

    @Test
    public void testSetRemoveHeader() {
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("x-custom", "value1");
        DefaultRequestWithQuery req = createRequestWithHeaders("/path", headers);
        Assertions.assertEquals("value1", req.getHeader("x-custom"));
        req.removeHeader("x-custom");
        Assertions.assertNull(req.getHeader("x-custom"));
    }

    @Test
    public void testSetHeaderOverwrite() {
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("x-custom", "old");
        DefaultRequestWithQuery req = createRequestWithHeaders("/path", headers);
        req.setHeader("x-custom", "new");
        Assertions.assertEquals("new", req.getHeader("x-custom"));
    }

    @Test
    public void testGetParameterNames() {
        Map<String, List<String>> params = new LinkedHashMap<String, List<String>>();
        params.put("a", Collections.singletonList("1"));
        params.put("b", Collections.singletonList("2"));
        DefaultRequestWithQuery req = createRequestWithParams("/path", params);
        Set<String> names = req.getParameterNames();
        Assertions.assertTrue(names.contains("a"));
        Assertions.assertTrue(names.contains("b"));
    }

    @Test
    public void testGetHttpVersion() {
        DefaultRequestWithQuery req = createRequestWithQuery("/path", null);
        Assertions.assertEquals(HttpVersion.HTTP_1_1, req.getHttpVersion());
    }

    // ==================== Additional coverage ====================

    @Test
    public void testGetSchemeSSL() {
        // SSL context → scheme = https
        DefaultRequestWithCtx req = createRequestWithCtx("/path", true);
        Assertions.assertEquals("https", req.getScheme());
    }

    @Test
    public void testGetUriParameterNamesWhenParamsNull() {
        // parameters == null → empty set
        DefaultRequestWithQuery req = new DefaultRequestWithQuery(
                HttpMethod.GET, "/path".getBytes(), "/path", null,
                HttpVersion.HTTP_1_1, new LinkedHashMap<String, Object>(), new byte[0], 0L, null
        );
        Assertions.assertTrue(req.getParameterNames().isEmpty());
    }

    @Test
    public void testGetQueryStringNoQuestionMark() {
        // rawUriBytes longer than requestUri but without ? → loop finishes, null returned
        DefaultRequestWithQuery req = new DefaultRequestWithQuery(
                HttpMethod.GET, "/path".getBytes(), "/p",
                new HashMap<String, List<String>>(),
                HttpVersion.HTTP_1_1, new LinkedHashMap<String, Object>(), new byte[0], 0L, null
        );
        Assertions.assertNull(req.getQueryString());
    }

    @Test
    public void testGetRequestURL() {
        DefaultRequestWithCtx req = createRequestWithCtx("/api/test", false);
        StringBuffer url = req.getRequestURL();
        Assertions.assertNotNull(url);
        Assertions.assertTrue(url.toString().contains("/api/test"));
    }

    @Test
    public void testGetRequestURLWithHostAndPort() {
        DefaultRequestWithCtx req = createRequestWithCtx("/path", false);
        StringBuffer url = req.getRequestURL();
        Assertions.assertNotNull(url);
    }

    @Test
    public void testGetRequestURLWithHostAndNonDefaultPort() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(false);
        when(ctx.getLocalAddress()).thenReturn(
                java.net.InetSocketAddress.createUnresolved("example.com", 8080));
        DefaultRequestWithCtx req = new DefaultRequestWithCtx("/api", ctx);
        StringBuffer url = req.getRequestURL();
        Assertions.assertTrue(url.toString().contains("example.com"));
        Assertions.assertTrue(url.toString().contains(":8080"));
    }

    @Test
    public void testGetRequestURLWithDefaultPort() {
        // port == defaultPort → port not appended
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(false);
        when(ctx.getLocalAddress()).thenReturn(
                java.net.InetSocketAddress.createUnresolved("host", 80));
        DefaultRequestWithCtx req = new DefaultRequestWithCtx("/p", ctx);
        StringBuffer url = req.getRequestURL();
        Assertions.assertFalse(url.toString().contains(":80"));
    }

    @Test
    public void testGetRequestURLWithSsl() {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(true);
        when(ctx.getLocalAddress()).thenReturn(
                java.net.InetSocketAddress.createUnresolved("secure.example.com", 443));
        DefaultRequestWithCtx req = new DefaultRequestWithCtx("/s", ctx);
        StringBuffer url = req.getRequestURL();
        Assertions.assertTrue(url.toString().contains("https"));
    }

    @Test
    public void testDelegateWithNullBody() throws Throwable {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(false);
        DefaultRequestWithCtx req = new DefaultRequestWithCtx("/p", ctx) {
            @Override
            public byte[] getBodyData() {
                return null;
            }
        };
        DefaultRequestWithCtx target = createRequestWithCtx("/t", false);
        req.delegate(target.ctx);
    }

    @Test
    public void testDelegateWithEmptyBody() throws Throwable {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(false);
        DefaultRequestWithCtx req = new DefaultRequestWithCtx("/p", ctx);
        DefaultRequestWithCtx target = createRequestWithCtx("/t", false);
        req.delegate(target.ctx);
    }

    @Test
    public void testDelegateWithNullHeader() throws Throwable {
        // header with null value → skipped in writeHeadersTo
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(false);
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("x-null", null);
        headers.put("x-valid", "ok");
        DefaultRequestWithCtx req = new DefaultRequestWithCtx("/p", ctx, headers);
        DefaultRequestWithCtx target = createRequestWithCtx("/t", false);
        req.delegate(target.ctx);
    }

    @Test
    public void testDelegateToSelf() throws Throwable {
        DefaultRequestWithCtx req = createRequestWithCtx("/path", false);
        req.delegate(req.ctx);
    }

    @Test
    public void testDelegateNullTarget() throws Throwable {
        DefaultRequestWithCtx req = createRequestWithCtx("/path", false);
        req.delegate(null);
    }

    @Test
    public void testDelegateToOther() throws Throwable {
        DefaultRequestWithCtx req = createRequestWithCtx("/path", false);
        DefaultRequestWithCtx targetReq = createRequestWithCtx("/other", false);
        req.delegate(targetReq.ctx);
    }

    @Test
    public void testGetHeaderListValue() {
        // getHeader with List<String> value type → get(0)
        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        List<String> list = new ArrayList<String>();
        list.add("first");
        headers.put("multi", list);
        DefaultRequestWithQuery req = createRequestWithHeaders("/path", headers);
        Assertions.assertEquals("first", req.getHeader("multi"));
    }

    // ==================== Helpers ====================

    private static DefaultRequestWithQuery createRequestWithQuery(String uri, String queryString) {
        String fullUri = queryString != null ? uri + "?" + queryString : uri;
        byte[] uriBytes = fullUri.getBytes();
        Map<String, List<String>> parameters = new LinkedHashMap<String, List<String>>();
        if (queryString != null) {
            String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                String key = kv[0];
                String val = kv.length > 1 ? kv[1] : "";
                List<String> values = new ArrayList<String>();
                values.add(val);
                parameters.put(key, values);
            }
        }
        return new DefaultRequestWithQuery(
                HttpMethod.GET, uriBytes, uri, parameters,
                HttpVersion.HTTP_1_1, new LinkedHashMap<String, Object>(), new byte[0], 0L, null
        );
    }

    private static DefaultRequestWithQuery createRequestWithHeaders(String uri, Map<String, Object> headers) {
        byte[] uriBytes = uri.getBytes();
        return new DefaultRequestWithQuery(
                HttpMethod.GET, uriBytes, uri, new HashMap<String, List<String>>(),
                HttpVersion.HTTP_1_1, headers, new byte[0], 0L, null
        );
    }

    private static DefaultRequestWithCtx createRequestWithCtx(String uri, boolean ssl) {
        ChannelContext ctx = mock(ChannelContext.class);
        when(ctx.isSSL()).thenReturn(ssl);
        return new DefaultRequestWithCtx(uri, ctx);
    }

    private static DefaultRequestWithQuery createRequestWithParams(String uri, Map<String, List<String>> params) {
        byte[] uriBytes = uri.getBytes();
        return new DefaultRequestWithQuery(
                HttpMethod.GET, uriBytes, uri, params,
                HttpVersion.HTTP_1_1, new LinkedHashMap<String, Object>(), new byte[0], 0L, null
        );
    }

    /**
     * Test subclass that avoids NullPointerException by not calling ctx.isSSL().
     */
    static class DefaultRequestWithQuery extends HttpDefaultRequest {
        public DefaultRequestWithQuery(HttpMethod method, byte[] uriAsciiBytes, String requestUri,
                                       Map<String, List<String>> parameters, HttpVersion httpVersion,
                                       Map<String, Object> headers, byte[] bodyData,
                                       long contentLength, String contentType) {
            super(method, uriAsciiBytes, requestUri, parameters, httpVersion, headers, bodyData, contentLength, contentType);
        }
    }

    /**
     * Subclass with ChannelContext for testing getRequestURL, delegate, etc.
     */
    static class DefaultRequestWithCtx extends HttpDefaultRequest {
        public DefaultRequestWithCtx(String uri, ChannelContext ctx) {
            this(uri, ctx, new LinkedHashMap<String, Object>());
        }

        public DefaultRequestWithCtx(String uri, ChannelContext ctx, Map<String, Object> headers) {
            super(HttpMethod.GET, uri.getBytes(), uri,
                    new LinkedHashMap<String, List<String>>(),
                    HttpVersion.HTTP_1_1, new LinkedHashMap<String, Object>(headers),
                    new byte[0], 0L, null, ctx);
        }
    }

}
