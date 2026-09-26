package io.github.wycst.wastnet.util;

import io.github.wycst.wastnet.socket.conf.Conf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Unit tests for {@link ConfigLoader}.
 */
public class ConfigLoaderTest {

    // ==================== createFileProps does not throw ====================

    @Test
    public void testCreateFilePropsDoesNotThrow() {
        Assertions.assertDoesNotThrow(() -> ConfigLoader.createFileProps("nonexistent.properties"));
    }

    // ==================== load* chain (in-memory) ====================

    @Test
    public void testLoadInputStreamNormal() throws Exception {
        Properties props = new Properties();
        String data = "name=wastnet\nport=8080\n";
        ConfigLoader.loadInputStream(props, new ByteArrayInputStream(data.getBytes("UTF-8")));
        Assertions.assertEquals("wastnet", props.getProperty("name"));
        Assertions.assertEquals("8080", props.getProperty("port"));
    }

    @Test
    public void testLoadInputStreamNullReturnsImmediately() {
        Properties props = new Properties();
        props.setProperty("keep", "1");
        ConfigLoader.loadInputStream(props, null);
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
        Assertions.assertDoesNotThrow(() -> ConfigLoader.loadInputStream(props, bad));
    }

    @Test
    public void testLoadResourcePropertiesMissing() {
        Properties props = new Properties();
        ConfigLoader.loadResourceProperties(props, "non-existent-resource-xyz.properties");
        Assertions.assertTrue(props.isEmpty());
    }

    @Test
    public void testLoadFilePropertiesMissingFileReturns() {
        Properties props = new Properties();
        ConfigLoader.loadFileProperties(props, new File("non-existent-file-xyz-123.properties"));
        Assertions.assertTrue(props.isEmpty());
    }

    @Test
    public void testLoadFilePropertiesDirectoryReturns() {
        Properties props = new Properties();
        ConfigLoader.loadFileProperties(props, new File("."));
        Assertions.assertTrue(props.isEmpty());
    }

    @Test
    public void testLoadFilePropertiesExistingFile() throws Exception {
        Properties props = new Properties();
        File tmp = File.createTempFile("confcov", ".properties");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "k=v\n".getBytes("UTF-8"));
        ConfigLoader.loadFileProperties(props, tmp);
        Assertions.assertEquals("v", props.getProperty("k"));
    }

    @Test
    public void testLoadParentConfigDirPropertiesNoParentConfig() {
        Properties props = new Properties();
        // parent dir/config/<name> does not exist -> silent no-op (covered early-return path)
        ConfigLoader.loadParentConfigDirProperties(props, "no-such-parent-config.properties");
        Assertions.assertNull(props.getProperty("no-such-parent-config.properties"));
    }

    @Test
    public void testLoadConfigDirPropertiesNoSuchFile() {
        // loadConfigDirProperties reads from JAR_DIR_PATH/config/<name>; without such a file it is a silent no-op
        Properties props = new Properties();
        ConfigLoader.loadConfigDirProperties(props, "no-such-config-dir-file.properties");
        Assertions.assertNull(props.getProperty("no-such-config-dir-file.properties"));
    }

    @Test
    public void testLoadPropertiesNoConfigDir() {
        Properties props = new Properties();
        // working dir has no config/ folder, so the load is a silent no-op (covered path)
        ConfigLoader.loadProperties(props, "no-such-config.properties");
        Assertions.assertNull(props.getProperty("no-such-config.properties"));
    }
}
