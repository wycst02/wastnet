package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpMethod;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpMethod}
 *
 * @author wangyc
 */
public class HttpMethodTest {

    @Test
    public void testFromStringCaseSensitive() {
        Assertions.assertNull(HttpMethod.fromString("get"), "Lowercase should not match");
        Assertions.assertNull(HttpMethod.fromString("Get"), "Mixed case should not match");
    }

    @Test
    public void testFromStringNull() {
        Assertions.assertNull(HttpMethod.fromString(null));
    }

    @Test
    public void testFromStringUnknown() {
        Assertions.assertNull(HttpMethod.fromString("INVALID"));
        Assertions.assertNull(HttpMethod.fromString(""));
        Assertions.assertNull(HttpMethod.fromString("GET "));
    }

    @Test
    public void testNameMatchesCanonicalForm() {
        Assertions.assertEquals("GET", HttpMethod.GET.name());
        Assertions.assertEquals("POST", HttpMethod.POST.name());
        Assertions.assertEquals("DELETE", HttpMethod.DELETE.name());
    }

    @Test
    public void testAllConstants() {
        HttpMethod[] methods = HttpMethod.values();
        Assertions.assertTrue(methods.length >= 30);
        for (HttpMethod method : methods) {
            Assertions.assertSame(method, HttpMethod.fromString(method.name()),
                    "fromString must return same instance for " + method.name());
        }
    }
}
