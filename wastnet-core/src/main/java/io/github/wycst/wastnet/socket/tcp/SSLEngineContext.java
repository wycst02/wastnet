package io.github.wycst.wastnet.socket.tcp;

import io.github.wycst.wastnet.env.RuntimeEnv;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.nio.ByteBuffer;

/**
 * SSL engine context containing SSLEngine and its associated buffers.
 */
public class SSLEngineContext {
    final SSLEngine sslEngine;
    final ByteBuffer packetInBuf;
    final ByteBuffer applicationInBuf;
    final ByteBuffer packetOutBuf;
    final ByteBuffer applicationOutBuf;

    boolean disabled;
    static final int HEADER_DELTA = 2048;

    public SSLEngineContext(SSLContext sslContext, String[] sslCipherSuites, String[] applicationProtocols) {
        this(sslContext, sslCipherSuites, applicationProtocols, null, false);
    }

    public SSLEngineContext(SSLContext sslContext, String[] sslCipherSuites, String[] applicationProtocols, boolean useClientMode) {
        this(sslContext, sslCipherSuites, applicationProtocols, null, useClientMode);
    }

    public SSLEngineContext(SSLContext sslContext, String[] sslCipherSuites, String[] applicationProtocols, String[] sslProtocols, boolean useClientMode) {
        sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(useClientMode);
        if (sslCipherSuites != null && sslCipherSuites.length > 0) {
            sslEngine.setEnabledCipherSuites(sslCipherSuites);
        }
        if (sslProtocols != null && sslProtocols.length > 0) {
            sslEngine.setEnabledProtocols(sslProtocols);
        }
        RuntimeEnv.INSTANCE.setApplicationProtocols(sslEngine, applicationProtocols);
        SSLSession session = sslEngine.getSession();
        packetInBuf = ByteBuffer.allocate(session.getPacketBufferSize());
        applicationInBuf = ByteBuffer.allocate(session.getApplicationBufferSize());
        packetOutBuf = ByteBuffer.allocate(session.getPacketBufferSize() + HEADER_DELTA);
        // Reserved outbound-plaintext buffer; current design wraps caller buffers directly, so this stays unused.
        applicationOutBuf = ByteBuffer.allocate(0);
        session.invalidate();
    }

    /**
     * Get the underlying SSLEngine for advanced configuration
     * (e.g. hostname verification, cipher suites, enabled protocols).
     *
     * @return the SSLEngine
     */
    public SSLEngine getSSLEngine() {
        return sslEngine;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDisabled() {
        return disabled;
    }
}
