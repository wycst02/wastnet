package io.github.wycst.wastnet.util;

import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Instance-independent helper for loading {@link Properties} from the standard
 * "externalized config" locations, in increasing priority:
 * <ol>
 *   <li>classpath root</li>
 *   <li>classpath /config</li>
 *   <li>JAR directory</li>
 *   <li>JAR directory /config</li>
 *   <li>JAR parent directory /config</li>
 * </ol>
 * Later locations override earlier ones (so an external file overrides a bundled one).
 * All methods are static; there is no instance state, hence no instance dependency.
 */
public final class ConfigLoader {

    /**
     * JAR file directory path (without trailing separator).
     */
    public static final String JAR_DIR_PATH;

    /**
     * JAR parent directory path (with trailing separator).
     */
    public static final String JAR_PARENT_PATH;

    static {
        String path = ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        try {
            path = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
        }
        if (path.endsWith(".jar")) {
            path = path.substring(0, path.lastIndexOf("/") + 1);
        } else {
            if (path.endsWith("/classes/")) {
                path = path.substring(0, path.length() - "/classes/".length());
            }
        }
        File file = new File(path);
        JAR_DIR_PATH = file.getAbsolutePath();
        // Note: getAbsolutePath() returns path without trailing separator
        int lastIndex = JAR_DIR_PATH.lastIndexOf(File.separator);
        String jarParentPath = JAR_DIR_PATH;
        if (lastIndex > -1) {
            jarParentPath = JAR_DIR_PATH.substring(0, lastIndex + 1);
        }
        JAR_PARENT_PATH = jarParentPath;
    }

    private ConfigLoader() {
    }

    /**
     * Load properties from relative to JAR directory.
     */
    public static void loadProperties(Properties props, String file) {
        loadFileProperties(props, new File(JAR_DIR_PATH + File.separator + file));
    }

    /**
     * Load properties from config subdirectory relative to JAR directory.
     */
    public static void loadConfigDirProperties(Properties props, String file) {
        loadFileProperties(props, new File(JAR_DIR_PATH + File.separator + "config" + File.separator + file));
    }

    /**
     * Load properties from config subdirectory relative to JAR parent directory.
     */
    public static void loadParentConfigDirProperties(Properties props, String file) {
        loadFileProperties(props, new File(JAR_PARENT_PATH + File.separator + "config" + File.separator + file));
    }

    /**
     * Load properties from file (silent no-op when missing or a directory).
     */
    public static void loadFileProperties(Properties props, File file) {
        try {
            if (!file.exists() || file.isDirectory()) {
                return;
            }
            loadInputStream(props, Files.newInputStream(file.toPath()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Load properties from classpath resource using the context class loader.
     */
    public static void loadResourceProperties(Properties props, String path) {
        loadResourceProperties(props, ConfigLoader.class.getClassLoader(), path);
    }

    public static void loadResourceProperties(Properties props, ClassLoader cl, String path) {
        // Class.getResourceAsStream strips the leading '/'; ClassLoader does not,
        // so strip it here to keep identical classpath resolution behavior.
        String name = path.startsWith("/") ? path.substring(1) : path;
        // Fall back to this class's loader when cl is null, so classpath resources are
        // always attempted (original Conf behavior never skipped classpath loading).
        ClassLoader resolver = cl != null ? cl : ConfigLoader.class.getClassLoader();
        loadInputStream(props, resolver.getResourceAsStream(name));
    }

    /**
     * Load properties from input stream (silent no-op on null or read failure).
     */
    public static void loadInputStream(Properties props, InputStream is) {
        if (is == null) {
            return;
        }
        try (InputStream in = is) {
            props.load(in);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Create and load properties from standard locations.
     * <p>
     * Loading priority (from low to high):
     * <ol>
     *     <li>/filename</li>
     *     <li>/config/filename</li>
     *     <li>JAR directory/filename</li>
     *     <li>JAR directory/config/filename</li>
     *     <li>JAR parent directory/config/filename</li>
     * </ol>
     *
     * @param filename configuration file name
     * @return new Properties object with loaded configuration
     */
    public static Properties createFileProps(String filename) {
        return createFileProps(ConfigLoader.class.getClassLoader(), filename, false);
    }

    /**
     * Load {@code filename} from the standard locations using the given class loader to
     * resolve classpath resources. Always includes the two classpath locations.
     *
     * @param cl       class loader used to resolve classpath resources
     * @param filename config file name (bare name, e.g. application.properties)
     * @return merged Properties across all searched locations
     */
    public static Properties createFileProps(ClassLoader cl, String filename) {
        return createFileProps(cl, filename, false);
    }

    /**
     * Load {@code filename} searching the standard locations, in increasing priority.
     * When {@code ignoreInternal} is true the two classpath (jar-internal) locations are
     * skipped, so only external files (JAR dir, JAR /config, parent /config) are loaded.
     *
     * @param cl             class loader used to resolve classpath resources
     * @param filename       config file name (bare name, e.g. application.properties)
     * @param ignoreInternal skip the two classpath resources when true
     * @return merged Properties across all searched locations
     */
    public static Properties createFileProps(ClassLoader cl, String filename, boolean ignoreInternal) {
        Properties props = new Properties();
        if (!ignoreInternal) {
            loadResourceProperties(props, cl, "/" + filename);
            loadResourceProperties(props, cl, "/config/" + filename);
        }
        loadProperties(props, filename);
        loadConfigDirProperties(props, filename);
        loadParentConfigDirProperties(props, filename);
        return props;
    }
}
