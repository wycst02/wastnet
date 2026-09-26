package io.github.wycst.wastnet.env;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * tests for {@link RuntimeEnv} (JDK-version adaptation).
 */
public class RuntimeEnvTest {

    @Test
    public void testStaticFieldsInitialized() {
        Assertions.assertTrue(RuntimeEnv.JDK_VERSION > 0);
        Assertions.assertNotNull(RuntimeEnv.INSTANCE);
    }

    @Test
    public void testGetSSLApplicationProtocolReturnsNull() {
        Assertions.assertNull(RuntimeEnv.INSTANCE.getSSLApplicationProtocol(null));
    }


}
