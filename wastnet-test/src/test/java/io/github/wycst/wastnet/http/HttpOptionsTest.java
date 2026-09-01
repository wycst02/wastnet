package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.tcp.NioConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers per-instance override (normalizer) semantics of {@link HttpOptions} constants,
 * validating the boundary / clamp / fallback logic introduced by the config isolation change.
 */
class HttpOptionsTest {

    private final NioConfig config = new NioConfig();

    @Test
    void headerAndUriSizesLowerBoundClamped() {
        // MAX_SINGLE_HEADER_SIZE / MAX_HTTP_HEADER_SIZE / MAX_URI_LENGTH: max(1, v)
        config.option(HttpOptions.MAX_SINGLE_HEADER_SIZE, 0);
        assertEquals(1, (int) config.option(HttpOptions.MAX_SINGLE_HEADER_SIZE));

        config.option(HttpOptions.MAX_HTTP_HEADER_SIZE, -100);
        assertEquals(1, (int) config.option(HttpOptions.MAX_HTTP_HEADER_SIZE));

        config.option(HttpOptions.MAX_URI_LENGTH, 0);
        assertEquals(1, (int) config.option(HttpOptions.MAX_URI_LENGTH));

        // valid values kept
        config.option(HttpOptions.MAX_SINGLE_HEADER_SIZE, 8192);
        assertEquals(8192, (int) config.option(HttpOptions.MAX_SINGLE_HEADER_SIZE));
    }

    @Test
    void bodyMemorySizesLowerBoundClamped() {
        config.option(HttpOptions.BODY_MEMORY_THRESHOLD, 0);
        assertEquals(1, (int) config.option(HttpOptions.BODY_MEMORY_THRESHOLD));

        config.option(HttpOptions.MAX_BODY_IN_MEMORY, -1);
        assertEquals(1, (int) config.option(HttpOptions.MAX_BODY_IN_MEMORY));
    }

    @Test
    void bodyMaxSizeNonPositiveMeansUnlimited() {
        // <= 0 -> Long.MAX_VALUE (no limit)
        config.option(HttpOptions.BODY_MAX_SIZE, 0L);
        assertEquals(Long.MAX_VALUE, config.option(HttpOptions.BODY_MAX_SIZE));

        config.option(HttpOptions.BODY_MAX_SIZE, -1L);
        assertEquals(Long.MAX_VALUE, config.option(HttpOptions.BODY_MAX_SIZE));

        // positive -> raised to at least BODY_MEMORY_THRESHOLD
        config.option(HttpOptions.BODY_MAX_SIZE, 1L);
        assertEquals((long) HttpConf.BODY_MEMORY_THRESHOLD, config.option(HttpOptions.BODY_MAX_SIZE));

        config.option(HttpOptions.BODY_MAX_SIZE, 1024L * 1024);
        assertEquals(1024L * 1024, config.option(HttpOptions.BODY_MAX_SIZE));
    }

    @Test
    void requestAndSseTimeoutNonPositiveMeansUnlimited() {
        config.option(HttpOptions.REQUEST_TIMEOUT_MS, 0L);
        assertEquals(Long.MAX_VALUE, config.option(HttpOptions.REQUEST_TIMEOUT_MS));

        config.option(HttpOptions.REQUEST_TIMEOUT_MS, -1L);
        assertEquals(Long.MAX_VALUE, config.option(HttpOptions.REQUEST_TIMEOUT_MS));

        config.option(HttpOptions.SSE_TIMEOUT_MS, 0L);
        assertEquals(Long.MAX_VALUE, config.option(HttpOptions.SSE_TIMEOUT_MS));

        config.option(HttpOptions.SSE_TIMEOUT_MS, 60000L);
        assertEquals(60000L, config.option(HttpOptions.SSE_TIMEOUT_MS));
    }

    @Test
    void gzipMinSizeLowerBoundClamped() {
        config.option(HttpOptions.GZIP_MIN_SIZE, -5);
        assertEquals(0, (int) config.option(HttpOptions.GZIP_MIN_SIZE));

        config.option(HttpOptions.GZIP_MIN_SIZE, 256);
        assertEquals(256, (int) config.option(HttpOptions.GZIP_MIN_SIZE));
    }

    @Test
    void http2SendWindowClamped() {
        // between 0xFFFF and 0xFFFFFF
        config.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE, 0);
        assertEquals(0xFFFF, (int) config.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE));

        config.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE, 0xFFFFFF + 1);
        assertEquals(0xFFFFFF, (int) config.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE));

        config.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE, 65536);
        assertEquals(65536, (int) config.option(HttpOptions.HTTP2_INITIAL_SEND_WINDOW_SIZE));
    }

    @Test
    void http2ConcurrentStreamsLowerBound() {
        config.option(HttpOptions.HTTP2_MAX_CONCURRENT_STREAMS, 10);
        assertEquals(100, (int) config.option(HttpOptions.HTTP2_MAX_CONCURRENT_STREAMS));

        config.option(HttpOptions.HTTP2_MAX_CONCURRENT_STREAMS, 200);
        assertEquals(200, (int) config.option(HttpOptions.HTTP2_MAX_CONCURRENT_STREAMS));
    }

    @Test
    void http2ClientRstBounds() {
        config.option(HttpOptions.HTTP2_CLIENT_RST_WINDOW_SECONDS, 0);
        assertEquals(1, (int) config.option(HttpOptions.HTTP2_CLIENT_RST_WINDOW_SECONDS));

        config.option(HttpOptions.HTTP2_CLIENT_RST_MAX_COUNT, 0);
        assertEquals(1, (int) config.option(HttpOptions.HTTP2_CLIENT_RST_MAX_COUNT));
    }

    @Test
    void http2FlowControlWaitAndBodyReadLowerBound() {
        config.option(HttpOptions.HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS, 1);
        assertEquals(1000, (int) config.option(HttpOptions.HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS));

        config.option(HttpOptions.HTTP2_BODY_READ_TIMEOUT_MS, 1);
        assertEquals(1000, (int) config.option(HttpOptions.HTTP2_BODY_READ_TIMEOUT_MS));
    }

    @Test
    void gzipUnconstrainedBoolean() {
        config.option(HttpOptions.GZIP, true);
        assertEquals(Boolean.TRUE, config.option(HttpOptions.GZIP));
    }
}
