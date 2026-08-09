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
}
