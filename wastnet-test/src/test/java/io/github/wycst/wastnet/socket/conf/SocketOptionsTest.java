package io.github.wycst.wastnet.socket.conf;

import io.github.wycst.wastnet.socket.tcp.NioConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers per-instance override (normalizer) semantics of {@link SocketOptions} constants,
 * validating the boundary / clamp / fallback logic introduced by the config isolation change.
 */
class SocketOptionsTest {

    private final NioConfig config = new NioConfig();

    @Test
    void selectTimeoutClampedToUpperBound() {
        // above the 100ms cap -> normalized down to 100
        config.option(SocketOptions.SELECT_TIMEOUT_MS, 5000L);
        assertEquals(100L, config.option(SocketOptions.SELECT_TIMEOUT_MS));

        // valid value kept
        config.option(SocketOptions.SELECT_TIMEOUT_MS, 50L);
        assertEquals(50L, config.option(SocketOptions.SELECT_TIMEOUT_MS));

        // null clears the override -> falls back to default
        config.option(SocketOptions.SELECT_TIMEOUT_MS, null);
        assertEquals(SocketOptions.SELECT_TIMEOUT_MS.value, config.option(SocketOptions.SELECT_TIMEOUT_MS));
    }

    @Test
    void maxConcurrentThreeStateSemantics() {
        int cpu = Runtime.getRuntime().availableProcessors();

        // -1 means "unbounded cached pool", kept as-is
        config.option(SocketOptions.MAX_CONCURRENT, -1);
        assertEquals(-1, (int) config.option(SocketOptions.MAX_CONCURRENT));

        // <= 0 (other than -1) -> cpu * 100
        config.option(SocketOptions.MAX_CONCURRENT, 0);
        assertEquals(cpu * 100, (int) config.option(SocketOptions.MAX_CONCURRENT));

        // positive but below cpu count -> raised to cpu count
        config.option(SocketOptions.MAX_CONCURRENT, 1);
        assertEquals(cpu, (int) config.option(SocketOptions.MAX_CONCURRENT));

        // positive >= cpu count -> kept as-is
        config.option(SocketOptions.MAX_CONCURRENT, 9999);
        assertEquals(9999, (int) config.option(SocketOptions.MAX_CONCURRENT));
    }

    @Test
    void loadBalanceTypeNormalized() {
        // only LEAST_CONN is special, everything else aliases to ROUND_ROBIN
        config.option(SocketOptions.LOAD_BALANCE_TYPE, "LEAST_CONN");
        assertEquals("LEAST_CONN", config.option(SocketOptions.LOAD_BALANCE_TYPE));

        config.option(SocketOptions.LOAD_BALANCE_TYPE, "ROUND_ROBIN");
        assertEquals("ROUND_ROBIN", config.option(SocketOptions.LOAD_BALANCE_TYPE));

        config.option(SocketOptions.LOAD_BALANCE_TYPE, "random");
        assertEquals("ROUND_ROBIN", config.option(SocketOptions.LOAD_BALANCE_TYPE));
    }

    @Test
    void readWriteAndGracefulTimeoutsLowerBoundClamped() {
        // timeouts use max(v, 0L): negatives clamp to 0
        config.option(SocketOptions.READ_TIMEOUT_MS, -5L);
        assertEquals(0L, config.option(SocketOptions.READ_TIMEOUT_MS));

        config.option(SocketOptions.WRITE_TIMEOUT_MS, -5L);
        assertEquals(0L, config.option(SocketOptions.WRITE_TIMEOUT_MS));

        config.option(SocketOptions.GRACEFUL_SHUTDOWN_TIMEOUT_MS, -5L);
        assertEquals(0L, config.option(SocketOptions.GRACEFUL_SHUTDOWN_TIMEOUT_MS));

        // valid positive values kept
        config.option(SocketOptions.READ_TIMEOUT_MS, 30000L);
        assertEquals(30000L, config.option(SocketOptions.READ_TIMEOUT_MS));
    }

    @Test
    void virtualThreadAndSslUnconstrained() {
        // ENABLE_VIRTUAL_THREAD has no normalizer, passthrough
        config.option(SocketOptions.ENABLE_VIRTUAL_THREAD, true);
        assertEquals(Boolean.TRUE, config.option(SocketOptions.ENABLE_VIRTUAL_THREAD));

        // SSL_HANDSHAKE_TIMEOUT_MS has no normalizer, passthrough
        config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS, 10000L);
        assertEquals(10000L, config.option(SocketOptions.SSL_HANDSHAKE_TIMEOUT_MS));
    }

    @Test
    void nullOptionRejected() {
        assertThrows(IllegalArgumentException.class, () -> config.option(null, 1));
        assertThrows(IllegalArgumentException.class, () -> config.option(null));
    }
}
