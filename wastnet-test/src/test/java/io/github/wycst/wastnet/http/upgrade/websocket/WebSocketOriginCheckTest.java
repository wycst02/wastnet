package io.github.wycst.wastnet.http.upgrade.websocket;

import io.github.wycst.wastnet.http.HttpHeaderNormalized;
import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpVersion;
import io.github.wycst.wastnet.http.MultipartField;
import io.github.wycst.wastnet.socket.tcp.ChannelContext;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket Origin 校验（CSWSH 防护）单元测试。
 * <p>
 * 覆盖 {@link WebSocketResource#isOriginAllowed(HttpRequest)} 全部分支：
 * 未配置跳过、白名单命中/未命中、无 Origin 放行/拒绝、"*" 通配、空白名单。
 */
public class WebSocketOriginCheckTest {

    private static final String ORIGIN_HEADER = HttpHeaderNormalized.getOrigin();

    /** 构造仅携带指定 Origin 的 mock 请求（origin 为 null 表示无 Origin 头）。 */
    private static HttpRequest requestWithOrigin(final String origin) {
        return new HttpRequest() {
            @Override public boolean isHttpRequest() { return true; }
            @Override public boolean isUpgrade() { return true; }
            @Override public boolean isBad() { return false; }
            @Override public long getRequestId() { return 0L; }
            @Override public HttpMethod getMethod() { return HttpMethod.GET; }
            @Override public String getUri() { return "/ws/test"; }
            @Override public String getRequestUri() { return "/ws/test"; }
            @Override public HttpVersion getHttpVersion() { return HttpVersion.HTTP_1_1; }
            @Override public String getScheme() { return "http"; }
            @Override public long getContentLength() { return 0; }
            @Override public String getContentType() { return null; }
            @Override public InputStream bodyStream() { return null; }
            @Override public byte[] getBodyData() { return new byte[0]; }
            @Override public String getHeader(String name) { return null; }
            @Override public String getHeader(String name, boolean caseSensitive) {
                return ORIGIN_HEADER.equals(name) ? origin : null;
            }
            @Override public boolean containsHeader(String name) { return false; }
            @Override public Object getRawHeader(String name) { return null; }
            @Override public List<String> getFullHeader(String name) { return null; }
            @Override public Set<String> getHeaderNames() { return Collections.emptySet(); }
            @Override public String getUriParameter(String name) { return null; }
            @Override public String getQueryString() { return null; }
            @Override public StringBuffer getRequestURL() { return new StringBuffer("/ws/test"); }
            @Override public String getDecodedRequestURL() { return "/ws/test"; }
            @Override public boolean isSSL() { return false; }
            @Override public long getConnectionId() { return 0; }
            @Override public InetSocketAddress getRemoteAddress() { return null; }
            @Override public String getRemoteHost() { return null; }
            @Override public int getRemotePort() { return 0; }
            @Override public InetSocketAddress getServerAddress() { return null; }
            @Override public String getServerHost() { return null; }
            @Override public int getServerPort() { return 0; }
            @Override public boolean isStream() { return false; }
            @Override public boolean isCompleted() { return true; }
            @Override public void complete() {}
            @Override public boolean isMultipart() { return false; }
            @Override public boolean isFormUrlencoded() { return false; }
            @Override public boolean isJson() { return false; }
            @Override public Charset getCharset() { return StandardCharsets.UTF_8; }
            @Override public MultipartField getMultipartField(String name) { return null; }
            @Override public List<MultipartField> getMultipartFields(String name) { return null; }
            @Override public String getMultipartFieldValue(String name) { return null; }
            @Override public List<String> getMultipartFieldValues(String name) { return null; }
            @Override public Set<String> getMultipartFieldNames() { return Collections.emptySet(); }

            @Override
            public List<MultipartField> getMultipartFields() {
                return Collections.emptyList();
            }

            @Override public String getParameter(String name) { return null; }
            @Override public String getBodyParameter(String name) { return null; }
            @Override public List<String> getParameterValues(String name) { return null; }
            @Override public Set<String> getParameterNames() { return Collections.emptySet(); }
            @Override public HttpResponse getResponse() { return null; }
            @Override public void setAttribute(String key, Object value) {}
            @Override public Object getAttribute(String key) { return null; }
            @Override public void delegate(ChannelContext targetCtx) {}

            @Override
            public Object upgrade() throws Exception {
                return null;
            }

            @Override
            public WebSocketConnection upgrade(WebSocketResource ws) throws Exception {
                return null;
            }

            @Override public String getHost() { return null; }
            @Override public void removeAttribute(String key) {}
            @Override public java.util.Enumeration<String> getAttributeNames() { return java.util.Collections.emptyEnumeration(); }
            @Override public java.util.Map<String, String[]> getParameterMap() { return java.util.Collections.emptyMap(); }
        };
    }

    @Test
    public void testNotConfiguredSkipsCheck() {
        // 默认不配置：任何请求放行（向后兼容）
        WebSocketResource resource = new WebSocketResource();
        assertTrue(resource.isOriginAllowed(requestWithOrigin("https://evil.com")));
        assertTrue(resource.isOriginAllowed(requestWithOrigin(null)));
    }

    @Test
    public void testExactMatchAllowed() {
        WebSocketResource resource = new WebSocketResource()
                .allowedOrigins("https://example.com", "https://app.example.com");
        assertTrue(resource.isOriginAllowed(requestWithOrigin("https://example.com")));
        assertTrue(resource.isOriginAllowed(requestWithOrigin("https://app.example.com")));
    }

    @Test
    public void testExactMatchRejected() {
        // 白名单未命中即拒绝（CSWSH 场景：浏览器自动带 Origin）
        WebSocketResource resource = new WebSocketResource()
                .allowedOrigins("https://example.com");
        assertFalse(resource.isOriginAllowed(requestWithOrigin("https://evil.com")));
        // 大小写/端口差异均视为不同源
        assertFalse(resource.isOriginAllowed(requestWithOrigin("https://example.com:8443")));
        assertFalse(resource.isOriginAllowed(requestWithOrigin("http://example.com")));
    }

    @Test
    public void testMissingOriginAllowedByDefault() {
        // 非浏览器客户端（curl/Java）无 Origin 头，默认放行
        WebSocketResource resource = new WebSocketResource()
                .allowedOrigins("https://example.com");
        assertTrue(resource.isOriginAllowed(requestWithOrigin(null)));
    }

    @Test
    public void testMissingOriginRejectedInStrictMode() {
        WebSocketResource resource = new WebSocketResource()
                .allowedOrigins("https://example.com")
                .allowMissingOrigin(false);
        assertFalse(resource.isOriginAllowed(requestWithOrigin(null)));
        // 严格模式不影响命中白名单的请求
        assertTrue(resource.isOriginAllowed(requestWithOrigin("https://example.com")));
    }

    @Test
    public void testWildcardAllowsAnyOrigin() {
        WebSocketResource resource = new WebSocketResource().allowedOrigins("*");
        assertTrue(resource.isOriginAllowed(requestWithOrigin("https://anything.com")));
    }

    @Test
    public void testEmptyAllowlistRejectsAnyOrigin() {
        // 空白名单：任何携带 Origin 的请求都被拒
        WebSocketResource resource = new WebSocketResource().allowedOrigins();
        assertFalse(resource.isOriginAllowed(requestWithOrigin("https://example.com")));
        assertTrue(resource.isOriginAllowed(requestWithOrigin(null)));
    }

    @Test
    public void testChainingReturnsThis() {
        WebSocketResource resource = new WebSocketResource();
        assertSame(resource, resource.allowedOrigins("https://example.com"));
        assertSame(resource, resource.allowMissingOrigin(false));
    }
}
