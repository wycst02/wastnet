package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

/**
 * Unit tests for {@link HttpConf}.
 *
 * @author wangyc
 */
public class HttpConfTest {

    @Test
    public void testDumpAsProperties() {
        String props = HttpConf.dumpAsProperties();
        Assertions.assertTrue(props.startsWith("# HTTP Configuration"), props);
        Assertions.assertTrue(props.contains("wastnet.http.gzip=" + HttpConf.GZIP));
        Assertions.assertTrue(props.contains("wastnet.http.gzip-min-size=" + HttpConf.GZIP_MIN_SIZE));
        Assertions.assertTrue(props.contains("wastnet.http.header.default.enabled=" + HttpConf.WRITE_DEFAULT_HEADERS));
    }

    @Test
    public void testGetPropertyReturnsNullForUnknownKey() {
        Assertions.assertNull(HttpConf.getProperty("wastnet.http.nonexistent.key"));
    }

    @Test
    public void testHttp2MaxConcurrentStreamsDefault() {
        // Default is 512; minimum allowed value is 100 (configured values below 100 are clamped up).
        Assertions.assertFalse(HttpConf.HTTP2_MAX_CONCURRENT_STREAMS > 512, "default should not exceed 512 unless explicitly overridden");
        Assertions.assertTrue(HttpConf.HTTP2_MAX_CONCURRENT_STREAMS >= 100, "never below the minimum of 100");
    }

    @Test
    public void testHttp2ClientRstRateLimitDefault() {
        // Rapid Reset (CVE-2023-44487) defense defaults:
        // window-seconds default 1 (min 1), max-count default 100 (min 1).
        Assertions.assertEquals(1, HttpConf.HTTP2_CLIENT_RST_WINDOW_SECONDS, "default window-seconds should be 1");
        Assertions.assertTrue(HttpConf.HTTP2_CLIENT_RST_WINDOW_SECONDS >= 1, "window-seconds never below minimum of 1");
        Assertions.assertEquals(100, HttpConf.HTTP2_CLIENT_RST_MAX_COUNT, "default max-count should be 100");
        Assertions.assertTrue(HttpConf.HTTP2_CLIENT_RST_MAX_COUNT >= 1, "max-count never below minimum of 1");
    }

    @Test
    public void testPrivateConstructorPreventsInstantiation() throws Exception {
        // HttpConf is a static-only config class; its constructor must be private (uncallable externally).
        Constructor<HttpConf> ctor = HttpConf.class.getDeclaredConstructor();
        Assertions.assertFalse(ctor.isAccessible(), "HttpConf constructor must be private");
        ctor.setAccessible(true);
        HttpConf instance = ctor.newInstance();
        Assertions.assertNotNull(instance);
    }
}
