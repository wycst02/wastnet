package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.socket.conf.Option;
import io.github.wycst.wastnet.socket.conf.SocketOptions;

/**
 * Registry of strongly-typed HTTP configuration options.
 *
 * <p>Each constant is an {@link Option} whose {@code defaultValue} is taken from
 * {@link HttpConf}, keeping this registry consistent with the global static
 * configuration while enabling per-server overrides via
 * {@code NioConfig#option(Option)} / {@code ChannelContext#option(Option)}
 * without touching the static holder.</p>
 *
 * @since 1.0.2
 */
public class HttpOptions extends SocketOptions {

    private HttpOptions() {
    }

    // ---- HTTP/1.1 ----
    /** Max size (bytes) of a single header line (name+value). Default: 8192; min 1. See {@link HttpConf#MAX_SINGLE_HEADER_SIZE}. */
    public static final Option<Integer> MAX_SINGLE_HEADER_SIZE =
            of(HttpConf.MAX_SINGLE_HEADER_SIZE, Integer.class, v -> (int) Math.min(Integer.MAX_VALUE, Math.max(1L, v)));
    /** Max total size (bytes) of all request headers. Default: 16384; min 1. See {@link HttpConf#MAX_HTTP_HEADER_SIZE}. */
    public static final Option<Integer> MAX_HTTP_HEADER_SIZE =
            of(HttpConf.MAX_HTTP_HEADER_SIZE, Integer.class, v -> (int) Math.min(Integer.MAX_VALUE, Math.max(1L, v)));
    /** Max length (bytes) of the request URI / target. Default: 16384; min 1. See {@link HttpConf#MAX_URI_LENGTH}. */
    public static final Option<Integer> MAX_URI_LENGTH =
            of(HttpConf.MAX_URI_LENGTH, Integer.class, v -> (int) Math.min(Integer.MAX_VALUE, Math.max(1L, v)));
    /** Response buffer threshold (bytes): data above it is flushed immediately. Default: 524288 (512KB). See {@link HttpConf#BODY_MEMORY_THRESHOLD}. */
    public static final Option<Integer> BODY_MEMORY_THRESHOLD =
            of(HttpConf.BODY_MEMORY_THRESHOLD, Integer.class, v -> (int) Math.min(Integer.MAX_VALUE, Math.max(1L, v)));
    /** Max request body kept in memory before streaming. Default: 2097152 (2MB); min 1. See {@link HttpConf#MAX_BODY_IN_MEMORY}. */
    public static final Option<Integer> MAX_BODY_IN_MEMORY =
            of(HttpConf.MAX_BODY_IN_MEMORY, Integer.class, v -> (int) Math.min(Integer.MAX_VALUE, Math.max(1L, v)));
    /** Max request body size (bytes); <=0 means unlimited, otherwise clamped to at least BODY_MEMORY_THRESHOLD. Default: 512MB. See {@link HttpConf#BODY_MAX_SIZE}. */
    public static final Option<Long> BODY_MAX_SIZE =
            of(HttpConf.BODY_MAX_SIZE, Long.class, v -> v <= 0 ? Long.MAX_VALUE : Math.max(v, HttpConf.BODY_MEMORY_THRESHOLD));
    /** Create temp files when body exceeds the memory threshold. Default: true. See {@link HttpConf#ENABLE_TEMP_FILE}. */
    public static final Option<Boolean> ENABLE_TEMP_FILE =
            of(HttpConf.ENABLE_TEMP_FILE, Boolean.class);
    /** Temp file directory. Default: {java.io.tmpdir}/wastnet-http. See {@link HttpConf#TEMP_FILE_DIR}. */
    public static final Option<String> TEMP_FILE_DIR =
            of(HttpConf.TEMP_FILE_DIR, String.class);
    /** Temp file name prefix. Default: "wastnet_tmp_". See {@link HttpConf#TEMP_FILE_PREFIX}. */
    public static final Option<String> TEMP_FILE_PREFIX =
            of(HttpConf.TEMP_FILE_PREFIX, String.class);
    /** Enable GZIP response compression. Default: false. See {@link HttpConf#GZIP}. */
    public static final Option<Boolean> GZIP =
            of(HttpConf.GZIP, Boolean.class);
    /** Min body size (bytes) to trigger GZIP. Default: 2048; min 0. See {@link HttpConf#GZIP_MIN_SIZE}. */
    public static final Option<Integer> GZIP_MIN_SIZE =
            of(HttpConf.GZIP_MIN_SIZE, Integer.class, v -> (int) Math.min(Integer.MAX_VALUE, Math.max(0L, v)));
    /** Write default response headers (Date/Server/Connection). Default: true. See {@link HttpConf#WRITE_DEFAULT_HEADERS}. */
    public static final Option<Boolean> WRITE_DEFAULT_HEADERS =
            of(HttpConf.WRITE_DEFAULT_HEADERS, Boolean.class);
    /** Expose the Server header (off by default for security). Default: false. See {@link HttpConf#EXPOSE_SERVER_HEADER}. */
    public static final Option<Boolean> EXPOSE_SERVER_HEADER =
            of(HttpConf.EXPOSE_SERVER_HEADER, Boolean.class);
    /** Enable HTTP/1.1 pipelining. Default: false (extra pipelined requests discarded). See {@link HttpConf#PIPELINE_ENABLED}. */
    public static final Option<Boolean> PIPELINE_ENABLED =
            of(HttpConf.PIPELINE_ENABLED, Boolean.class);
    /** Max time (ms) from first byte to full request; <=0 disables (Long.MAX_VALUE). Default: 60000 (60s). See {@link HttpConf#REQUEST_TIMEOUT_MS}. */
    public static final Option<Long> REQUEST_TIMEOUT_MS =
            of(HttpConf.REQUEST_TIMEOUT_MS, Long.class, v -> v <= 0 ? Long.MAX_VALUE : v);
    /** Preserve header insertion order (LinkedHashMap). Default: false (HashMap). See {@link HttpConf#PRESERVE_HEADER_ORDER}. */
    public static final Option<Boolean> PRESERVE_HEADER_ORDER =
            of(HttpConf.PRESERVE_HEADER_ORDER, Boolean.class);
    /** SSE connection auto-close timeout (ms); <=0 disables. Default: 1800000 (30min). See {@link HttpConf#SSE_TIMEOUT_MS}. */
    public static final Option<Long> SSE_TIMEOUT_MS =
            of(HttpConf.SSE_TIMEOUT_MS, Long.class, v -> v <= 0 ? Long.MAX_VALUE : v);

    // ---- HTTP/2 ----
    /** HTTP/2 initial send/receive window size (bytes), clamped [65535, 16777215]. Default: 65535. See {@link HttpConf#HTTP2_INITIAL_SEND_WINDOW_SIZE}. */
    public static final Option<Integer> HTTP2_INITIAL_SEND_WINDOW_SIZE =
            of(HttpConf.HTTP2_INITIAL_SEND_WINDOW_SIZE, Integer.class, v -> (int) Math.min(Math.max(v, 0xFFFFL), 0xFFFFFFL));
    /** HTTP/2 max concurrent streams (anti Rapid Reset). Default: 512; min 100. See {@link HttpConf#HTTP2_MAX_CONCURRENT_STREAMS}. */
    public static final Option<Integer> HTTP2_MAX_CONCURRENT_STREAMS =
            of(HttpConf.HTTP2_MAX_CONCURRENT_STREAMS, Integer.class, v -> Math.max(100, v));
    /** HTTP/2 client RST_STREAM rate-limit window (seconds). Default: 1; min 1. See {@link HttpConf#HTTP2_CLIENT_RST_WINDOW_SECONDS}. */
    public static final Option<Integer> HTTP2_CLIENT_RST_WINDOW_SECONDS =
            of(HttpConf.HTTP2_CLIENT_RST_WINDOW_SECONDS, Integer.class, v -> Math.max(1, v));
    /** HTTP/2 max client RST_STREAM frames per window. Default: 100; min 1. See {@link HttpConf#HTTP2_CLIENT_RST_MAX_COUNT}. */
    public static final Option<Integer> HTTP2_CLIENT_RST_MAX_COUNT =
            of(HttpConf.HTTP2_CLIENT_RST_MAX_COUNT, Integer.class, v -> Math.max(1, v));
    /** HTTP/2 max wait (ms) for WINDOW_UPDATE before FLOW_CONTROL_ERROR. Default: 30000; min 1000. See {@link HttpConf#HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS}. */
    public static final Option<Integer> HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS =
            of(HttpConf.HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS, Integer.class, v -> Math.max(1000, v));
    /** HTTP/2 body read timeout (ms) for Http2BodyInputStream.read. Default: 30000; min 1000. See {@link HttpConf#HTTP2_BODY_READ_TIMEOUT_MS}. */
    public static final Option<Integer> HTTP2_BODY_READ_TIMEOUT_MS =
            of(HttpConf.HTTP2_BODY_READ_TIMEOUT_MS, Integer.class, v -> Math.max(1000, v));
    /** Switch to streaming at first window exhaustion. Default: true. See {@link HttpConf#HTTP2_STREAM_EARLY}. */
    public static final Option<Boolean> HTTP2_STREAM_EARLY =
            of(HttpConf.HTTP2_STREAM_EARLY, Boolean.class);

}
