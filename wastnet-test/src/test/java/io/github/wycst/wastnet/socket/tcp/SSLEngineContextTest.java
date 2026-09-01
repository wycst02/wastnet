package io.github.wycst.wastnet.socket.tcp;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SSLEngineContext} — 100% coverage target.
 *
 * @author wangyc
 */
public class SSLEngineContextTest {

    private static SSLContext createCtx() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        return ctx;
    }

    @Test
    public void testConstructorDefaultServerMode() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null);
        assertNotNull(ctx.sslEngine);
        assertFalse(ctx.sslEngine.getUseClientMode());
    }

    @Test
    public void testConstructorClientMode() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null, true);
        assertNotNull(ctx.sslEngine);
        assertTrue(ctx.sslEngine.getUseClientMode());
    }

    @Test
    public void testConstructorWithCipherSuites() throws Exception {
        SSLContext sslCtx = createCtx();
        String[] suites = sslCtx.getServerSocketFactory().getSupportedCipherSuites();
        // Use first supported cipher suite
        String[] selected = new String[]{suites[0]};
        SSLEngineContext ctx = new SSLEngineContext(sslCtx, selected, null, false);
        assertNotNull(ctx.sslEngine);
        // Cipher suites were set (non-null + non-empty branch)
    }

    @Test
    public void testConstructorWithApplicationProtocols() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, new String[]{"h2", "http/1.1"}, false);
        assertNotNull(ctx.sslEngine);
    }

    @Test
    public void testBuffersAllocated() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null);
        assertNotNull(ctx.packetInBuf);
        assertNotNull(ctx.applicationInBuf);
        assertNotNull(ctx.packetOutBuf);
        assertNotNull(ctx.applicationOutBuf);
        assertTrue(ctx.packetInBuf.capacity() > 0);
        assertTrue(ctx.applicationInBuf.capacity() > 0);
    }

    @Test
    public void testDisabledDefault() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null);
        assertFalse(ctx.isDisabled());
    }

    @Test
    public void testSetDisabled() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null);
        ctx.setDisabled(true);
        assertTrue(ctx.isDisabled());
        ctx.setDisabled(false);
        assertFalse(ctx.isDisabled());
    }

    @Test
    public void testGetSSLEngineReturnsSameInstance() throws Exception {
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null);
        assertNotNull(ctx.getSSLEngine());
        assertSame(ctx.sslEngine, ctx.getSSLEngine());
    }

    @Test
    public void testConstructorWithEmptyCipherSuitesArray() throws Exception {
        // non-null but empty -> "length > 0" is false -> setEnabledCipherSuites is skipped
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), new String[0], null, false);
        assertNotNull(ctx.sslEngine);
    }

    @Test
    public void testConstructorWithSslProtocols() throws Exception {
        SSLContext sslCtx = createCtx();
        // sslProtocols is only reachable through the 5-arg constructor. Reuse the protocols the
        // engine already enables, otherwise setEnabledProtocols rejects them.
        String[] protocols = sslCtx.createSSLEngine().getEnabledProtocols();
        assertTrue(protocols.length > 0);
        SSLEngineContext ctx = new SSLEngineContext(sslCtx, null, null, protocols, false);
        assertArrayEquals(protocols, ctx.sslEngine.getEnabledProtocols());
    }

    @Test
    public void testConstructorWithEmptySslProtocolsArray() throws Exception {
        // non-null but empty -> "length > 0" is false -> setEnabledProtocols is skipped
        SSLEngineContext ctx = new SSLEngineContext(createCtx(), null, null, new String[0], false);
        assertNotNull(ctx.sslEngine);
    }
}
