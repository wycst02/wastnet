package io.github.wycst.wastnet.socket.conf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Unit tests for {@link Conf} utility methods.
 * <p>
 * Tests cover getPropInt, getPropLong, isPropTrue parsing and fallback logic.
 * <p>
 * Note: The test is in the same package as Conf to access protected static methods.
 */
public class ConfTest {

    // ==================== getPropInt ====================

    @Test
    public void testGetPropIntValidValue() {
        Properties props = new Properties();
        props.setProperty("key", "42");
        int result = Conf.getPropInt(props, "key", 0);
        Assertions.assertEquals(42, result);
    }

    @Test
    public void testGetPropIntInvalidValueReturnsDefault() {
        Properties props = new Properties();
        props.setProperty("key", "not-a-number");
        int result = Conf.getPropInt(props, "key", 99);
        Assertions.assertEquals(99, result);
    }

    @Test
    public void testGetPropIntMissingKeyReturnsDefault() {
        Properties props = new Properties();
        int result = Conf.getPropInt(props, "missing-key", 77);
        Assertions.assertEquals(77, result);
    }

    @Test
    public void testGetPropIntNegativeValue() {
        Properties props = new Properties();
        props.setProperty("key", "-5");
        int result = Conf.getPropInt(props, "key", 0);
        Assertions.assertEquals(-5, result);
    }

    // ==================== getPropLong ====================

    @Test
    public void testGetPropLongValidValue() {
        Properties props = new Properties();
        props.setProperty("key", "10000000000");
        long result = Conf.getPropLong(props, "key", 0L);
        Assertions.assertEquals(10000000000L, result);
    }

    @Test
    public void testGetPropLongInvalidValueReturnsDefault() {
        Properties props = new Properties();
        props.setProperty("key", "invalid");
        long result = Conf.getPropLong(props, "key", 500L);
        Assertions.assertEquals(500L, result);
    }

    @Test
    public void testGetPropLongMissingKeyReturnsDefault() {
        Properties props = new Properties();
        long result = Conf.getPropLong(props, "missing", 123L);
        Assertions.assertEquals(123L, result);
    }

    // ==================== isPropTrue ====================

    @Test
    public void testIsPropTrueExactMatch() {
        Properties props = new Properties();
        props.setProperty("key", "true");
        boolean result = Conf.isPropTrue(props, "key");
        Assertions.assertTrue(result);
    }

    @Test
    public void testIsPropTrueCaseSensitiveMustBeLowercase() {
        Properties props = new Properties();
        props.setProperty("key", "True");
        boolean result = Conf.isPropTrue(props, "key");
        Assertions.assertFalse(result);
    }

    @Test
    public void testIsPropTrueMissingKeyReturnsFalse() {
        Properties props = new Properties();
        boolean result = Conf.isPropTrue(props, "missing");
        Assertions.assertFalse(result);
    }

    @Test
    public void testIsPropTrueFalseValueReturnsFalse() {
        Properties props = new Properties();
        props.setProperty("key", "false");
        Assertions.assertFalse(Conf.isPropTrue(props, "key"));
    }

    @Test
    public void testIsPropTrueWithDefaultWhenMissing() {
        Properties props = new Properties();
        Assertions.assertTrue(Conf.isPropTrue(props, "missing", true));
        Assertions.assertFalse(Conf.isPropTrue(props, "missing", false));
    }

    @Test
    public void testIsPropTrueWithDefaultAndValueExists() {
        Properties props = new Properties();
        props.setProperty("key", "TRUE");
        // "TRUE" (uppercase) with case-insensitive overload
        Assertions.assertTrue(Conf.isPropTrue(props, "key", false));
    }

    @Test
    public void testIsPropTrueWithDefaultAndFalseValue() {
        Properties props = new Properties();
        props.setProperty("key", "false");
        Assertions.assertFalse(Conf.isPropTrue(props, "key", true));
    }

    @Test
    public void testGetPropertyFromPropsTrims() {
        Properties props = new Properties();
        props.setProperty("gp.key", "  spaced  ");
        Assertions.assertEquals("spaced", Conf.getProperty(props, "gp.key"));
    }

    @Test
    public void testGetPropertySystemOverridesProps() {
        try {
            System.setProperty("gp.syskey", "sysval");
            Properties props = new Properties();
            props.setProperty("gp.syskey", "propsval");
            Assertions.assertEquals("sysval", Conf.getProperty(props, "gp.syskey"));
        } finally {
            System.clearProperty("gp.syskey");
        }
    }

    @Test
    public void testGetPropertyMissingReturnsNull() {
        Properties props = new Properties();
        Assertions.assertNull(Conf.getProperty(props, "gp.absent"));
    }

    @Test
    public void testGetPropertyFromEnv() {
        // PATH exists as an environment variable on every normal OS; covers the getenv branch
        Properties props = new Properties();
        Assertions.assertNotNull(Conf.getProperty(props, "PATH"));
    }
}
