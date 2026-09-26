package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

/**
 * onStarted/onStopped startup banner
 * branches, the private logNetworkAddresses enumeration, and pemSSL(InputStream, InputStream).
 */
public class HTTPServerTest {

    private static void setBannerEnabled(Object instance, boolean value) {
        ((HTTPServer) instance).startupBannerEnabled = value;
    }

    // Expose protected hooks so onStarted/onStopped can be driven without a live bind.
    private static class ExposedHTTPServer extends HTTPServer {
        ExposedHTTPServer(int port) {
            super(port);
        }
        @Override
        protected void onStarted() {
            super.onStarted();
        }
        @Override
        protected void onStopped() {
            super.onStopped();
        }
        boolean sslEnabled() {
            return isSsl();
        }
    }

    @Test
    public void testOnStartedBannerLocalHttp() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        server.localOnly(false); // enumerate network addresses
        setBannerEnabled(server, true);
        Assertions.assertFalse(server.sslEnabled());
        server.onStarted();
    }

    @Test
    public void testOnStartedBannerLocalHttps() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18443);
        server.localOnly(false);
        server.ssl(true);
        setBannerEnabled(server, true);
        Assertions.assertTrue(server.sslEnabled());
        server.onStarted();
    }

    @Test
    public void testOnStartedBannerLocalOnlySkipsNetworkEnumeration() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        server.localOnly(true); // localOnly -> logNetworkAddresses not called
        setBannerEnabled(server, true);
        server.onStarted();
    }

    @Test
    public void testOnStartedBannerDisabled() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        setBannerEnabled(server, false); // early return
        server.onStarted();
    }

    @Test
    public void testOnStoppedBannerEnabled() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        setBannerEnabled(server, true);
        server.onStopped();
    }

    @Test
    public void testOnStoppedBannerDisabled() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        setBannerEnabled(server, false); // early return
        server.onStopped();
    }

    @Test
    public void testPemSSLWithStreams() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18443);
        InputStream certIn = HTTPServerTest.class.getResourceAsStream("/cert/cert.pem");
        InputStream keyIn = HTTPServerTest.class.getResourceAsStream("/cert/server.pem");
        Assertions.assertNotNull(certIn, "cert.pem resource must exist");
        Assertions.assertNotNull(keyIn, "server.pem resource must exist");
        Assertions.assertSame(server, server.pemSSL(certIn, keyIn));
        certIn.close();
        keyIn.close();
    }
}
