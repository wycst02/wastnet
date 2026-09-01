package io.github.wycst.wastnet.socket.conf;

import java.util.function.Function;

/**
 * Registry of strongly-typed socket-level options, mirroring the static
 * defaults in {@link SocketConf}. Each constant is an {@link Option} whose
 * {@code defaultValue} is used by {@code NioConfig#option(Option)} when no
 * per-instance override is set.
 *
 * <p>Options whose underlying {@link SocketConf} field carries a range/enum
 * validation declare the same constraint via {@link Option#normalizer}, so that a
 * {@code NioConfig#option(Option, value)} override is normalized exactly like
 * the global default read in {@link SocketConf}.</p>
 *
 * @since 1.0.2
 */
public class SocketOptions {

    /** Global ordinal counter shared by this class and subclasses (unique index per option). */
    private static int seq = 0;

    protected SocketOptions() {
    }

    /**
     * Allocate the next fixed ordinal for an option (called once per declared
     * constant, in declaration order).
     *
     * @return the next ordinal, starting from 0
     */
    protected static int nextIndex() {
        return seq++;
    }

    /**
     * Creates a typed {@link Option} (no constraint) with a unique index via {@link #nextIndex()}.
     *
     * @param defaultValue value returned when no instance overrides it
     * @param type         runtime class of the value
     * @param <T>          value type
     * @return the option instance
     */
    protected static <T> Option<T> of(T defaultValue, Class<T> type) {
        return Option.of(nextIndex(), defaultValue, type);
    }

    /**
     * Creates a typed {@link Option} that normalizes every override via the given normalizer.
     *
     * @param defaultValue value returned when no instance overrides it (assumed already valid)
     * @param type         runtime class of the value
     * @param normalizer   normalizer applied to every override
     * @param <T>          value type
     * @return the option instance
     */
    protected static <T> Option<T> of(T defaultValue, Class<T> type, Function<T, T> normalizer) {
        return Option.of(nextIndex(), defaultValue, type, normalizer);
    }

    /** Enable virtual threads for worker tasks. Default: false (auto-on only when JDK >= 21). See {@link SocketConf#ENABLE_VIRTUAL_THREAD}. */
    public static final Option<Boolean> ENABLE_VIRTUAL_THREAD =
            of(SocketConf.ENABLE_VIRTUAL_THREAD, Boolean.class);
    /** Selector poll timeout in ms (capped at 100). Default: 100. Lower = more responsive wake-ups but more CPU. See {@link SocketConf#SELECT_TIMEOUT_MS}. */
    public static final Option<Long> SELECT_TIMEOUT_MS =
            of(SocketConf.SELECT_TIMEOUT_MS, Long.class, v -> Math.min(v, 100L));
    /** Number of empty select() polls before the worker re-checks registration state. Default: 1024. See {@link SocketConf#SELECT_EMPTY_COUNT}. */
    public static final Option<Integer> SELECT_EMPTY_COUNT =
            of(SocketConf.SELECT_EMPTY_COUNT, Integer.class);
    /** Max concurrent request-runner threads. Default: CPU*100; -1 = unbounded pool; values below CPU clamped to CPU. See {@link SocketConf#MAX_CONCURRENT}. */
    public static final Option<Integer> MAX_CONCURRENT =
            of(SocketConf.MAX_CONCURRENT, Integer.class, SocketOptions::normalizeMaxConcurrent);
    /** Worker load-balance strategy: "ROUND_ROBIN" (default) or "LEAST_CONN". See {@link SocketConf#LOAD_BALANCE_TYPE}. */
    public static final Option<String> LOAD_BALANCE_TYPE =
            of(SocketConf.LOAD_BALANCE_TYPE, String.class, v -> "LEAST_CONN".equals(v) ? "LEAST_CONN" : "ROUND_ROBIN");
    /** SSL handshake timeout in ms. Default: 5000; 0 = no timeout. See {@link SocketConf#SSL_HANDSHAKE_TIMEOUT_MS}. */
    public static final Option<Long> SSL_HANDSHAKE_TIMEOUT_MS =
            of(SocketConf.SSL_HANDSHAKE_TIMEOUT_MS, Long.class);
    /** Blocking read timeout in ms (guards trusted fixed-length reads). Default: 10000; 0 = no timeout. See {@link SocketConf#READ_TIMEOUT_MS}. */
    public static final Option<Long> READ_TIMEOUT_MS =
            of(SocketConf.READ_TIMEOUT_MS, Long.class, v -> Math.max(v, 0L));
    /** Channel write timeout in ms. Default: 30000; 0 = no timeout. See {@link SocketConf#WRITE_TIMEOUT_MS}. */
    public static final Option<Long> WRITE_TIMEOUT_MS =
            of(SocketConf.WRITE_TIMEOUT_MS, Long.class, v -> Math.max(v, 0L));
    /** Graceful shutdown wait in ms for in-flight requests. Default: 10000. See {@link SocketConf#GRACEFUL_SHUTDOWN_TIMEOUT_MS}. */
    public static final Option<Long> GRACEFUL_SHUTDOWN_TIMEOUT_MS =
            of(SocketConf.GRACEFUL_SHUTDOWN_TIMEOUT_MS, Long.class, v -> Math.max(v, 0L));

    /**
     * Normalize the max-concurrent override, mirroring {@link SocketConf#MAX_CONCURRENT}:
     * -1 keeps the unbounded cached pool; values <= 0 (other than -1) fall back to the default
     * (CPU cores * 100); any other positive value is raised to at least the CPU core count.
     */
    private static int normalizeMaxConcurrent(int v) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        if (v == -1) {
            return -1;
        }
        if (v <= 0) {
            return cpuCores * 100;
        }
        return Math.max(v, cpuCores);
    }
}
