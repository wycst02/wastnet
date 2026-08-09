package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpVersion}
 *
 * @author wangyc
 */
public class HttpVersionTest {

    @Test
    public void testOfValidVersions() {
        Assertions.assertSame(HttpVersion.HTTP_1_0, HttpVersion.of("HTTP/1.0"));
        Assertions.assertSame(HttpVersion.HTTP_1_1, HttpVersion.of("HTTP/1.1"));
        Assertions.assertNull(HttpVersion.of("HTTP/2"));
    }

    @Test
    public void testToString() {
        Assertions.assertEquals("HTTP/1.0", HttpVersion.HTTP_1_0.toString());
        Assertions.assertEquals("HTTP/1.1", HttpVersion.HTTP_1_1.toString());
    }
}
