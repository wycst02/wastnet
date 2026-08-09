package io.github.wycst.wastnet.http.proxy;

import io.github.wycst.wastnet.http.HttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HttpProxyConfig} builder methods and
 * {@link HttpProxyConfig.RewriteRule} rewrite logic.
 */
public class HttpProxyConfigTest {

    // ==================== target() ====================

    @Test
    public void testTargetReturnsConfigWithUrl() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost:8080");
        Assertions.assertNotNull(config);
    }

    // ==================== Builder chain ====================

    @Test
    public void testUpgradeReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.upgrade(true));
        Assertions.assertSame(config, config.upgrade(false));
    }

    @Test
    public void testChangeOriginReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.changeOrigin(true));
    }

    @Test
    public void testReadTimeoutReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.readTimeout(10000));
    }

    @Test
    public void testConnectionTimeoutReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.connectionTimeout(3000));
    }

    @Test
    public void testLoopDetectionReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.loopDetection(true));
        Assertions.assertTrue(config.isLoopDetection());
    }

    @Test
    public void testHttp2ReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.http2(true));
    }

    @Test
    public void testRemoveHeaderReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        Assertions.assertSame(config, config.removeHeader("X-Internal"));
    }

    @Test
    public void testRemoveHeaderMultiple() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.removeHeader("X-A", "X-B", "X-C");
        Assertions.assertTrue(config.removedHeaders.contains("X-A"));
        Assertions.assertTrue(config.removedHeaders.contains("X-B"));
        Assertions.assertTrue(config.removedHeaders.contains("X-C"));
    }

    @Test
    public void testAddHeaderWithCustomResolver() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Custom", new HttpProxyConfig.HeaderValueResolver() {
            public String resolve(HttpRequest request) {
                return "custom-value";
            }
        });
        HttpRequest mockReq = mock(HttpRequest.class);
        HttpProxyConfig.HeaderValueResolver resolver = config.headers.get("x-custom");
        Assertions.assertNotNull(resolver);
        Assertions.assertEquals("custom-value", resolver.resolve(mockReq));
    }

    @Test
    public void testAddHeaderWithVariableDelegatesToResolver() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Real-IP", "$remote_addr");
        HttpRequest mockReq = mock(HttpRequest.class);
        HttpProxyConfig.HeaderValueResolver resolver = config.headers.get("x-real-ip");
        Assertions.assertNotNull(resolver);
        // Should not be the raw template
        String result = resolver.resolve(mockReq);
        Assertions.assertNotNull(result);
    }

    @Test
    public void testAddHeaderStaticValueStored() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.addHeader("X-Static", "fixed");
        Assertions.assertTrue(config.headers.containsKey("x-static"));
    }

    @Test
    public void testRewriteBooleanTrueSetsIdentityRule() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.rewrite(true);
        Assertions.assertNotNull(config.rewriteRule);
        Assertions.assertEquals(HttpProxyConfig.RewriteRule.Type.IDENTITY, config.rewriteRule.type);
        // Identity returns path unchanged
        Assertions.assertEquals("/api/test", config.rewriteRule.apply("/api/test"));
    }

    @Test
    public void testRewriteBooleanFalseClearsRule() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.rewrite(false);
        Assertions.assertNull(config.rewriteRule);
    }

    @Test
    public void testRewriteFunction() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.rewrite(new HttpProxyConfig.RewriteFunction() {
            public String rewrite(String path) {
                return path.replaceFirst("^/api", "");
            }
        });
        Assertions.assertNotNull(config.rewriteRule);
        Assertions.assertEquals(HttpProxyConfig.RewriteRule.Type.FUNCTION, config.rewriteRule.type);
        Assertions.assertEquals("/users", config.rewriteRule.apply("/api/users"));
    }

    @Test
    public void testReplacePrefixStripsAndPrepends() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.replacePrefix("/api", "/v2");
        Assertions.assertNotNull(config.rewriteRule);
        Assertions.assertEquals(HttpProxyConfig.RewriteRule.Type.PREFIX_REPLACE, config.rewriteRule.type);
        Assertions.assertEquals("/v2/users", config.rewriteRule.apply("/api/users"));
    }

    @Test
    public void testReplacePrefixEmptyReplacement() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.replacePrefix("/api", "");
        Assertions.assertEquals("/users", config.rewriteRule.apply("/api/users"));
    }

    @Test
    public void testReplacePrefixNonMatchingReturnsOriginal() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.replacePrefix("/api", "/v2");
        Assertions.assertEquals("/other/path", config.rewriteRule.apply("/other/path"));
    }

    @Test
    public void testReplaceRegex() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.replaceRegex("^/api/(.*)$", "/$1");
        Assertions.assertEquals("/users", config.rewriteRule.apply("/api/users"));
        Assertions.assertEquals("/v1/data", config.rewriteRule.apply("/api/v1/data"));
    }

    @Test
    public void testReplaceRegexNoMatch() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.replaceRegex("^/api/.*$", "/replaced");
        Assertions.assertEquals("/other", config.rewriteRule.apply("/other"));
    }

    // ==================== RewriteRule standalone tests ====================

    @Test
    public void testRewriteRuleIdentity() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.IDENTITY;
        Assertions.assertEquals(HttpProxyConfig.RewriteRule.Type.IDENTITY, rule.type);
        Assertions.assertEquals("/any/path", rule.apply("/any/path"));
    }

    @Test
    public void testRewriteRuleApplyNullReturnsNull() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.IDENTITY;
        Assertions.assertNull(rule.apply(null));
    }

    @Test
    public void testRewriteRulePrefix() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.prefix("/old", "/new");
        Assertions.assertEquals("/new/path", rule.apply("/old/path"));
        Assertions.assertEquals("/new", rule.apply("/old"));
    }

    @Test
    public void testRewriteRulePrefixNullReplacement() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.prefix("/api", null);
        Assertions.assertEquals("/users", rule.apply("/api/users"));
    }

    @Test
    public void testRewriteRulePrefixNoMatch() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.prefix("/api", "/new");
        Assertions.assertEquals("/other", rule.apply("/other"));
    }

    @Test
    public void testRewriteRuleRegex() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.regex("^/api/(.*)$", "/proxy/$1");
        Assertions.assertEquals("/proxy/users", rule.apply("/api/users"));
    }

    @Test
    public void testRewriteRuleFunction() {
        HttpProxyConfig.RewriteRule rule = HttpProxyConfig.RewriteRule.function(
                path -> path.toUpperCase());
        Assertions.assertEquals("/API/USERS", rule.apply("/api/users"));
    }

    @Test
    public void testSetters() {
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.setUpgrade(true);
        config.setChangeOrigin(false);
        config.setReadTimeout(999);
        config.setConnectionTimeout(888);
        config.setRewrite(new HttpProxyConfig.RewriteFunction() {
            public String rewrite(String path) {
                return path;
            }
        });
        config.setRewriteRule(HttpProxyConfig.RewriteRule.IDENTITY);
    }

    @Test
    public void testRewriteWithNullFunction() {
        // rewrite(null) should be no-op
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.rewrite((HttpProxyConfig.RewriteFunction) null);
        Assertions.assertNull(config.rewriteRule);
    }

    @Test
    public void testSetRewriteNull() {
        // setRewrite(null) should clear the rule
        HttpProxyConfig config = HttpProxyConfig.target("http://localhost");
        config.setRewrite(null);
        Assertions.assertNull(config.rewriteRule);
    }

    @Test
    public void testTrustManagersNullClearsPrevious() {
        HttpProxyConfig config = HttpProxyConfig.target("https://localhost");
        config.trustManagers(null);
        Assertions.assertNull(config.trustManagers);
    }

    @Test
    public void testTrustManagersReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("https://localhost");
        Assertions.assertSame(config, config.trustManagers(null));
    }

    @Test
    public void testTrustManagersDefaultIsNull() {
        HttpProxyConfig config = HttpProxyConfig.target("https://localhost");
        Assertions.assertNull(config.trustManagers);
    }

    @Test
    public void testVerifyHostnameDefaultTrue() {
        HttpProxyConfig config = HttpProxyConfig.target("https://localhost");
        Assertions.assertTrue(config.isVerifyHostname());
    }

    @Test
    public void testVerifyHostnameReturnsThis() {
        HttpProxyConfig config = HttpProxyConfig.target("https://localhost");
        Assertions.assertSame(config, config.verifyHostname(false));
        Assertions.assertFalse(config.isVerifyHostname());
    }
}
