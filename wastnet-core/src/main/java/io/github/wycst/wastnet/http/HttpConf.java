package io.github.wycst.wastnet.http;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.socket.conf.Conf;
import io.github.wycst.wastnet.util.ConfigLoader;

import java.io.File;
import java.util.Properties;

/**
 * HTTP environment configuration global control class.
 * <p>
 * Supports configuration through wastnet-http.properties, system properties, or Docker environment variables.
 * <p>
 * Configuration file loading priority (from low to high):
 * <ol>
 *     <li>Source code path /wastnet-http.properties</li>
 *     <li>Config directory under source root: /config/wastnet-http.properties</li>
 *     <li>wastnet-http.properties at the same level as the JAR file</li>
 *     <li>wastnet-http.properties in the config directory at the same level as the JAR file</li>
 *     <li>wastnet-http.properties in the config directory at the parent level of the JAR file</li>
 * </ol>
 */
public final class HttpConf extends Conf {

    private HttpConf() {
    }

    private static final Log LOG = LogFactory.getLog(HttpConf.class);

    // ================= Configuration Values =================

    private static final Properties APP_PROPS;

    /**
     * Maximum size of a single header (key + value) in bytes.
     * Key: {@code wastnet.http.max-single-header-size}
     * <p>
     * Default: 8192, Minimum: 1
     */
    public static final int MAX_SINGLE_HEADER_SIZE;

    /**
     * Maximum size of all headers in bytes.
     * Key: {@code wastnet.http.max-http-header-size}
     * <p>
     * Default: 16KB (16384 bytes), Minimum: 1
     */
    public static final int MAX_HTTP_HEADER_SIZE;

    /**
     * Maximum length of request URI (request-target).
     * Key: {@code wastnet.http.max-uri-length}
     * <p>
     * Default: 16384, Minimum: 1
     */
    public static final int MAX_URI_LENGTH;

    /**
     * Body memory threshold in bytes for response buffering.
     * Key: {@code wastnet.http.body-memory-threshold}
     * <p>
     * Default: 512KB, Minimum: 1
     * <p>
     * Used primarily in Response: when buffer size exceeds this threshold,
     * data is flushed to channel immediately to prevent OOM.
     */
    public static final int BODY_MEMORY_THRESHOLD;

    /**
     * Maximum body size to keep in memory for request parsing.
     * Key: {@code wastnet.http.max-body-in-memory}
     * <p>
     * Default: 2MB, Minimum: 1
     * <p>
     * Used primarily in Request: bodies larger than this will be processed
     * as stream instead of being fully loaded into memory.
     */
    public static final int MAX_BODY_IN_MEMORY;

    /**
     * Maximum request body size in bytes.
     * Key: {@code wastnet.http.body-max-size}
     * <p>
     * Default: 536870912 (512MB). A non-positive value (≤ 0, e.g. -1) means unlimited.
     * Units KB/MB/GB are supported (case-insensitive, no unit = bytes); the value must be an integer.
     * When positive, the effective minimum is {@link #BODY_MEMORY_THRESHOLD}.
     * <p>
     * The framework never proactively writes the body to a temporary file; temp
     * files are only created by the application layer while decoding upload
     * attachments. The body is streamed on demand, and {@code getBodyData()}
     * rejects bodies larger than {@code 2 * MAX_BODY_IN_MEMORY}, so even an
     * unlimited value (≤ 0) is memory-safe and will not cause OOM or fill the
     * disk. A finite value here only adds a protocol-level 413 rejection, which
     * is recommended for production to bound slow-send abuse and upload usage.
     */
    public static final long BODY_MAX_SIZE;

    /**
     * Maximum size of a single WebSocket frame payload in bytes.
     * Key: {@code wastnet.http.max-ws-frame-size}
     * <p>
     * Default: 16MB payload (16777216), Minimum: 1
     */
    public static final int MAX_WS_FRAME_SIZE;

    /**
     * Max number of WebSocket continuation frames allowed per fragmented message.
     * <p>
     * Defense against fragmentation attacks where an attacker sends many tiny
     * continuation frames to trigger repeated merge-copy (O(n^2) CPU).
     * Default: 256 frames, Minimum: 1.
     */
    public static final int MAX_WS_CONTINUATIONS;

    /**
     * Max time in milliseconds allowed for a fragmented WebSocket message
     * to be fully merged (only applies to MERGE/BATCH strategies, ignored by STREAM).
     * <p>
     * Defense against slow fragmentation attacks where an attacker sends
     * continuation frames at extremely low rates to hold server resources.
     * After this timeout the connection is closed with 1009.
     * Default: 30 000 ms (30 s), Minimum: 1 000 ms.
     */
    public static final long MAX_WS_FRAGMENT_MERGE_TIMEOUT_MS;

    /**
     * Enable temporary file generation.
     * Key: {@code wastnet.http.enable-temp-file}
     * <p>
     * Default: true
     * <p>
     * Whether to generate temporary files when body exceeds memory threshold.
     * If disabled, multipart fields that would require temp files will be skipped.
     */
    public static final boolean ENABLE_TEMP_FILE;

    /**
     * Temporary file directory.
     * Key: {@code wastnet.http.temp-file-dir}
     * <p>
     * Default: {java.io.tmpdir}/wastnet-http
     */
    public static final String TEMP_FILE_DIR;

    /**
     * Temporary file prefix.
     * Key: {@code wastnet.http.temp-file-prefix}
     * <p>
     * Default: "wastnet_tmp_"
     */
    public static final String TEMP_FILE_PREFIX;

    /**
     * Default character set.
     * Key: {@code wastnet.http.default-charset}
     * <p>
     * Default: "UTF-8"
     */
    public static final String DEFAULT_CHARSET;

    /**
     * Enable GZIP compression.
     * Key: {@code wastnet.http.gzip}
     * <p>
     * Default: false
     */
    public static final boolean GZIP;

    /**
     * Minimum size threshold for GZIP compression.
     * Key: {@code wastnet.http.gzip-min-size}
     * <p>
     * No GZIP compression if smaller than this value
     * Default: 2KB, Minimum: 0 (no minimum limit)
     */
    public static final int GZIP_MIN_SIZE;

    /**
     * Write default HTTP response headers (Date, Server, Connection).
     * Key: {@code wastnet.http.header.default.enabled}
     * <p>
     * Default: true
     */
    public static final boolean WRITE_DEFAULT_HEADERS;

    /**
     * Expose Server header in HTTP response.
     * Key: {@code wastnet.http.server-header.expose}
     * <p>
     * Default: false (security best practice: hide server info)
     */
    public static final boolean EXPOSE_SERVER_HEADER;

    /**
     * Enable HTTP/1.1 Pipelining support.
     * Key: {@code wastnet.http.pipeline.enabled}
     * <p>
     * Default: false
     * <p>
     * When enabled, multiple HTTP requests in a single TCP packet will be processed.
     * When disabled, extra requests after the first one will be discarded.
     */
    public static final boolean PIPELINE_ENABLED;

    /**
     * Maximum elapsed time in milliseconds from first byte arrival to full request reception.
     * Key: {@code wastnet.http.request-timeout}
     * <p>
     * Checked non-blockingly on each NIO data arrival; exceeding this limit returns 408 Request Timeout.
     * Covers the entire request decoding lifecycle (start line, headers, and body).
     * Default: 60000 (60 seconds). Negative or zero value is treated as disabled ({@link Long#MAX_VALUE}).
     */
    public static final long REQUEST_TIMEOUT_MS;

    /**
     * Preserve HTTP header insertion order.
     * Key: {@code wastnet.http.header.order.preserve}
     * <p>
     * Default: false (use HashMap for better performance).
     * When true, uses LinkedHashMap to maintain insertion order.
     */
    public static final boolean PRESERVE_HEADER_ORDER;

    /**
     * SSE connection timeout in milliseconds.
     * Key: {@code wastnet.http.sse-timeout-ms}
     * <p>
     * After this timeout, the SSE connection will be closed automatically.
     * Default: 1800000 (30 minutes). Non-positive value disables timeout.
     */
    public static final long SSE_TIMEOUT_MS;

    /** Comma-separated HTTP methods implemented (e.g. "GET,POST"). Null = all methods. */
    public static final String IMPLEMENTED_METHODS;

    /**
     * HTTP/2 initial send/receive window size in bytes.
     * Key: {@code wastnet.http2.initial.send-window-size}
     * <p>
     * Default: 65535. Clamped to [{@code 0xFFFF}, {@code 0xFFFFFF}] (i.e. ~64KB - 16MB).
     */
    public static final int HTTP2_INITIAL_SEND_WINDOW_SIZE;

    /**
     * HTTP/2 server-side max concurrent streams limit.
     * Key: {@code wastnet.http2.max-concurrent-streams}
     * <p>
     * Default: 512. Minimum allowed value is 100; any configured value below 100
     * is clamped up to 100. A bounded value prevents resource exhaustion from
     * excessive concurrent streams (MadeYouReset / Rapid Reset attacks).
     */
    public static final int HTTP2_MAX_CONCURRENT_STREAMS;

    /**
     * HTTP/2 client RST_STREAM rate-limit window, in seconds.
     * Key: {@code wastnet.http2.client-rst.window-seconds}
     * <p>
     * Used by the Rapid Reset (CVE-2023-44487) defense: inbound client RST_STREAM
     * frames are counted per connection within a fixed window of this length.
     * Minimum allowed value is 1 (any lower is clamped up to 1).
     */
    public static final int HTTP2_CLIENT_RST_WINDOW_SECONDS;

    /**
     * HTTP/2 maximum allowed client RST_STREAM frames within the window.
     * Key: {@code wastnet.http2.client-rst.max-count}
     * <p>
     * Default: 100, minimum allowed value is 1.
     * <p>
     * <b>Note:</b> counting uses a fixed window, so due to window-boundary straddling
     * the actual allowed rate can reach up to ~2x this value in the worst case.
     * Configure a lower value for a stricter effective cap.
     */
    public static final int HTTP2_CLIENT_RST_MAX_COUNT;

    /**
     * Enable HPACK Huffman encoding for HTTP/2 header compression.
     * Key: {@code wastnet.http2.hpack.huffman.enabled}
     * <p>
     * Default: true. When enabled, Huffman encoding is applied to ASCII-only
     * header strings longer than 5 bytes for optimal bandwidth savings.
     * Disable for debugging or CPU-constrained environments.
     */
    public static final boolean HTTP2_HPACK_HUFFMAN_ENABLED;

    /**
     * Max time in milliseconds to wait for the peer to grant additional send window
     * via WINDOW_UPDATE before treating the connection as FLOW_CONTROL_ERROR and
     * closing it (RFC 7540 §6.9.2).
     * Key: {@code wastnet.http2.flow-control-wait-timeout-ms}
     * <p>
     * Default: 30000 (30s), min: 1000 (1s).
     */
    public static final int HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS;

    /**
     * Max time in milliseconds an application thread may block in
     * {@code Http2BodyInputStream.read(byte[], int, int)} waiting for the peer to deliver DATA frames.
     * Key: {@code wastnet.http2.body-read-timeout-ms}
     * <p>
     * Default: 30000 (30s), min: 1000 (1s). On expiry the read throws SocketTimeoutException,
     * leaving error handling to the application layer.
     *
     * @since 1.0.2
     */
    public static final int HTTP2_BODY_READ_TIMEOUT_MS;

    /**
     * Enter streaming mode at first window exhaustion instead of buffering up to MAX_STREAM_CAPACITY_SIZE.
     * Key: {@code wastnet.http2.stream-early}
     * <p>
     * Default: true. When enabled, a stream switches to streaming as soon as the
     * initial receive window is exhausted (ring buffer = INITIAL_RECEIVE_WINDOW_SIZE),
     * greatly cutting per-stream memory for large request bodies.
     */
    public static final boolean HTTP2_STREAM_EARLY;

    /**
     * Maximum HPACK dynamic table size the server advertises and enforces
     * (RFC 7541 §6.2 / §6.3, SETTINGS_HEADER_TABLE_SIZE, identifier 0x1).
     * Key: {@code wastnet.http2.max-header-table-size}
     * <p>
     * Default: 4096 (RFC protocol default). The decoder rejects any dynamic
     * table size update exceeding this value, so it also bounds per-connection
     * decoder memory. Max allowed value is 1 MB (1048576).
     */
    public static final int HTTP2_MAX_HEADER_TABLE_SIZE;

    // ================= Static Initialization Block =================

    static {
        APP_PROPS = ConfigLoader.createFileProps("wastnet-http.properties");

        // Initialize configuration values
        MAX_SINGLE_HEADER_SIZE = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.max-single-header-size", 8192L))); // Default: 8192 (8KB), min: 1
        MAX_HTTP_HEADER_SIZE = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.max-http-header-size", 16384L))); // Default: 16384 (16KB), min: 1
        MAX_URI_LENGTH = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.max-uri-length", 16384L))); // Default: 16384, min: 1
        BODY_MEMORY_THRESHOLD = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.body-memory-threshold", 512L * 1024))); // Default: 512KB, min: 1
        MAX_BODY_IN_MEMORY = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.max-body-in-memory", 2L * 1024 * 1024))); // Default: 2MB, min: 1
        long bodyMaxSize = getPropSize(APP_PROPS, "wastnet.http.body-max-size", 512L * 1024 * 1024);
        BODY_MAX_SIZE = bodyMaxSize <= 0 ? Long.MAX_VALUE : Math.max(bodyMaxSize, BODY_MEMORY_THRESHOLD); // Non-positive value means unlimited, otherwise min is BODY_MEMORY_THRESHOLD
        MAX_WS_FRAME_SIZE = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.max-ws-frame-size", 16L * 1024 * 1024))); // Default: 16MB, min: 1
        MAX_WS_CONTINUATIONS = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, getPropSize(APP_PROPS, "wastnet.http.max-ws-continuations", 256))); // Default: 256, min: 1
        MAX_WS_FRAGMENT_MERGE_TIMEOUT_MS = Math.min(Long.MAX_VALUE, Math.max(1000L, getPropSize(APP_PROPS, "wastnet.http.max-ws-fragment-merge-timeout-ms", 30000L))); // Default: 30s, min: 1s
        ENABLE_TEMP_FILE = isPropTrue(APP_PROPS, "wastnet.http.enable-temp-file", true); // Default: true

        String tempDir = getProperty(APP_PROPS, "wastnet.http.temp-file-dir");
        String baseTempDir = (tempDir != null && !tempDir.trim().isEmpty()) ? tempDir.trim() : System.getProperty("java.io.tmpdir") + "/wastnet-http";

        // Validate and create temp directory
        File tempDirFile = new File(baseTempDir);
        if (!tempDirFile.exists()) {
            if (!tempDirFile.mkdirs()) {
                // Fallback to system temp dir if creation fails
                tempDirFile = new File(System.getProperty("java.io.tmpdir"));
                LOG.error("[HttpConf] Failed to create temp directory: {}, fallback to: {}", baseTempDir, tempDirFile.getAbsolutePath());
            }
        } else if (!tempDirFile.isDirectory()) {
            // Path exists but is not a directory, fallback
            tempDirFile = new File(System.getProperty("java.io.tmpdir"));
            LOG.error("[HttpConf] Temp path is not a directory: {}, fallback to: {}", baseTempDir, tempDirFile.getAbsolutePath());
        }
        TEMP_FILE_DIR = tempDirFile.getAbsolutePath();

        String tempPrefix = getProperty(APP_PROPS, "wastnet.http.temp-file-prefix");
        TEMP_FILE_PREFIX = (tempPrefix != null && !tempPrefix.trim().isEmpty()) ? tempPrefix.trim() : "wastnet_tmp_"; // Default: "wastnet_tmp_"

        String charset = getProperty(APP_PROPS, "wastnet.http.default-charset");
        DEFAULT_CHARSET = (charset != null && !charset.trim().isEmpty()) ? charset.trim() : "UTF-8"; // Default: "UTF-8"

        GZIP = isPropTrue(APP_PROPS, "wastnet.http.gzip"); // Default: false
        GZIP_MIN_SIZE = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, getPropSize(APP_PROPS, "wastnet.http.gzip-min-size", 2048L))); // Default: 2048 (2KB), min: 0 (no minimum limit)
        WRITE_DEFAULT_HEADERS = isPropTrue(APP_PROPS, "wastnet.http.header.default.enabled", true); // Default: true
        EXPOSE_SERVER_HEADER = isPropTrue(APP_PROPS, "wastnet.http.server-header.expose"); // Default: false
        PIPELINE_ENABLED = isPropTrue(APP_PROPS, "wastnet.http.pipeline.enabled"); // Default: false
        long requestTimeout = getPropLong(APP_PROPS, "wastnet.http.request-timeout", 60000); // Default: 60000 (60s), disabled if <= 0
        REQUEST_TIMEOUT_MS = requestTimeout <= 0 ? Long.MAX_VALUE : requestTimeout;
        PRESERVE_HEADER_ORDER = isPropTrue(APP_PROPS, "wastnet.http.header.order.preserve"); // Default: false
        long sseTimeout = getPropLong(APP_PROPS, "wastnet.http.sse-timeout-ms", 1800000L);
        SSE_TIMEOUT_MS = sseTimeout <= 0 ? Long.MAX_VALUE : sseTimeout;

        String implementedMethods = getProperty(APP_PROPS, "wastnet.http.implemented-methods");
        IMPLEMENTED_METHODS = (implementedMethods != null && !implementedMethods.trim().isEmpty()) ? implementedMethods.trim() : null;

        long http2InitWindow = getPropSize(APP_PROPS, "wastnet.http2.initial.send-window-size", 0xFFFFL);
        HTTP2_INITIAL_SEND_WINDOW_SIZE = (int) Math.min(Math.max(http2InitWindow, 0xFFFFL), 0xFFFFFFL);
        HTTP2_MAX_CONCURRENT_STREAMS = Math.max(100, getPropInt(APP_PROPS, "wastnet.http2.max-concurrent-streams", 512)); // Default: 512, min: 100
        HTTP2_CLIENT_RST_WINDOW_SECONDS = Math.max(1, getPropInt(APP_PROPS, "wastnet.http2.client-rst.window-seconds", 1));
        HTTP2_CLIENT_RST_MAX_COUNT = Math.max(1, getPropInt(APP_PROPS, "wastnet.http2.client-rst.max-count", 100));
        HTTP2_HPACK_HUFFMAN_ENABLED = isPropTrue(APP_PROPS, "wastnet.http2.hpack.huffman.enabled", true);
        HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS = Math.max(1000, getPropInt(APP_PROPS, "wastnet.http2.flow-control-wait-timeout-ms", 30000)); // Default: 30000ms, min: 1000ms
        HTTP2_BODY_READ_TIMEOUT_MS = Math.max(1000, getPropInt(APP_PROPS, "wastnet.http2.body-read-timeout-ms", 30000)); // Default: 30000ms, min: 1000ms
        HTTP2_STREAM_EARLY = isPropTrue(APP_PROPS, "wastnet.http2.stream-early", true); // Default: true
        HTTP2_MAX_HEADER_TABLE_SIZE = (int) Math.min(1048576L, Math.max(4096L, getPropSize(APP_PROPS, "wastnet.http2.max-header-table-size", 4096L))); // Default: 4096, min: 4096, max: 1MB
    }

    // ================= Public Methods =================

    /**
     * Get configuration property value.
     * <p>
     * Priority: System Properties > Environment Variables > Configuration File
     *
     * @param key configuration key
     * @return configuration value, null if not exists
     */
    public static String getProperty(String key) {
        return getProperty(APP_PROPS, key);
    }

    /**
     * Dump all HTTP configuration as properties format.
     * <p>
     * Returns a properties string containing all configuration key-value pairs.
     *
     * @return properties string of all configurations
     */
    public static String dumpAsProperties() {
        return "# HTTP Configuration\n\n" +
                "# ---- HTTP/1.1 ----\n" +
                "wastnet.http.max-single-header-size=" + MAX_SINGLE_HEADER_SIZE + '\n' +
                "wastnet.http.max-http-header-size=" + MAX_HTTP_HEADER_SIZE + '\n' +
                "wastnet.http.max-uri-length=" + MAX_URI_LENGTH + '\n' +
                "wastnet.http.body-memory-threshold=" + BODY_MEMORY_THRESHOLD + '\n' +
                "wastnet.http.max-body-in-memory=" + MAX_BODY_IN_MEMORY + '\n' +
                "wastnet.http.body-max-size=" + BODY_MAX_SIZE + '\n' +
                "wastnet.http.enable-temp-file=" + ENABLE_TEMP_FILE + '\n' +
                "wastnet.http.temp-file-dir=" + TEMP_FILE_DIR + '\n' +
                "wastnet.http.temp-file-prefix=" + TEMP_FILE_PREFIX + '\n' +
                "wastnet.http.default-charset=" + DEFAULT_CHARSET + '\n' +
                "wastnet.http.gzip=" + GZIP + '\n' +
                "wastnet.http.gzip-min-size=" + GZIP_MIN_SIZE + '\n' +
                "wastnet.http.header.default.enabled=" + WRITE_DEFAULT_HEADERS + '\n' +
                "wastnet.http.server-header.expose=" + EXPOSE_SERVER_HEADER + '\n' +
                "wastnet.http.header.order.preserve=" + PRESERVE_HEADER_ORDER + '\n' +
                "wastnet.http.pipeline.enabled=" + PIPELINE_ENABLED + '\n' +
                "wastnet.http.request-timeout=" + REQUEST_TIMEOUT_MS + '\n' +
                "wastnet.http.implemented-methods=" + (IMPLEMENTED_METHODS == null ? "" : IMPLEMENTED_METHODS) + '\n' +
                "\n# ---- HTTP/2 ----\n" +
                "wastnet.http2.initial.send-window-size=" + HTTP2_INITIAL_SEND_WINDOW_SIZE + '\n' +
                "wastnet.http2.max-concurrent-streams=" + HTTP2_MAX_CONCURRENT_STREAMS + '\n' +
                "wastnet.http2.max-header-table-size=" + HTTP2_MAX_HEADER_TABLE_SIZE + '\n' +
                "wastnet.http2.flow-control-wait-timeout-ms=" + HTTP2_FLOW_CONTROL_WAIT_TIMEOUT_MS + '\n' +
                "wastnet.http2.body-read-timeout-ms=" + HTTP2_BODY_READ_TIMEOUT_MS + '\n' +
                "wastnet.http2.stream-early=" + HTTP2_STREAM_EARLY + '\n' +
                "wastnet.http2.hpack.huffman.enabled=" + HTTP2_HPACK_HUFFMAN_ENABLED + '\n' +
                "wastnet.http2.client-rst.window-seconds=" + HTTP2_CLIENT_RST_WINDOW_SECONDS + '\n' +
                "wastnet.http2.client-rst.max-count=" + HTTP2_CLIENT_RST_MAX_COUNT + '\n' +
                "\n# ---- WebSocket ----\n" +
                "wastnet.http.max-ws-frame-size=" + MAX_WS_FRAME_SIZE + '\n' +
                "wastnet.http.max-ws-continuations=" + MAX_WS_CONTINUATIONS + '\n' +
                "wastnet.http.max-ws-fragment-merge-timeout-ms=" + MAX_WS_FRAGMENT_MERGE_TIMEOUT_MS + '\n' +
                "\n# ---- SSE ----\n" +
                "wastnet.http.sse-timeout-ms=" + SSE_TIMEOUT_MS + '\n';
    }
}
