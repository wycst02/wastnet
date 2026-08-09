package io.github.wycst.wastnet.env;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * @since 2024-2-19
 * @author wangyc
 */
class RuntimeEnvJDK9Plus extends RuntimeEnv {

    private final MethodHandle getApplicationProtocolHandle;
    private final MethodHandle setApplicationProtocolsHandle;

    RuntimeEnvJDK9Plus() {
        MethodHandle getHandle = null;
        MethodHandle setHandle = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            getHandle = lookup.unreflect(SSLEngine.class.getMethod("getApplicationProtocol"));
            setHandle = lookup.unreflect(SSLParameters.class.getMethod("setApplicationProtocols", String[].class));
        } catch (Throwable ignored) {
        }
        getApplicationProtocolHandle = getHandle;
        setApplicationProtocolsHandle = setHandle;
    }

    @Override
    public void setApplicationProtocols(SSLEngine sslEngine, String[] applicationProtocols) {
        if (applicationProtocols == null || applicationProtocols.length == 0) {
            return;
        }
        if (setApplicationProtocolsHandle == null) {
            return;
        }
        try {
            SSLParameters sslParameters = sslEngine.getSSLParameters();
            setApplicationProtocolsHandle.invokeExact(sslParameters, applicationProtocols);
            sslEngine.setSSLParameters(sslParameters);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String getSSLApplicationProtocol(SSLEngine sslEngine) {
        if (sslEngine == null) {
            return null;
        }
        try {
            return (String) getApplicationProtocolHandle.invokeExact(sslEngine);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
