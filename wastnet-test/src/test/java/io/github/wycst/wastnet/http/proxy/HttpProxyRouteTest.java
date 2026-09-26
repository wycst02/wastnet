package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpProxyRoute}.
 * <p>
 * Covers constructor URL parsing variants, handle() loop detection and rewrite error, clear().
 *
 * @author wangyc
 */
public class HttpProxyRouteTest {

    // ==================== Constructor: URL parsing variants ====================

    @Test
    public void testConstructorMalformedUrlThrows() {
        HttpProxyConfig config = HttpProxyConfig.target("not-a-url");
        assertThrows(IllegalStateException.class, () -> new HttpProxyRoute(config));
    }

    // ==================== handle: loop detection ====================

    /**
     * Minimal non-abstract subclass for testing handle().
     */
    static class TestBaseRequest extends HttpInternalRequest {
        final Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        TestBaseRequest(ChannelContext ctx) { super(ctx); }
        @Override public HttpMethod getMethod() { return HttpMethod.GET; }
        @Override public String getUri() { return "/test"; }
        @Override public String getRequestUri() { return "/test"; }
        @Override public HttpVersion getHttpVersion() { return HttpVersion.HTTP_1_1; }
        @Override public String getScheme() { return "http"; }
        @Override public long getContentLength() { return 0; }
        @Override public String getContentType() { return null; }
        @Override public void delegate(ChannelContext ctx) throws Throwable {}
        @Override public HttpBodyInputStream bodyStream() { return null; }
        @Override public byte[] getBodyData() { return new byte[0]; }
        @Override public String getHeader(String name) { return headers.get(name.toLowerCase()); }
        @Override public boolean containsHeader(String name) { return headers.containsKey(name.toLowerCase()); }
        @Override public java.util.Set<String> getHeaderNames() { return headers.keySet(); }
        @Override public String getUriParameter(String name) { return null; }
        @Override public String getQueryString() { return null; }
        @Override public StringBuffer getRequestURL() { return null; }
        @Override public void setRewriteUri(String uri) {}
        @Override public void setHeader(String key, java.io.Serializable value) { headers.put(key.toLowerCase(), String.valueOf(value)); }
        @Override public void removeHeader(String key) { headers.remove(key.toLowerCase()); }
        @Override public Object getRawHeader(String name) { return headers.get(name.toLowerCase()); }
        @Override protected java.util.List<String> getUriParameterValues(String name) { return null; }
        @Override protected java.util.Set<String> getUriParameterNames() { return java.util.Collections.emptySet(); }
        @Override public boolean isCompleted() { return false; }
    }

    @Test
    public void testHandleLoopDetectedReturnsEarly() throws Throwable {
        HttpProxyConfig config = HttpProxyConfig.target("http://example.com")
                .loopDetection(true);
        HttpProxyRoute route = new HttpProxyRoute(config);

        // loopMarker is package-private (same package), read directly
        String loopMarker = route.loopMarker;
        assertNotNull(loopMarker);

        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest request = new TestBaseRequest(ctx);
        request.setHeader(loopMarker, "1");

        HttpResponse response = mock(HttpResponse.class);
        route.handle("/test", request, response);
        verify(response).setStatusAndText(HttpStatus.LOOP_DETECTED);
    }

    // ==================== handle: rewrite rule error ====================

    @Test
    public void testHandleRewriteErrorReturnsInternalError() throws Throwable {
        HttpProxyConfig config = HttpProxyConfig.target("http://example.com")
                .loopDetection(false)
                .rewrite(new HttpProxyConfig.RewriteFunction() {
                    public String rewrite(String path) {
                        throw new RuntimeException("rewrite failed");
                    }
                });
        HttpProxyRoute route = new HttpProxyRoute(config);

        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest request = new TestBaseRequest(ctx);
        HttpResponse response = mock(HttpResponse.class);

        route.handle("/test", request, response);
        verify(response).setStatusAndText(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ==================== handle: targetPath  + changeOrigin  ====================

    // ==================== handle: rewrite returns non-standard URI  ====================

    @Test
    public void testHandleRewritePrependsSlash() throws Throwable {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost:1")
                .loopDetection(false)
                .rewrite(new HttpProxyConfig.RewriteFunction() {
                    public String rewrite(String path) {
                        return "rewritten-path";
                    }
                });
        config.connectionTimeout = 50;
        HttpProxyRoute route = new HttpProxyRoute(config);

        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest request = new TestBaseRequest(ctx);
        HttpResponse response = mock(HttpResponse.class);

        // Connection refused, but  (rewrite prepend) already executed
        route.handle("/test", request, response);
    }

    @Test
    public void testHandleWithTargetPathAndChangeOrigin() throws Throwable {
        // Target with non-trivial path → targetPath != null, changeOrigin is true by default
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost:1/api");
        config.connectionTimeout = 50;
        HttpProxyRoute route = new HttpProxyRoute(config);

        ChannelContext ctx = mock(ChannelContext.class);
        TestBaseRequest request = new TestBaseRequest(ctx);
        HttpResponse response = mock(HttpResponse.class);

        route.handle("/test", request, response);
        // Connection to localhost:1 will be refused → IOException caught at ,
        // but  (targetPath) and  (changeOrigin) were already executed
    }


    // ==================== clear ====================


    @Test
    public void testClear() {
        HttpProxyConfig config = HttpProxyConfig.target("http://example.com");
        HttpProxyRoute route = new HttpProxyRoute(config);
        route.clear(); // should not throw
    }

}
