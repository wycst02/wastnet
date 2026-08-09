package io.github.wycst.wastnet.socket.conf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

/**
 * Unit tests for {@link SocketConf} static configuration holder.
 * <p>
 * Covers the private constructor, the load-balance helper and the property accessor.
 * Referencing the static fields also triggers and thus covers the static initializer.
 */
public class SocketConfTest {

    @Test
    public void testPrivateConstructorIsAccessibleViaReflection() throws Exception {
        Constructor<SocketConf> ctor = SocketConf.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        SocketConf instance = ctor.newInstance();
        Assertions.assertNotNull(instance);
    }

    @Test
    public void testUseLoadBalanceLeastConnectionsReturnsBoolean() {
        // Default environment uses ROUND_ROBIN unless overridden by wastnet-socket.properties
        Assertions.assertFalse(SocketConf.useLoadBalanceLeastConnections());
    }

    @Test
    public void testGetPropertyReturnsNullForUnknownKey() {
        Assertions.assertNull(SocketConf.getProperty("wastnet.socket.unknown.test.key"));
    }

    @Test
    public void testStaticFieldsAreInitializedWithDefaults() {
        // Select timeout capped at 100ms, default 100
        Assertions.assertTrue(SocketConf.SELECT_TIMEOUT_MS > 0 && SocketConf.SELECT_TIMEOUT_MS <= 100);
        // Write timeout default 30s
        Assertions.assertEquals(30000L, SocketConf.WRITE_TIMEOUT_MS);
        // SSL handshake timeout default 5s
        Assertions.assertEquals(5000L, SocketConf.SSL_HANDSHAKE_TIMEOUT_MS);
        // Graceful shutdown timeout default 10s
        Assertions.assertEquals(10000L, SocketConf.GRACEFUL_SHUTDOWN_TIMEOUT_MS);
        // Read timeout default 10s
        Assertions.assertEquals(10000L, SocketConf.READ_TIMEOUT_MS);
    }
}
