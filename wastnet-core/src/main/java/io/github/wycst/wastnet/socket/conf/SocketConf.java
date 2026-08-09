package io.github.wycst.wastnet.socket.conf;

import io.github.wycst.wastnet.env.RuntimeEnv;

import java.util.Properties;

public final class SocketConf extends Conf {

    // Prevent instantiation of this static configuration holder.
    private SocketConf() {
    }

    /**
     * Enable virtual thread.
     * Key: {@code wastnet.socket.virtual-thread.enabled}
     */
    public static final boolean ENABLE_VIRTUAL_THREAD;

    /**
     * Select timeout in milliseconds.
     * Key: {@code wastnet.socket.select-timeout-ms}
     * <p>
     * Range: 10-100ms (capped at 100ms)
     */
    public static final long SELECT_TIMEOUT_MS;

    /**
     * Select empty poll count threshold.
     * Key: {@code wastnet.socket.select-empty-count}
     */
    public static final int SELECT_EMPTY_COUNT;

    /**
     * Default sync runner.
     * Key: {@code wastnet.socket.default-sync-runner}
     * <p>
     * Example: -Dwastnet.socket.default-sync-runner=true
     */
    public static final boolean DEFAULT_SYNC_RUNNER;

    private static final Properties APP_PROPS;
    public static final boolean WINDOWS_PLATFORM;

    static {
        boolean isWindows = false;
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            isWindows = osName.contains("win");
        } catch (Throwable ignored) {
        }
        WINDOWS_PLATFORM = isWindows;
    }

    /**
     * Maximum concurrent requests (runner thread pool max size).
     * Key: {@code wastnet.socket.max-concurrent}
     * <p>
     * Default: CPU cores * 100.
     * Values ≤ 0 (except -1) fall back to default; values below CPU cores
     * are clamped to the core count. Set to -1 for an unbounded cached thread pool.
     */
    public static final int MAX_CONCURRENT;

    /**
     * Load balance strategy for TCP server worker selection.
     * Key: {@code wastnet.socket.load-balance-strategy}
     * <p>
     * Optional values (case-sensitive):
     * <ul>
     * <li>{@code ROUND_ROBIN} – Assign clients to workers in round-robin order (default)</li>
     * <li>{@code LEAST_CONN} – Assign clients to the worker with the fewest active connections</li>
     * </ul>
     * Example: {@code -Dwastnet.socket.load-balance-strategy=LEAST_CONN}
     */
    public static String LOAD_BALANCE_TYPE;

    /**
     * SSL handshake timeout in milliseconds.
     * Key: {@code wastnet.socket.ssl.handshake-timeout-ms}
     * <p>
     * Default: 5000 (5 seconds), 0 = no timeout
     * <p>
     * SSL handshake timeout period (milliseconds)
     */
    public static final long SSL_HANDSHAKE_TIMEOUT_MS;

    /**
     * Read timeout in milliseconds for blocking read operations.
     * Key: {@code wastnet.socket.read-timeout-ms}
     * <p>
     * Default: 10000 (10 seconds), 0 = no timeout.
     * <p>
     * This timeout is intentionally kept relatively short because it mainly
     * guards <b>trusted/expected blocking reads</b> (e.g. reading a fixed-length
     * frame header or a known payload length during frame decoding). Such reads
     * should complete promptly once the peer starts sending; a long stall with
     * no bytes received indicates a stuck or malicious connection. It must be
     * smaller than the request-level timeout ({@code wastnet.http.request-timeout})
     * so it can release a blocked worker thread even when no new data event
     * arrives. Set it to 0 only for special scenarios (e.g. push-only long
     * connections) where the server does not read from the client.
     */
    public static final long READ_TIMEOUT_MS;

    /**
     * Write timeout in milliseconds for channel write operations.
     * Key: {@code wastnet.socket.write-timeout-ms}
     * <p>
     * Default: 30000 (30 seconds), 0 = no timeout
     */
    public static final long WRITE_TIMEOUT_MS;

    /**
     * Graceful shutdown timeout in milliseconds.
     * Key: {@code wastnet.socket.graceful-shutdown-timeout-ms}
     * <p>
     * Default: 10000 (10 seconds), the server waits up to this duration
     * for in-flight requests to complete before forcefully closing connections.
     */
    public static final long GRACEFUL_SHUTDOWN_TIMEOUT_MS;

    private static final boolean USE_LEAST_CONNECTIONS;

    static {
        APP_PROPS = createFileProps("wastnet-socket.properties");

        ENABLE_VIRTUAL_THREAD = RuntimeEnv.JDK_VERSION >= 21f && isPropTrue(APP_PROPS, "wastnet.socket.virtual-thread.enabled");
        SELECT_TIMEOUT_MS = Math.min(100, getPropInt(APP_PROPS, "wastnet.socket.select-timeout-ms", 100)); // 10 - 100ms
        SELECT_EMPTY_COUNT = getPropInt(APP_PROPS, "wastnet.socket.select-empty-count", 1024);
        DEFAULT_SYNC_RUNNER = isPropTrue(APP_PROPS, "wastnet.socket.default-sync-runner");
        // Maximum concurrent requests (default: CPU cores * 100, -1 = unbounded)
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int maxConcurrent = getPropInt(APP_PROPS, "wastnet.socket.max-concurrent", cpuCores * 100);
        if (maxConcurrent <= 0 && maxConcurrent != -1) {
            maxConcurrent = cpuCores * 100; // invalid value, fall back to default
        }
        MAX_CONCURRENT = maxConcurrent == -1 ? -1 : Math.max(maxConcurrent, cpuCores);
        // Load balance strategy
        String lbType = APP_PROPS.getProperty("wastnet.socket.load-balance-strategy");
        LOAD_BALANCE_TYPE = lbType != null ? lbType : "ROUND_ROBIN";
        USE_LEAST_CONNECTIONS = "LEAST_CONN".equals(LOAD_BALANCE_TYPE);
        // SSL handshake timeout (5 seconds)
        SSL_HANDSHAKE_TIMEOUT_MS = getPropLong(APP_PROPS, "wastnet.socket.ssl.handshake-timeout-ms", 5000L);
        // Read timeout (10 seconds; 0 = unlimited)
        READ_TIMEOUT_MS = Math.max(0L, getPropLong(APP_PROPS, "wastnet.socket.read-timeout-ms", 10000L));
        // Write timeout (30 seconds)
        WRITE_TIMEOUT_MS = Math.max(0, getPropLong(APP_PROPS, "wastnet.socket.write-timeout-ms", 30000L));
        // Graceful shutdown timeout (10 seconds)
        GRACEFUL_SHUTDOWN_TIMEOUT_MS = Math.max(0, getPropLong(APP_PROPS, "wastnet.socket.graceful-shutdown-timeout-ms", 10000L));
    }

    public static boolean useLoadBalanceLeastConnections() {
        return USE_LEAST_CONNECTIONS;
    }

    public static String getProperty(String key) {
        return getProperty(APP_PROPS, key);
    }
}
