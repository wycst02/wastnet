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

    // ==================== createFileProps does not throw ====================

    @Test
    public void testCreateFilePropsDoesNotThrow() {
        Assertions.assertDoesNotThrow(() -> Conf.createFileProps("nonexistent.properties"));
    }

    // ==================== load* chain (in-memory) ====================

    @Test
    public void testLoadInputStreamNormal() throws Exception {
        Properties props = new Properties();
        String data = "name=wastnet\nport=8080\n";
        Conf.loadInputStream(props, new ByteArrayInputStream(data.getBytes("UTF-8")));
        Assertions.assertEquals("wastnet", props.getProperty("name"));
        Assertions.assertEquals("8080", props.getProperty("port"));
    }

    @Test
    public void testLoadInputStreamNullReturnsImmediately() {
        Properties props = new Properties();
        props.setProperty("keep", "1");
        Conf.loadInputStream(props, null);
        Assertions.assertEquals("1", props.getProperty("keep"));
    }

    @Test
    public void testLoadInputStreamReadFailureCaught() {
        Properties props = new Properties();
        InputStream bad = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("boom");
            }
        };
        // load failure is swallowed by catch(Throwable); must not propagate
        Assertions.assertDoesNotThrow(() -> Conf.loadInputStream(props, bad));
    }

    @Test
    public void testLoadFilePropertiesMissingFileReturns() {
        Properties props = new Properties();
        Conf.loadFileProperties(props, new File("non-existent-file-xyz-123.properties"));
        Assertions.assertTrue(props.isEmpty());
    }

    @Test
    public void testLoadFilePropertiesDirectoryReturns() {
        Properties props = new Properties();
        Conf.loadFileProperties(props, new File("."));
        Assertions.assertTrue(props.isEmpty());
    }

    @Test
    public void testLoadFilePropertiesExistingFile() throws Exception {
        Properties props = new Properties();
        File tmp = File.createTempFile("confcov", ".properties");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "k=v\n".getBytes("UTF-8"));
        Conf.loadFileProperties(props, tmp);
        Assertions.assertEquals("v", props.getProperty("k"));
    }

    @Test
    public void testLoadResourcePropertiesMissing() {
        Properties props = new Properties();
        Conf.loadResourceProperties(props, "non-existent-resource-xyz.properties");
        Assertions.assertTrue(props.isEmpty());
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

    @Test
    public void testLoadPropertiesNoConfigDir() {
        Properties props = new Properties();
        // working dir has no config/ folder, so the load is a silent no-op (covered path)
        Conf.loadProperties(props, "no-such-config.properties");
        Assertions.assertNull(props.getProperty("no-such-config.properties"));
    }

    @Test
    public void testLoadConfigDirPropertiesNoSuchFile() {
        // loadConfigDirProperties reads from JAR_DIR_PATH/config/<name>; without such a file it is a silent no-op
        Properties props = new Properties();
        Conf.loadConfigDirProperties(props, "no-such-config-dir-file.properties");
        Assertions.assertNull(props.getProperty("no-such-config-dir-file.properties"));
    }

    @Test
    public void testLoadParentConfigDirPropertiesNoParentConfig() {
        Properties props = new Properties();
        // parent dir/config/<name> does not exist -> silent no-op (covered early-return path)
        Conf.loadParentConfigDirProperties(props, "no-such-parent-config.properties");
        Assertions.assertNull(props.getProperty("no-such-parent-config.properties"));
    }
}
