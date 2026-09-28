package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Drives the HTTPServer lifecycle through start()/stop() (which internally invoke
 * onStarted()/onStopped()) instead of calling the protected hooks directly. Most
 * tests disable the startup banner to keep output clean; two tests enable it to
 * cover the banner-printing / logNetworkAddresses branches and assert the
 * localOnly skip behavior. SSL state and the pemSSL(InputStream, InputStream)
 * fluent API are still asserted.
 */
public class HTTPServerTest {

    private static class ExposedHTTPServer extends HTTPServer {
        ExposedHTTPServer(int port) {
            super(port);
        }
        boolean sslEnabled() {
            return isSsl();
        }
    }

    @Test
    public void testStartStopLocalHttp() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        server.localOnly(false);
        server.startupBannerEnabled(false);
        Assertions.assertFalse(server.sslEnabled());
        server.start();
        server.stop();
    }

    @Test
    public void testStartStopLocalHttps() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18443);
        server.localOnly(false);
        server.ssl(true);
        server.startupBannerEnabled(false);
        InputStream certIn = HTTPServerTest.class.getResourceAsStream("/cert/cert.pem");
        InputStream keyIn = HTTPServerTest.class.getResourceAsStream("/cert/server.pem");
        Assertions.assertNotNull(certIn, "cert.pem resource must exist");
        Assertions.assertNotNull(keyIn, "server.pem resource must exist");
        server.pemSSL(certIn, keyIn);
        Assertions.assertTrue(server.sslEnabled());
        try {
            server.start();
        } finally {
            server.stop();
            certIn.close();
            keyIn.close();
        }
    }

    @Test
    public void testStartStop() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18080);
        server.startupBannerEnabled(false);
        server.start();
        server.stop();
    }

    // ---- banner-enabled tests: cover onStarted() printing + logNetworkAddresses ----

    @Test
    public void testStartStopLocalOnlySkipsNetworkEnumeration() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18081);
        server.localOnly(true);
        server.startupBannerEnabled(true);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            server.start();
        } finally {
            System.setOut(originalOut);
            server.stop();
        }
        String out = baos.toString();
        Assertions.assertFalse(out.contains(">>  Network:"),
                "localOnly server must not enumerate network addresses, but output was:\n" + out);
    }

    @Test
    public void testStartStopEnumeratesNetworkAddresses() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18082);
        server.localOnly(false);
        server.startupBannerEnabled(true);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            server.start();
        } finally {
            System.setOut(originalOut);
            server.stop();
        }
        String out = baos.toString();
        Assertions.assertTrue(out.contains("started in"),
                "banner should print the startup line, but output was:\n" + out);
        // localOnly=false enters logNetworkAddresses; the actual Network lines depend on
        // host network interfaces, so they are not asserted here (branch coverage only).
    }

    @Test
    public void testPemSSLWithStreams() throws Exception {
        ExposedHTTPServer server = new ExposedHTTPServer(18443);
        server.startupBannerEnabled(false);
        InputStream certIn = HTTPServerTest.class.getResourceAsStream("/cert/cert.pem");
        InputStream keyIn = HTTPServerTest.class.getResourceAsStream("/cert/server.pem");
        Assertions.assertNotNull(certIn, "cert.pem resource must exist");
        Assertions.assertNotNull(keyIn, "server.pem resource must exist");
        Assertions.assertSame(server, server.pemSSL(certIn, keyIn));
        certIn.close();
        keyIn.close();
    }
}
