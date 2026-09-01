package io.github.wycst.wastnet.env;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

import javax.net.ssl.SSLEngine;
/**
 * Adaptation to different versions of JDK
 *
 * @since 2024-2-19
 * @author wangyc
 */
public class RuntimeEnv {

    static final Log LOG = LogFactory.getLog(RuntimeEnv.class);

    public static final RuntimeEnv INSTANCE;
    public static final float JDK_VERSION;
    public static final boolean JDK9PLUS;
    public static final boolean WINDOWS_PLATFORM;

    RuntimeEnv() {}

    static {
        boolean isWindows = false;
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            isWindows = osName.contains("win");
        } catch (Throwable ignored) {
        }
        WINDOWS_PLATFORM = isWindows;

        float jdkVersion = 1.8f;
        try {
            String version = System.getProperty("java.specification.version");
            // java.specification.version is never null; any parse failure falls through to the catch below
            jdkVersion = Float.parseFloat(version);
        } catch (Throwable e) {
            LOG.warn("Failed to parse JDK version: {}", e.getMessage());
        }
        JDK_VERSION = jdkVersion;
        JDK9PLUS = jdkVersion >= 9f;
        INSTANCE = JDK9PLUS ? new RuntimeEnvJDK9Plus() : new RuntimeEnv();
    }

    public void setApplicationProtocols(SSLEngine sslEngine, String[] applicationProtocols) {}

    public String getSSLApplicationProtocol(SSLEngine sslEngine) {
        return null;
    }
}
