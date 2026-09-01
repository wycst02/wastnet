package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpStatus;
import okhttp3.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/**
 * Embedded integration test — starts one real HTTP server, sends multiple real HTTP requests.
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class HttpServerIntegrationTest {

    private static HTTPServer server;
    private static int port;
    private static OkHttpClient httpClient;

    @BeforeAll
    public static void startServer() {
        port = findFreePort();

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        server = HTTPServer.of(port)
                .requestHandler((request, response) -> {
                    String path = request.getRequestUri();
                    String method = request.getMethod().name();
                    String agent = request.getHeader("User-Agent");
                    String name = request.getUriParameter("name");
                    byte[] body = request.getBodyData();

                    if ("/test".equals(path) && "GET".equals(method)) {
                        response.status(HttpStatus.OK)
                                .contentType("text/plain;charset=utf-8")
                                .body("Hello World".getBytes());
                    } else if ("/post".equals(path) && "POST".equals(method)) {
                        response.status(HttpStatus.OK).contentType("text/plain")
                                .body(body);
                    } else if ("/missing".equals(path)) {
                        response.status(HttpStatus.NOT_FOUND).body("Not Found".getBytes());
                    } else if ("/header".equals(path)) {
                        response.status(HttpStatus.OK)
                                .body(("agent=" + agent).getBytes());
                    } else if ("/greet".equals(path)) {
                        response.status(HttpStatus.OK)
                                .body(("hello " + name).getBytes());
                    } else if ("/data".equals(path)) {
                        response.status(HttpStatus.OK)
                                .contentType("application/json")
                                .body("{\"key\":\"value\"}".getBytes());
                    } else {
                        response.status(HttpStatus.OK).body("ok".getBytes());
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
    public void testGetRequest() throws Exception {
        try (Response resp = get("/test")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("Hello World", resp.body().string());
        }
    }

    @Test
    public void testPostRequest() throws Exception {
        try (Response resp = post("/post", "Hello Server")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("Hello Server", resp.body().string());
        }
    }

    @Test
    public void testNotFound() throws Exception {
        try (Response resp = get("/missing")) {
            Assertions.assertEquals(404, resp.code());
        }
    }

    @Test
    public void testCustomHeader() throws Exception {
        try (Response resp = get("/header", "User-Agent", "IntegrationTest/1.0")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("agent=IntegrationTest/1.0", resp.body().string());
        }
    }

    @Test
    public void testQueryParameter() throws Exception {
        try (Response resp = get("/greet?name=world")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertEquals("hello world", resp.body().string());
        }
    }

    @Test
    public void testContentTypeResponse() throws Exception {
        try (Response resp = get("/data")) {
            Assertions.assertEquals(200, resp.code());
            Assertions.assertTrue(resp.header("Content-Type", "").contains("application/json"));
        }
    }

    @Test
    public void testMultipleRequests() throws Exception {
        for (int i = 0; i < 3; ++i) {
            try (Response resp = get("/" + i)) {
                Assertions.assertEquals(200, resp.code());
            }
        }
    }

    // ==================== OkHttp helpers ====================

    private static Response get(String path, String... headers) throws Exception {
        Request.Builder rb = new Request.Builder().url("http://localhost:" + port + path);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            rb.addHeader(headers[i], headers[i + 1]);
        }
        return httpClient.newCall(rb.build()).execute();
    }

    private static Response post(String path, String body) throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + path)
                .post(RequestBody.create(MediaType.parse("text/plain"), body))
                .build();
        return httpClient.newCall(request).execute();
    }

    private static int findFreePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (Exception e) {
            return 18080;
        }
    }
}
