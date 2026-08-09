package io.github.wycst.wastnet.socket.tcp;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * PEM-based SSLContextFactory, compatible with OpenSSL-generated PEM files.
 * <p>
 * Accepts PEM certificate chain ({@code -----BEGIN CERTIFICATE-----})
 * and PKCS#8 private key ({@code -----BEGIN PRIVATE KEY-----}), provided either
 * as filesystem/classpath paths or as input streams. All sources are normalized
 * into a certificate chain and a private key at construction time.
 * </p>
 *
 * <pre>
 * HTTPServer.of(port)
 *     .pemSSL("cert/cert.pem", "cert/server.pem")
 *     .applicationProtocols("h2", "http/1.1")
 *     .requestHandler(router).start();
 * </pre>
 *
 * @author wangyc
 */
public class PEMSSLContextFactory implements SSLContextFactory {

    private final Certificate[] certChain;
    private final PrivateKey privateKey;
    private final String keyPassword;
    private final String trustCertsPath;

    // ==================== Path-based (filesystem or classpath) ====================

    public PEMSSLContextFactory(String certPath, String keyPath) {
        this(certPath, keyPath, null, null);
    }

    public PEMSSLContextFactory(String certPath, String keyPath, String keyPassword) {
        this(certPath, keyPath, keyPassword, null);
    }

    public PEMSSLContextFactory(String certPath, String keyPath, String keyPassword, String trustCertsPath) {
        this(resolveCertChain(resolveStream(certPath)), resolvePrivateKey(resolveStream(keyPath)), keyPassword, trustCertsPath);
    }

    // ==================== Stream-based ====================

    public PEMSSLContextFactory(InputStream certIn, InputStream keyIn) {
        this(certIn, keyIn, null);
    }

    public PEMSSLContextFactory(InputStream certIn, InputStream keyIn, String keyPassword) {
        this(resolveCertChain(certIn), resolvePrivateKey(keyIn), keyPassword, null);
    }

    // ==================== Unified core constructor ====================

    private PEMSSLContextFactory(Certificate[] certChain, PrivateKey privateKey, String keyPassword, String trustCertsPath) {
        this.certChain = certChain;
        this.privateKey = privateKey;
        this.keyPassword = keyPassword;
        this.trustCertsPath = trustCertsPath;
    }

    @Override
    public SSLContext create() {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(null, null);
            char[] password = keyPassword != null ? keyPassword.toCharArray() : new char[0];
            keyStore.setKeyEntry("server", privateKey, password, certChain);

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            if (trustCertsPath != null) {
                KeyStore trustStore = KeyStore.getInstance("JKS");
                trustStore.load(null, null);
                Certificate[] trustedCerts = loadCertificates(openStream(trustCertsPath));
                for (int i = 0; i < trustedCerts.length; ++i) {
                    trustStore.setCertificateEntry("ca-" + i, trustedCerts[i]);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            } else {
                sslContext.init(kmf.getKeyManagers(), null, null);
            }
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSLContext from PEM", e);
        }
    }

    private static Certificate[] loadCertificates(InputStream in) throws Exception {
        List<Certificate> certs = new ArrayList<Certificate>();
        List<byte[]> pemBlocks = parsePEM(in);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (byte[] der : pemBlocks) {
            certs.add(cf.generateCertificate(new ByteArrayInputStream(der)));
        }
        return certs.toArray(new Certificate[0]);
    }

    private static PrivateKey loadPrivateKey(InputStream in) throws Exception {
        List<byte[]> pemBlocks = parsePEM(in);
        if (pemBlocks.isEmpty()) {
            throw new IllegalArgumentException("No private key found in PEM data");
        }
        byte[] keyBytes = pemBlocks.get(0);
        String[] algorithms = {"RSA", "EC"};
        for (String alg : algorithms) {
            try {
                KeyFactory kf = KeyFactory.getInstance(alg);
                return kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Unsupported key type (only RSA and EC PKCS#8 supported)");
    }

    // Resolve helpers: wrap checked exceptions into RuntimeException so they can be
    // used inside explicit constructor delegation (this(...)) without a throws clause.

    private static InputStream resolveStream(String path) {
        try {
            return openStream(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open PEM resource: " + path, e);
        }
    }

    private static Certificate[] resolveCertChain(InputStream in) {
        try {
            return loadCertificates(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load certificate from PEM", e);
        }
    }

    private static PrivateKey resolvePrivateKey(InputStream in) {
        try {
            return loadPrivateKey(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load private key from PEM", e);
        }
    }

    /**
     * Open an InputStream from filesystem path or classpath resource.
     * <p>
     * A {@code classpath:} prefix forces classpath loading; otherwise it first tries
     * the filesystem and falls back to the classpath resource when the file is absent.
     */
    private static InputStream openStream(String path) throws IOException {
        boolean isClasspath = path.startsWith("classpath:");
        if (!isClasspath) {
            File file = new File(path);
            if (file.isFile()) {
                return new FileInputStream(file);
            }
        }
        String resource = isClasspath ? path.substring("classpath:".length()) : path;
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new FileNotFoundException("PEM resource not found: " + path);
        }
        return in;
    }

    /**
     * Parse PEM stream directly (streaming, no full file load into memory).
     *
     * @param in input stream of PEM data
     * @return list of DER-encoded bytes for each PEM block
     * @throws IOException if read fails
     */
    private static List<byte[]> parsePEM(InputStream in) throws IOException {
        List<byte[]> blocks = new ArrayList<byte[]>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            StringBuilder base64 = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("-----BEGIN ")) {
                    base64 = new StringBuilder();
                } else if (line.startsWith("-----END ")) {
                    if (base64 != null) {
                        blocks.add(Base64.getMimeDecoder().decode(base64.toString()));
                        base64 = null;
                    }
                } else if (base64 != null) {
                    base64.append(line.trim());
                }
            }
        } finally {
            reader.close();
        }
        return blocks;
    }

}
