package io.github.wycst.wastnet.env;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Adaptation to different versions of JDK. The JDK9+ ALPN APIs
 * (SSLEngine.getApplicationProtocol / SSLParameters.setApplicationProtocols)
 * are accessed via MethodHandle reflection, so this single class loads safely
 * on JDK8 (the handles simply stay null and the methods become no-ops).
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

    private static final MethodHandle GET_APPLICATION_PROTOCOL_HANDLE;
    private static final MethodHandle SET_APPLICATION_PROTOCOLS_HANDLE;

    RuntimeEnv() {}

    static {
        WINDOWS_PLATFORM = System.getProperty("os.name", "").toLowerCase().contains("win");

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

        MethodHandle getHandle = null;
        MethodHandle setHandle = null;
        if (JDK9PLUS) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                getHandle = lookup.unreflect(SSLEngine.class.getMethod("getApplicationProtocol"));
                setHandle = lookup.unreflect(SSLParameters.class.getMethod("setApplicationProtocols", String[].class));
            } catch (Throwable ignored) {
            }
        }
        GET_APPLICATION_PROTOCOL_HANDLE = getHandle;
        SET_APPLICATION_PROTOCOLS_HANDLE = setHandle;

        INSTANCE = new RuntimeEnv();
    }

    public void setApplicationProtocols(SSLEngine sslEngine, String[] applicationProtocols) {
        if (applicationProtocols == null || SET_APPLICATION_PROTOCOLS_HANDLE == null) {
            return;
        }
        try {
            SSLParameters sslParameters = sslEngine.getSSLParameters();
            SET_APPLICATION_PROTOCOLS_HANDLE.invokeExact(sslParameters, applicationProtocols);
            sslEngine.setSSLParameters(sslParameters);
        } catch (Throwable ignored) {
        }
    }

    public String getSSLApplicationProtocol(SSLEngine sslEngine) {
        if (GET_APPLICATION_PROTOCOL_HANDLE == null) {
            return null;
        }
        try {
            return (String) GET_APPLICATION_PROTOCOL_HANDLE.invokeExact(sslEngine);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
