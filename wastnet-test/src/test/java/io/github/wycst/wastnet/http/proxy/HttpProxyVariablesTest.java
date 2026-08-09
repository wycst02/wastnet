package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HttpProxyVariables}.
 * <p>
 * Tests {@link HttpProxyVariables#resolve(String, HttpRequest)} and
 * all built-in {@link HttpProxyConfig.HeaderValueResolver} implementations.
 */
public class HttpProxyVariablesTest {

    private HttpRequest mockRequest(String host, String scheme, String requestUri,
                                     String uri, String queryString,
                                     InetSocketAddress remoteAddr, InetSocketAddress serverAddr) {
        HttpRequest req = mock(HttpRequest.class);
        when(req.getHeader("host", true)).thenReturn(host);
        when(req.getScheme()).thenReturn(scheme);
        when(req.getRequestUri()).thenReturn(requestUri);
        when(req.getUri()).thenReturn(uri);
        when(req.getQueryString()).thenReturn(queryString);
        when(req.getRemoteAddress()).thenReturn(remoteAddr);
        when(req.getServerAddress()).thenReturn(serverAddr);
        return req;
    }

    @Test
    void testResolveNullReturnsNull() {
        Assertions.assertNull(HttpProxyVariables.resolve(null, mock(HttpRequest.class)));
    }

    @Test
    void testResolvePlainReturnsAsIs() {
        HttpRequest req = mock(HttpRequest.class);
        Assertions.assertEquals("plain-value", HttpProxyVariables.resolve("plain-value", req));
    }

    @Test
    void testResolveUnknownVariableKeepsAsIs() {
        HttpRequest req = mock(HttpRequest.class);
        Assertions.assertEquals("$unknown", HttpProxyVariables.resolve("$unknown", req));
    }

    @Test
    void testResolveRemoteAddr() {
        HttpRequest req = mockRequest(null, null, null, null, null,
                new InetSocketAddress("192.168.1.1", 12345), null);
        Assertions.assertEquals("192.168.1.1", HttpProxyVariables.resolve("$remote_addr", req));
    }

    @Test
    void testResolveRemoteAddrNull() {
        HttpRequest req = mockRequest(null, null, null, null, null, null, null);
        Assertions.assertEquals("", HttpProxyVariables.resolve("$remote_addr", req));
    }

    @Test
    void testResolveRemotePort() {
        HttpRequest req = mockRequest(null, null, null, null, null,
                new InetSocketAddress("192.168.1.1", 12345), null);
        Assertions.assertEquals("12345", HttpProxyVariables.resolve("$remote_port", req));
    }

    @Test
    void testResolveRemotePortNull() {
        HttpRequest req = mockRequest(null, null, null, null, null, null, null);
        Assertions.assertEquals("", HttpProxyVariables.resolve("$remote_port", req));
    }

    @Test
    void testResolveHost() {
        HttpRequest req = mockRequest("example.com", null, null, null, null, null, null);
        Assertions.assertEquals("example.com", HttpProxyVariables.resolve("$host", req));
    }

    @Test
    void testResolveHostNull() {
        HttpRequest req = mockRequest(null, null, null, null, null, null, null);
        Assertions.assertEquals("", HttpProxyVariables.resolve("$host", req));
    }

    @Test
    void testResolveScheme() {
        HttpRequest req = mockRequest(null, "https", null, null, null, null, null);
        Assertions.assertEquals("https", HttpProxyVariables.resolve("$scheme", req));
    }

    @Test
    void testResolveRequestUri() {
        HttpRequest req = mockRequest(null, null, "/api/users", null, null, null, null);
        Assertions.assertEquals("/api/users", HttpProxyVariables.resolve("$request_uri", req));
    }

    @Test
    void testResolveUri() {
        HttpRequest req = mockRequest(null, null, null, "/api/users?page=1", null, null, null);
        Assertions.assertEquals("/api/users?page=1", HttpProxyVariables.resolve("$uri", req));
    }

    @Test
    void testResolveQueryString() {
        HttpRequest req = mockRequest(null, null, null, null, "page=1&q=hello", null, null);
        Assertions.assertEquals("page=1&q=hello", HttpProxyVariables.resolve("$query_string", req));
    }

    @Test
    void testResolveQueryStringNull() {
        HttpRequest req = mockRequest(null, null, null, null, null, null, null);
        Assertions.assertEquals("", HttpProxyVariables.resolve("$query_string", req));
    }

    @Test
    void testResolveServerAddr() {
        HttpRequest req = mockRequest(null, null, null, null, null, null,
                new InetSocketAddress("10.0.0.1", 8080));
        Assertions.assertEquals("10.0.0.1", HttpProxyVariables.resolve("$server_addr", req));
    }

    @Test
    void testResolveServerAddrNull() {
        HttpRequest req = mockRequest(null, null, null, null, null, null, null);
        Assertions.assertEquals("", HttpProxyVariables.resolve("$server_addr", req));
    }

    @Test
    void testResolveServerPort() {
        HttpRequest req = mockRequest(null, null, null, null, null, null,
                new InetSocketAddress("10.0.0.1", 8080));
        Assertions.assertEquals("8080", HttpProxyVariables.resolve("$server_port", req));
    }

    @Test
    void testResolveServerPortNull() {
        HttpRequest req = mockRequest(null, null, null, null, null, null, null);
        Assertions.assertEquals("", HttpProxyVariables.resolve("$server_port", req));
    }

    @Test
    void testResolveMixedVariablesAndLiterals() {
        HttpRequest req = mockRequest("example.com", "https", "/path", "/path?q=1", "q=1",
                new InetSocketAddress("10.0.0.1", 12345),
                new InetSocketAddress("192.168.1.1", 8080));
        // $scheme://$host/path
        // ':' and '/' are not word chars, so $scheme and $host are extracted correctly
        String resolved = HttpProxyVariables.resolve("$scheme://$host/api", req);
        Assertions.assertEquals("https://example.com/api", resolved);
    }

    @Test
    void testResolveMultipleTokensWithUnknownVariable() {
        HttpRequest req = mockRequest("x.com", "http", null, null, null, null, null);
        // Unknown variable $foo is kept as-is
        // Note: '_' is a word char, so $host is followed by '.' to stop variable extraction
        String resolved = HttpProxyVariables.resolve("prefix.$host.mid.$foo.suffix", req);
        Assertions.assertEquals("prefix.x.com.mid.$foo.suffix", resolved);
    }

    @Test
    void testContainsVariable() {
        Assertions.assertTrue(HttpProxyVariables.containsVariable("$remote_addr"));
        Assertions.assertTrue(HttpProxyVariables.containsVariable("X-$host"));
        Assertions.assertFalse(HttpProxyVariables.containsVariable("plain"));
        Assertions.assertFalse(HttpProxyVariables.containsVariable(null));
    }

    // ==================== Char range coverage (L154) ====================

    @Test
    void testResolveVariableWithDigitsInName() {
        // Variable extraction with digits: $host123 → word chars include digits
        // ":" stops extraction after "https"
        HttpRequest req = mockRequest("example.com", "https", null, null, null, null, null);
        String resolved = HttpProxyVariables.resolve("$scheme://$host123/path", req);
        // $host123 is not in BUILTINS → kept as raw, $scheme resolves
        Assertions.assertEquals("https://$host123/path", resolved);
    }

    @Test
    void testResolveVariableWithUppercaseName() {
        // Variable extraction with uppercase: $HOST is matched as whole name
        HttpRequest req = mockRequest("example.com", "https", null, null, null, null, null);
        String resolved = HttpProxyVariables.resolve("prefix.$Host.suffix", req);
        // $Host not in BUILTINS → kept as raw
        Assertions.assertEquals("prefix.$Host.suffix", resolved);
    }

    // ==================== Null resolver branch (L165) ====================

    @Test
    void testResolveProviderReturnsNull() throws Exception {
        // Insert a resolver that returns null into BUILTINS via reflection
        java.lang.reflect.Field builtinsField = HttpProxyVariables.class.getDeclaredField("BUILTINS");
        builtinsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, HttpProxyConfig.HeaderValueResolver> map =
                (java.util.Map<String, HttpProxyConfig.HeaderValueResolver>) builtinsField.get(null);
        HttpProxyConfig.HeaderValueResolver nullResolver = new HttpProxyConfig.HeaderValueResolver() {
            public String resolve(HttpRequest request) { return null; }
        };
        map.put("$nullvar", nullResolver);
        try {
            HttpRequest req = mock(HttpRequest.class);
            // Slow path with $nullvar returning null → appends "" (L165 else branch)
            String resolved = HttpProxyVariables.resolve("x$nullvar", req);
            Assertions.assertEquals("x", resolved);
        } finally {
            map.remove("$nullvar");
        }
    }
}
