package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.socket.tcp.NioConfig;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AnnotationRouterHandlerAsyncTest {

    private static final String PKG = "io.github.wycst.wastnet.http.annotation.asyncfixture";

    @Test
    public void testAsyncEndpointReturnsBody() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            assertEquals("ok", bodyOf(client, "http://127.0.0.1:" + port, "/async/delay"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testSyncEndpointReturnsBody() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            assertEquals("hello", bodyOf(client, "http://127.0.0.1:" + port, "/sync/hello"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testAsyncEndpointOffloadInSyncMode() throws Exception {
        NioConfig config = new NioConfig().testMode();
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port, config).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            assertEquals("ok", bodyOf(client, "http://127.0.0.1:" + port, "/async/delay"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testAsyncEndpointThrowsReturns500() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            assertEquals(500, codeOf(client, "http://127.0.0.1:" + port, "/async/err"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testAsyncEndpointThrowsReturns500InSyncMode() throws Exception {
        NioConfig config = new NioConfig().testMode();
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port, config).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            assertEquals(500, codeOf(client, "http://127.0.0.1:" + port, "/async/err"));
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void testAsyncEndpointConcurrencyReleasesWorker() throws Exception {
        NioConfig config = new NioConfig().testMode();
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port, config).requestHandler(handler).startupBannerEnabled(false).start();
        try {
            OkHttpClient client = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            int n = 20;
            ExecutorService es = Executors.newFixedThreadPool(n);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(es.submit(() -> bodyOf(client, "http://127.0.0.1:" + port, "/async/delay")));
            }
            for (Future<String> f : futures) {
                assertEquals("ok", f.get(15, TimeUnit.SECONDS));
            }
            es.shutdown();
        } finally {
            server.shutdown();
        }
    }

    @EnabledForJreRange(min = JRE.JAVA_9)
    @Test
    public void testAsyncEndpointOverHttp2() throws Exception {
        AnnotationRouterHandler handler = new AnnotationRouterHandler().scanPackages(PKG);
        int port = findFreePort();
        HTTPServer server = HTTPServer.of(port)
                .pemSSL("cert/cert.pem", "cert/server.pem")
                .h2()
                .requestHandler(handler)
                .startupBannerEnabled(false)
                .start();
        try {
            SSLContext sslContext = createTrustAllSSLContext();
            X509TrustManager trustManager = createTrustAllTrustManager();
            OkHttpClient client = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();
            String base = "https://localhost:" + port;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                try (Response r = client.newCall(new Request.Builder().url(base + "/async/delay").get().build()).execute()) {
                    if (r.code() == 200) break;
                } catch (Exception ignored) {
                }
                Thread.sleep(50);
            }
            assertEquals("ok", bodyOf(client, base, "/async/delay"));
        } finally {
            server.shutdown();
        }
    }

    private static String bodyOf(OkHttpClient client, String base, String path) throws Exception {
        try (Response r = client.newCall(new Request.Builder().url(base + path).get().build()).execute()) {
            assertEquals(200, r.code(), "unexpected status for " + path);
            return r.body().string();
        }
    }

    private static int codeOf(OkHttpClient client, String base, String path) throws Exception {
        try (Response r = client.newCall(new Request.Builder().url(base + path).get().build()).execute()) {
            return r.code();
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static SSLContext createTrustAllSSLContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{createTrustAllTrustManager()}, new java.security.SecureRandom());
        return ctx;
    }

    private static X509TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
