package io.github.wycst.wastnet.socket.conf;

import java.io.*;
import java.util.Properties;

/**
 * Base configuration environment class providing common configuration loading functionality.
 */
public abstract class Conf {

    /**
     * JAR file directory path (without trailing separator).
     */
    public static final String JAR_DIR_PATH;

    /**
     * JAR parent directory path (with trailing separator).
     */
    public static final String JAR_PARENT_PATH;

    static {
        String path = Conf.class.getProtectionDomain().getCodeSource().getLocation().getPath();
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

    /**
     * Load properties from relative to JAR directory.
     *
     * @param props Properties object to load into
     * @param file  filename
     */
    public static void loadProperties(Properties props, String file) {
        loadFileProperties(props, new File(JAR_DIR_PATH + File.separator + file));
    }

    /**
     * Load properties from config subdirectory relative to JAR directory.
     *
     * @param props Properties object to load into
     * @param file  filename
     */
    public static void loadConfigDirProperties(Properties props, String file) {
        loadFileProperties(props, new File(JAR_DIR_PATH + File.separator + "config" + File.separator + file));
    }

    /**
     * Load properties from config subdirectory relative to JAR parent directory.
     *
     * @param props Properties object to load into
     * @param file  filename
     */
    public static void loadParentConfigDirProperties(Properties props, String file) {
        loadFileProperties(props, new File(JAR_PARENT_PATH + File.separator + "config" + File.separator + file));
    }

    /**
     * Load properties from file.
     *
     * @param props Properties object to load into
     * @param file  File object
     */
    public static void loadFileProperties(Properties props, File file) {
        try {
            if (!file.exists() || file.isDirectory()) {
                return;
            }
            loadInputStream(props, new FileInputStream(file));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Load properties from classpath resource.
     *
     * @param props Properties object to load into
     * @param path  resource path
     */
    public static void loadResourceProperties(Properties props, String path) {
        loadInputStream(props, Conf.class.getResourceAsStream(path));
    }

    /**
     * Load properties from input stream.
     *
     * @param props Properties object to load into
     * @param is    InputStream
     */
    public static void loadInputStream(Properties props, InputStream is) {
        try {
            if (is == null) return;
            props.load(is);
        } catch (Throwable ignored) {
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Get configuration property value.
     * <p>
     * Priority: System Properties > Environment Variables > Configuration File
     *
     * @param props Properties object to read from
     * @param key   configuration key
     * @return configuration value, null if not exists
     */
    protected static String getProperty(Properties props, String key) {
        Properties sysProperties = System.getProperties();
        if (sysProperties.containsKey(key)) {
            return sysProperties.getProperty(key);
        }
        if (System.getenv().containsKey(key)) {
            return System.getenv(key);
        }
        String value = props.getProperty(key);
        return value == null ? null : value.trim();
    }

    /**
     * Check if property value is true.
     *
     * @param props Properties object to read from
     * @param key   configuration key
     * @return true if value is "true", false otherwise
     */
    protected static boolean isPropTrue(Properties props, String key) {
        return "true".equals(getProperty(props, key));
    }

    /**
     * Check if property value is true with default value.
     *
     * @param props        Properties object to read from
     * @param key          configuration key
     * @param defaultValue default value if property not found
     * @return true if value is "true", false if "false", defaultValue otherwise
     */
    protected static boolean isPropTrue(Properties props, String key, boolean defaultValue) {
        String value = getProperty(props, key);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value);
    }

    /**
     * Get property value as integer.
     *
     * @param props        Properties object to read from
     * @param key          configuration key
     * @param defaultValue default value if property not found or invalid
     * @return integer value of property, or defaultValue if not found/invalid
     */
    protected static int getPropInt(Properties props, String key, int defaultValue) {
        String value = getProperty(props, key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignore) {
            }
        }
        return defaultValue;
    }

    /**
     * Get property value as long.
     *
     * @param props        Properties object to read from
     * @param key          configuration key
     * @param defaultValue default value if property not found or invalid
     * @return long value of property, or defaultValue if not found/invalid
     */
    protected static long getPropLong(Properties props, String key, long defaultValue) {
        String value = getProperty(props, key);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignore) {
            }
        }
        return defaultValue;
    }

    /**
     * Get a size property supporting KB/MB/GB units (case-insensitive); no unit means bytes.
     * The value must be an integer; decimals are rejected and fall back to defaultValue.
     *
     * @param props        Properties object to read from
     * @param key          configuration key
     * @param defaultValue default if absent or invalid
     * @return size in bytes
     */
    protected static long getPropSize(Properties props, String key, long defaultValue) {
        String value = getProperty(props, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return parseSize(value);
        } catch (IllegalArgumentException ignore) {
            return defaultValue;
        }
    }

    // Parse size: integer + optional KB/MB/GB (no unit = bytes); Long.parseLong rejects decimals.
    public static long parseSize(String text) {
        String s = text.trim().toUpperCase();
        int numEnd = s.length();
        int shift = 0; // 0=B, 10=KB, 20=MB, 30=GB
        if (numEnd > 0 && s.charAt(numEnd - 1) == 'B') {
            if (--numEnd > 0) {
                char c = s.charAt(numEnd - 1);
                if (c == 'K') shift = 10;
                else if (c == 'M') shift = 20;
                else if (c == 'G') shift = 30;
                if (shift != 0) {
                    --numEnd;
                }
            }
        }
        long num;
        try {
            num = Long.parseLong(s.substring(0, numEnd).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid size number: " + text);
        }
        if (num > (Long.MAX_VALUE >> shift)) {
            throw new IllegalArgumentException("Size too large: " + text);
        }
        return num << shift;
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
    protected static Properties createFileProps(String filename) {
        Properties props = new Properties();
        loadResourceProperties(props, "/" + filename);
        loadResourceProperties(props, "/config/" + filename);
        loadProperties(props, filename);
        loadConfigDirProperties(props, filename);
        loadParentConfigDirProperties(props, filename);
        return props;
    }
}
