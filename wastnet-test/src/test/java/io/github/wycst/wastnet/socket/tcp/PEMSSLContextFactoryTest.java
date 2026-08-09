package io.github.wycst.wastnet.socket.tcp;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.security.cert.X509Certificate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PEMSSLContextFactory}.
 * <p>
 * Covers constructors, create(), PEM parsing, Base64 decoding.
 *
 * @author wangyc
 */
public class PEMSSLContextFactoryTest {

    // ==================== Constructor + create with classpath PEM files ====================

    @Test
    public void testCreateFromClasspathCert() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    @Test
    public void testCreateWithKeyPassword() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem", "changeit");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    @Test
    public void testCreateWithEmptyPassword() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem", "");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    // ==================== Base64 utility ====================

    @Test
    public void testBase64DecodeSimple() {
        byte[] decoded = java.util.Base64.getMimeDecoder().decode("SGVsbG8="); // "Hello"
        assertNotNull(decoded);
        assertEquals("Hello", new String(decoded));
    }

    @Test
    public void testBase64DecodeWithoutPadding() {
        byte[] decoded = java.util.Base64.getMimeDecoder().decode("SGVsbG8"); // "Hello" no padding
        assertNotNull(decoded);
        assertEquals("Hello", new String(decoded));
    }

    @Test
    public void testBase64DecodeLongString() {
        String original = "This is a longer test string for base64 decoding with multiple characters!";
        String encoded = java.util.Base64.getEncoder().encodeToString(original.getBytes());
        byte[] decoded = java.util.Base64.getMimeDecoder().decode(encoded);
        assertEquals(original, new String(decoded));
    }

    @Test
    public void testBase64DecodeEmpty() {
        byte[] decoded = java.util.Base64.getMimeDecoder().decode("");
        assertNotNull(decoded);
        assertEquals(0, decoded.length);
    }

    @Test
    public void testBase64DecodeWithNewlines() {
        // Base64 with embedded newlines (as in PEM files)
        String multiLine = "U0FN\nUExF\nIFRF\nU1Q=";
        byte[] decoded = java.util.Base64.getMimeDecoder().decode(multiLine);
        assertEquals("SAMPLE TEST", new String(decoded).trim());
    }

    @Test
    public void testBase64DecodeSingleChar() {
        byte[] decoded = java.util.Base64.getMimeDecoder().decode("Zg=="); // "f"
        assertEquals(1, decoded.length);
        assertEquals('f', decoded[0]);
    }

    @Test
    public void testBase64DecodeTwoChars() {
        byte[] decoded = java.util.Base64.getMimeDecoder().decode("Zm8="); // "fo"
        assertEquals(2, decoded.length);
        assertEquals("fo", new String(decoded));
    }

    @Test
    public void testBase64DecodeInvalidChar() {
        // Lenient MIME decoder ignores non-alphabet characters (e.g. '!') instead of throwing
        byte[] decoded = java.util.Base64.getMimeDecoder().decode("!!");
        assertNotNull(decoded);
    }

    // ==================== classpath: prefix ====================

    @Test
    public void testClasspathPrefix() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("classpath:cert/cert.pem", "classpath:cert/server.pem");
        assertNotNull(factory.create());
    }

    // ==================== Stream-based constructors ====================

    @Test
    public void testStreamBasedConstructor() throws Exception {
        InputStream certIn = openResource("cert/cert.pem");
        InputStream keyIn = openResource("cert/server.pem");
        PEMSSLContextFactory factory = new PEMSSLContextFactory(certIn, keyIn);
        assertNotNull(factory.create());
    }

    @Test
    public void testStreamBasedConstructorWithPassword() throws Exception {
        InputStream certIn = openResource("cert/cert.pem");
        InputStream keyIn = openResource("cert/server.pem");
        PEMSSLContextFactory factory = new PEMSSLContextFactory(certIn, keyIn, "changeit");
        assertNotNull(factory.create());
    }

    // ==================== Trust certificates path ====================

    @Test
    public void testTrustCertsPath() throws Exception {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem", null, "cert/cert.pem");
        SSLContext ctx = factory.create();
        assertNotNull(ctx);
    }

    @Test
    public void testTrustCertsPathNotFound() {
        PEMSSLContextFactory factory = new PEMSSLContextFactory("cert/cert.pem", "cert/server.pem", null, "cert/not-exist.pem");
        try {
            factory.create();
            fail("Expected RuntimeException for missing trust resource");
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof FileNotFoundException);
        }
    }

    // ==================== Filesystem path ====================

    @Test
    public void testFilesystemPath() throws Exception {
        File certFile = copyResourceToTemp("cert/cert.pem");
        File keyFile = copyResourceToTemp("cert/server.pem");
        try {
            PEMSSLContextFactory factory = new PEMSSLContextFactory(certFile.getAbsolutePath(), keyFile.getAbsolutePath());
            assertNotNull(factory.create());
        } finally {
            certFile.delete();
            keyFile.delete();
        }
    }

    // ==================== Error paths ====================

    @Test
    public void testCertPathNotFound() {
        try {
            new PEMSSLContextFactory("cert/not-exist.pem", "cert/server.pem");
            fail("Expected RuntimeException for missing cert resource");
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof FileNotFoundException);
        }
    }

    @Test
    public void testUnsupportedKeyType() {
        // cert.pem holds a CERTIFICATE block, not a PRIVATE KEY -> unsupported key type
        try {
            new PEMSSLContextFactory("cert/cert.pem", "cert/cert.pem");
            fail("Expected RuntimeException for unsupported key type");
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void testNoPrivateKeyFound() {
        // stream with no PEM block -> "No private key found"
        InputStream keyIn = new ByteArrayInputStream("not a pem".getBytes());
        try {
            new PEMSSLContextFactory(openResource("cert/cert.pem"), keyIn);
            fail("Expected RuntimeException for missing private key");
        } catch (RuntimeException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }
    }

    // ==================== Test helpers ====================

    private static InputStream openResource(String path) {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Test resource not found: " + path);
        }
        return in;
    }

    private static File copyResourceToTemp(String path) throws Exception {
        InputStream in = openResource(path);
        File file = File.createTempFile("pem-test-", ".pem");
        FileOutputStream out = new FileOutputStream(file);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > -1) {
            out.write(buf, 0, n);
        }
        in.close();
        out.close();
        return file;
    }
}
