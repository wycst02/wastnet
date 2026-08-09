package io.github.wycst.wastnet.env;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Coverage tests for {@link RuntimeEnv} and its subclasses.
 */
public class RuntimeEnvTest {

    @Test
    public void testStaticFieldsInitialized() {
        Assertions.assertTrue(RuntimeEnv.JDK_VERSION > 0);
        Assertions.assertNotNull(RuntimeEnv.INSTANCE);
    }

    @Test
    public void testInstanceTypeOnCurrentJdk() {
        if (RuntimeEnv.JDK9PLUS) {
            Assertions.assertTrue(RuntimeEnv.INSTANCE instanceof RuntimeEnvJDK9Plus);
        } else {
            Assertions.assertEquals(RuntimeEnv.class, RuntimeEnv.INSTANCE.getClass());
        }
    }

    @Test
    public void testGetSSLApplicationProtocolReturnsNull() {
        Assertions.assertNull(RuntimeEnv.INSTANCE.getSSLApplicationProtocol(null));
    }

    @Test
    public void testBaseEnvInstantiation() {
        RuntimeEnv below = new RuntimeEnv();
        Assertions.assertNotNull(below);
        Assertions.assertNull(below.getSSLApplicationProtocol(null));
        // setApplicationProtocols is a no-op in the base class (not overridden)
        below.setApplicationProtocols(null, null);
    }
}
