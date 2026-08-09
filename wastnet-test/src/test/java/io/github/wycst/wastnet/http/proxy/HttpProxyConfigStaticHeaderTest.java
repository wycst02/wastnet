package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpHeaderUtils;
import io.github.wycst.wastnet.http.HttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HttpProxyConfig.StaticHeaderValueResolver}.
 * <p>
 * Verifies that static header values are stored and returned correctly,
 * regardless of the request object passed to {@code resolve()}.
 *
 * @author wangyc
 */
public class HttpProxyConfigStaticHeaderTest {

    @Test
    public void testStaticHeaderValueResolverReturnsFixedValue() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Custom", "fixed-value");

        // StaticHeaderValueResolver is private, accessed via headers map
        HttpProxyConfig.HeaderValueResolver resolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Custom"));
        Assertions.assertNotNull(resolver);

        // resolve() ignores the request param and returns the stored value
        HttpRequest request = mock(HttpRequest.class);
        Assertions.assertEquals("fixed-value", resolver.resolve(request));
    }

    @Test
    public void testStaticHeaderValueMultipleFields() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Auth-Token", "token-abc");
        config.addHeader("X-App-Id", "app-789");

        HttpProxyConfig.HeaderValueResolver tokenResolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Auth-Token"));
        HttpProxyConfig.HeaderValueResolver appResolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-App-Id"));

        Assertions.assertNotNull(tokenResolver);
        Assertions.assertNotNull(appResolver);

        HttpRequest request = mock(HttpRequest.class);
        Assertions.assertEquals("token-abc", tokenResolver.resolve(request));
        Assertions.assertEquals("app-789", appResolver.resolve(request));
    }

    @Test
    public void testStaticHeaderValueResolveReturnsSameForAnyRequest() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Node", "node-01");

        HttpProxyConfig.HeaderValueResolver resolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Node"));

        // Must return the same value regardless of request
        HttpRequest request1 = mock(HttpRequest.class);
        HttpRequest request2 = mock(HttpRequest.class);
        Assertions.assertEquals("node-01", resolver.resolve(request1));
        Assertions.assertEquals("node-01", resolver.resolve(request2));
    }

    @Test
    public void testStaticHeaderValueWithEmptyString() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Empty", "");

        HttpProxyConfig.HeaderValueResolver resolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Empty"));
        Assertions.assertNotNull(resolver);

        HttpRequest request = mock(HttpRequest.class);
        Assertions.assertEquals("", resolver.resolve(request));
    }

    @Test
    public void testStaticHeaderWithSpecialCharacters() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Special", "value with spaces & special chars!#$%");

        HttpProxyConfig.HeaderValueResolver resolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Special"));
        Assertions.assertNotNull(resolver);

        HttpRequest request = mock(HttpRequest.class);
        Assertions.assertEquals("value with spaces & special chars!#$%", resolver.resolve(request));
    }

    @Test
    public void testStaticHeaderNameNormalization() {
        // addHeader normalizes the header key, StaticHeaderValueResolver is used for non-variable values
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("x-custom-header", "normalized");

        // Should be stored under normalized key
        HttpProxyConfig.HeaderValueResolver resolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Custom-Header"));
        Assertions.assertNotNull(resolver);

        HttpRequest request = mock(HttpRequest.class);
        Assertions.assertEquals("normalized", resolver.resolve(request));
    }

    @Test
    public void testStaticHeaderDistinctFromDynamicHeader() {
        // Verify static and dynamic headers produce different resolver types
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Static", "static-val");
        config.addHeader("X-Dynamic", "$remote_addr");

        HttpProxyConfig.HeaderValueResolver staticResolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Static"));
        HttpProxyConfig.HeaderValueResolver dynamicResolver =
                config.headers.get(HttpHeaderUtils.normalizeHeaderKey("X-Dynamic"));

        Assertions.assertNotNull(staticResolver);
        Assertions.assertNotNull(dynamicResolver);

        // Static returns the literal value
        HttpRequest request = mock(HttpRequest.class);
        Assertions.assertEquals("static-val", staticResolver.resolve(request));

        // Dynamic resolves to something different (not the raw template)
        String dynamicResult = dynamicResolver.resolve(request);
        Assertions.assertNotNull(dynamicResult);
        Assertions.assertNotEquals("$remote_addr", dynamicResult);
    }
}
