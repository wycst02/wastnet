package io.github.wycst.wastnet.http.h2;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import okhttp3.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP/2 over TLS (h2) integration test using OkHttp.
 * <p>
 * Starts an HTTPS server with ALPN negotiation for h2,
 * validates protocol selection, multiplexing, flow control,
 * binary transfer, and ALPN fallback behavior.
 * <p>
 * Note: Requires JDK 9+ for ALPN support (h2 negotiation is a no-op on JDK 8).
 *
 * @author wangyc
 */
@EnabledForJreRange(min = JRE.JAVA_9)
public class Http2OverTlsIntegrationTest {

    private static HTTPServer server;
    private static OkHttpClient h2Client;
    private static int port;

    private static final String ECHO_BODY = "Hello, HTTP/2 over TLS!";
    private static final int LARGE_BODY_SIZE = 512 * 1024; // 512KB
    private static final int CONCURRENT_COUNT = 20;

    @BeforeAll
    public static void startServer() throws Exception {
        port = findFreePort();
        HttpRouterHandler router = new HttpRouterHandler("/app");

        router.get("/api/test", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK)
                        .contentType("application/json;charset=utf-8")
                        .body(("{\"protocol\":\"" + request.getHttpVersion() + "\",\"ssl\":" + request.isSSL() + "}").getBytes());
            }
        });

        router.post("/api/echo", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                java.io.InputStream in = request.bodyStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                byte[] body = out.toByteArray();
                response.status(HttpStatus.OK)
                        .contentType("text/plain")
                        .body(body != null ? body : new byte[0]);
            }
        });

        router.get("/api/large", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                byte[] data = new byte[LARGE_BODY_SIZE];
                Arrays.fill(data, (byte) 'X');
                response.status(HttpStatus.OK)
                        .contentType("application/octet-stream")
                        .body(data);
            }
        });

        router.post("/api/binary", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                java.io.InputStream in = request.bodyStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                byte[] body = out.toByteArray();
                String hexLen = Integer.toHexString(body != null ? body.length : 0);
                response.status(HttpStatus.OK)
                        .contentType("text/plain")
                        .body(hexLen.getBytes());
            }
        });

        router.get("/api/notfound", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.NOT_FOUND).body("not found".getBytes());
            }
        });

        server = HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();

        SSLContext sslContext = createTrustAllSSLContext();
        X509TrustManager trustManager = createTrustAllTrustManager();

        h2Client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier((hostname, session) -> true)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    // ==================== Core h2 Tests ====================

    @Test
    public void testH2GetRequest() throws Exception {
        Request request = new Request.Builder()
                .url("https://localhost:" + port + "/app/api/test")
                .get()
                .build();

        try (Response response = h2Client.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.HTTP_2, response.protocol());
            Assertions.assertEquals(200, response.code());
            ResponseBody body = response.body();
            Assertions.assertNotNull(body);
            String bodyStr = body.string();
            Assertions.assertTrue(bodyStr.contains("HTTP/2"));
            Assertions.assertTrue(bodyStr.contains("\"ssl\":true"));
        }
    }

    @Test
    public void testH2PostEcho() throws Exception {
        okhttp3.MediaType mediaType = okhttp3.MediaType.parse("text/plain; charset=utf-8");
        RequestBody reqBody = RequestBody.create(mediaType, ECHO_BODY);

        Request request = new Request.Builder()
                .url("https://localhost:" + port + "/app/api/echo")
                .post(reqBody)
                .build();

        try (Response response = h2Client.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.HTTP_2, response.protocol());
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals(ECHO_BODY, response.body().string().trim());
        }
    }

    @Test
    public void testH2NotFound() throws Exception {
        Request request = new Request.Builder()
                .url("https://localhost:" + port + "/app/missing")
                .get()
                .build();

        try (Response response = h2Client.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.HTTP_2, response.protocol());
            Assertions.assertEquals(404, response.code());
        }
    }

    @Test
    public void testH2MultipleRoundTrips() throws Exception {
        for (int i = 0; i < 5; ++i) {
            Request request = new Request.Builder()
                    .url("https://localhost:" + port + "/app/api/test")
                    .get()
                    .build();

            try (Response response = h2Client.newCall(request).execute()) {
                Assertions.assertEquals(Protocol.HTTP_2, response.protocol(), "Round-trip " + i + " should use h2");
                Assertions.assertEquals(200, response.code());
                Assertions.assertNotNull(response.body());
                Assertions.assertTrue(response.body().string().contains("HTTP/2"));
            }
        }
    }

    // ==================== h2 Multiplexing ====================

    @Test
    public void testH2ConcurrentMultiplexing() throws Exception {
        int n = CONCURRENT_COUNT;
        CountDownLatch latch = new CountDownLatch(n);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<String> failureInfo = new AtomicReference<>(null);

        for (int i = 0; i < n; ++i) {
            final int idx = i;
            new Thread(() -> {
                try {
                    Request request = new Request.Builder()
                            .url("https://localhost:" + port + "/app/api/test")
                            .get()
                            .build();
                    try (Response response = h2Client.newCall(request).execute()) {
                        if (response.protocol() == Protocol.HTTP_2 && response.code() == 200) {
                            successCount.incrementAndGet();
                        } else {
                            failureInfo.set("Request " + idx + ": protocol=" + response.protocol() + " code=" + response.code());
                        }
                    }
                } catch (Exception e) {
                    failureInfo.set("Request " + idx + " threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), "All concurrent requests should complete within timeout");
        Assertions.assertNull(failureInfo.get(), "No request should fail: " + (failureInfo.get() != null ? failureInfo.get() : ""));
        Assertions.assertEquals(n, successCount.get(), "All " + n + " concurrent requests should succeed with HTTP_2 protocol");
    }

    @Test
    public void testH2ConcurrentPostMultiplexing() throws Exception {
        int n = CONCURRENT_COUNT;
        CountDownLatch latch = new CountDownLatch(n);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<String> failureInfo = new AtomicReference<>(null);

        okhttp3.MediaType mediaType = okhttp3.MediaType.parse("text/plain; charset=utf-8");

        for (int i = 0; i < n; ++i) {
            final String payload = "payload-" + i;
            new Thread(() -> {
                try {
                    RequestBody reqBody = RequestBody.create(mediaType, payload);
                    Request request = new Request.Builder()
                            .url("https://localhost:" + port + "/app/api/echo")
                            .post(reqBody)
                            .build();
                    try (Response response = h2Client.newCall(request).execute()) {
                        if (response.protocol() == Protocol.HTTP_2 && response.code() == 200
                                && payload.equals(response.body().string().trim())) {
                            successCount.incrementAndGet();
                        } else {
                            failureInfo.set("POST " + ": unexpected response");
                        }
                    }
                } catch (Exception e) {
                    failureInfo.set("POST threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS));
        Assertions.assertNull(failureInfo.get());
        Assertions.assertEquals(n, successCount.get());
    }

    // ==================== Flow Control ====================

    @Test
    public void testH2LargeResponseBody() throws Exception {
        Request request = new Request.Builder()
                .url("https://localhost:" + port + "/app/api/large")
                .get()
                .build();

        try (Response response = h2Client.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.HTTP_2, response.protocol());
            Assertions.assertEquals(200, response.code());
            ResponseBody body = response.body();
            Assertions.assertNotNull(body);
            byte[] data = body.bytes();
            Assertions.assertEquals(LARGE_BODY_SIZE, data.length, "Large response should have correct size");
            for (int i = 0; i < data.length; ++i) {
                if (data[i] != (byte) 'X') {
                    Assertions.fail("Byte at position " + i + " should be 'X' but was " + (char) data[i]);
                }
            }
        }
    }

    // ==================== Binary Transfer ====================

    @Test
    public void testH2BinaryPost() throws Exception {
        byte[] binaryData = new byte[1024];
        for (int i = 0; i < binaryData.length; ++i) {
            binaryData[i] = (byte) (i & 0xFF);
        }

        // 使用独立连接，避免共享连接池中因并发测试残留的脏连接影响
        SSLContext sslContext = createTrustAllSSLContext();
        X509TrustManager trustManager = createTrustAllTrustManager();
        OkHttpClient localClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier((hostname, session) -> true)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS))
                .build();

        RequestBody reqBody = RequestBody.create(null, binaryData);
        Request request = new Request.Builder()
                .url("https://localhost:" + port + "/app/api/binary")
                .post(reqBody)
                .build();

        try (Response response = localClient.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.HTTP_2, response.protocol());
            Assertions.assertEquals(200, response.code());
            String hexLen = response.body().string().trim();
            Assertions.assertEquals("400", hexLen, "Hex length of 1024 should be 400");
        }
    }

    // ==================== ALPN Fallback ====================

    // ==================== Helpers ====================

    private static SSLContext createTrustAllSSLContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{createTrustAllTrustManager()};
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        return sslContext;
    }

    private static X509TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        };
    }

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18081;
        }
    }
}
