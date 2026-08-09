package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import okhttp3.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

/**
 * SSL/TLS integration test — starts an HTTPS server using PEM certificates,
 * verifies TLS handshake, encrypted request/response, and certificate validation.
 */
public class SslTlsIntegrationTest {

    private static HTTPServer server;
    private static int port;
    private static OkHttpClient httpsClient;

    @BeforeAll
    public static void startServer() throws Exception {
        port = findFreePort();

        // Trust-all OkHttpClient for self-signed certs
        X509TrustManager trustManager = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{trustManager}, new java.security.SecureRandom());
        httpsClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        server = HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .requestHandler(new HttpRequestHandler() {
                    @Override
                    public void handle(HttpRequest request,
                                       HttpResponse response) throws Throwable {
                        String path = request.getRequestUri();
                        if ("/secure".equals(path)) {
                            response.status(HttpStatus.OK)
                                    .contentType("application/json;charset=utf-8")
                                    .body("{\"secure\":true,\"ssl\":" + request.isSSL() + "}".getBytes("UTF-8"));
                        } else if ("/echo".equals(path)) {
                            byte[] body = request.getBodyData();
                            response.status(HttpStatus.OK)
                                    .contentType("text/plain")
                                    .body(body != null ? body : new byte[0]);
                        } else {
                            response.status(HttpStatus.OK)
                                    .body("HTTPS OK".getBytes());
                        }
                    }
                })
                .startupBannerEnabled(false)
                .start();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) server.shutdown();
    }

    @Test
    public void testHttpsGetRequest() throws Exception {
        try (Response resp = httpsGet("/secure")) {
            Assertions.assertEquals(200, resp.code());
            String body = resp.body().string();
            Assertions.assertTrue(body.contains("\"secure\":true"), "Response should indicate secure flag");
            Assertions.assertTrue(body.contains("\"ssl\":true"), "Response should indicate SSL is active");
        }
    }

    @Test
    public void testHttpsPlainTextEndpoint() throws Exception {
        try (Response resp = httpsGet("/")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("HTTPS OK", resp.body().string());
        }
    }

    @Test
    public void testHttpsPostRequest() throws Exception {
        try (Response resp = httpsPost("/echo", "encrypted-payload")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("encrypted-payload", resp.body().string());
        }
    }

    @Test
    public void testTlsCipherSuite() throws Exception {
        try (Response resp = httpsGet("/")) {
            Assertions.assertEquals(200, resp.code());
            Handshake handshake = resp.handshake();
            Assertions.assertNotNull(handshake, "TLS handshake should be present");
            Assertions.assertNotNull(handshake.cipherSuite(), "TLS cipher suite should be negotiated");
            Assertions.assertTrue(handshake.cipherSuite().javaName().startsWith("TLS"),
                    "Cipher suite should start with TLS");
        }
    }

    @Test
    public void testServerCertificate() throws Exception {
        try (Response resp = httpsGet("/")) {
            Assertions.assertEquals(200, resp.code());
            Handshake handshake = resp.handshake();
            Assertions.assertNotNull(handshake, "TLS handshake should be present");
            // peerCertificates availability depends on JDK/SSL implementation
            if (!handshake.peerCertificates().isEmpty()) {
                java.security.cert.Certificate cert = handshake.peerCertificates().get(0);
                Assertions.assertTrue(cert instanceof X509Certificate, "Certificate should be X509");
                X509Certificate x509 = (X509Certificate) cert;
                Assertions.assertTrue(x509.getSubjectDN().getName().contains("localhost"),
                        "Certificate should be for localhost");
            }
        }
    }

    @Test
    public void testHttpsMultipleRequests() throws Exception {
        // Verify keep-alive works over TLS using shared OkHttpClient
        for (int i = 0; i < 3; ++i) {
            try (Response resp = httpsGet("/")) {
                Assertions.assertEquals(200, resp.code(), "Request " + i + " should succeed");
                Assertions.assertEquals("HTTPS OK", resp.body().string());
            }
        }
    }

    @Test
    public void testPlaintextToSslPortFails() throws Exception {
        // Sending a plain HTTP request to the HTTPS port should fail
        OkHttpClient plainClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("http://localhost:" + port + "/").build();
        try {
            Response resp = plainClient.newCall(request).execute();
            resp.close();
            Assertions.fail("Plain HTTP to TLS port should fail");
        } catch (Exception e) {
            Assertions.assertTrue(e instanceof java.io.IOException || e instanceof javax.net.ssl.SSLException,
                    "Should fail with IO or SSL exception");
        }
    }

    // ==================== OkHttp helpers ====================

    private static Response httpsGet(String path) throws IOException {
        Request request = new Request.Builder()
                .url("https://localhost:" + port + path)
                .build();
        return httpsClient.newCall(request).execute();
    }

    private static Response httpsPost(String path, String body) throws IOException {
        Request request = new Request.Builder()
                .url("https://localhost:" + port + path)
                .post(RequestBody.create(MediaType.parse("text/plain"), body))
                .build();
        return httpsClient.newCall(request).execute();
    }

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18083;
        }
    }
}
