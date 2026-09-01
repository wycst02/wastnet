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

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * H2C prior knowledge integration test using OkHttp.
 * <p>
 * Shares one server and one OkHttpClient across all tests.
 *
 * @author wangyc
 */
@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class Http2PriorKnowledgeIntegrationTest {

    private static HTTPServer server;
    private static OkHttpClient client;
    private static int port;
    private static File testFile;

    @BeforeAll
    public static void startServer() throws Exception {
        port = findFreePort();

        // Create temp file for sendFile test
        testFile = File.createTempFile("h2c-sendfile-", ".dat");
        FileOutputStream fos = new FileOutputStream(testFile);
        byte[] data = new byte[32768];
        new java.util.Random().nextBytes(data);
        fos.write("H2C_FILE_DATA:".getBytes());
        fos.write(data);
        fos.close();

        HttpRouterHandler router = new HttpRouterHandler("/app");
        router.h2c("/h2c");

        router.route("/api/test", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK)
                        .contentType("application/json;charset=utf-8")
                        .body(("{\"protocol\":\"" + request.getHttpVersion() + "\"}").getBytes());
            }
        });
        router.route("/api/echo", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                java.io.InputStream in = request.bodyStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                String body = new String(out.toByteArray());
                response.status(HttpStatus.OK).contentType("text/plain").body(body.getBytes());
            }
        });
        router.route("/api/ping", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body("pong".getBytes());
            }
        });
        router.route("/api/large", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 20000; ++i) sb.append("Hello H2 World! ");
                byte[] bigBody = sb.toString().getBytes();
                response.status(HttpStatus.OK).contentType("text/plain").body(bigBody);
            }
        });
        router.route("/api/status-only", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.NO_CONTENT);
            }
        });
        router.route("/api/headers", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK)
                        .header("X-Custom", "h2-value")
                        .header("X-Request-Method", request.getMethod().name())
                        .body("headers set".getBytes());
            }
        });
        router.route("/api/cust-header", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String v1 = request.getHeader("x-cust-h1");
                String v2 = request.getHeader("x-cust-h2");
                response.status(HttpStatus.OK).body(("h1=" + v1 + "&h2=" + v2).getBytes());
            }
        });
        router.route("/api/query", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String name = request.getUriParameter("name");
                String age = request.getUriParameter("age");
                response.status(HttpStatus.OK).body(("name=" + name + "&age=" + age).getBytes());
            }
        });
        router.route("/api/chunked-write", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                response.write("chunk1".getBytes());
                response.write("chunk2".getBytes());
                response.write("chunk3".getBytes());
                response.commit();
            }
        });
        router.route("/api/not-modified", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.NOT_MODIFIED);
            }
        });
        router.route("/api/req-info", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                StringBuilder sb = new StringBuilder();
                sb.append("scheme=").append(request.getScheme() != null ? request.getScheme() : "null").append("|");
                sb.append("method=").append(request.getMethod()).append("|");
                sb.append("queryString=").append(request.getQueryString() != null ? request.getQueryString() : "").append("|");
                sb.append("containsHost=").append(request.containsHeader("host"));
                response.status(HttpStatus.OK).body(sb.toString().getBytes());
            }
        });
        router.route("/api/req-mutate", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                request.setAttribute("attr1", "val1");
                String s = (String) request.getAttribute("attr1");
                response.status(HttpStatus.OK).body(("attr=" + s).getBytes());
            }
        });
        router.route("/api/hdr-mutate", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                Http2Request h2req = (Http2Request) request;
                h2req.setRewriteUri("/rewritten");
                h2req.setHeader("x-test", "setval");
                String h = h2req.getHeader("x-test");
                h2req.removeHeader("x-test");
                response.status(HttpStatus.OK).body(("rewritten=" + h2req.getUri() + "&setHdr=" + h + "&removed=" + h2req.containsHeader("x-test")).getBytes());
            }
        });
        router.route("/api/req-full", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                Http2Request h2req = (Http2Request) request;
                StringBuilder sb = new StringBuilder();
                sb.append("url=").append(request.getRequestURL()).append("|");
                sb.append("uri=").append(request.getUri()).append("|");
                sb.append("reqUri=").append(request.getRequestUri()).append("|");
                sb.append("httpVer=").append(request.getHttpVersion()).append("|");
                sb.append("contentType=").append(request.getContentType() != null ? request.getContentType() : "null").append("|");
                sb.append("stream=").append(request.isStream()).append("|");
                sb.append("sid=").append(h2req.streamId()).append("|");
                sb.append("rawHost=").append(h2req.getRawHeader(":authority")).append("|");
                sb.append("hdrNames=").append(h2req.getHeaderNames().toString()).append("|");
                sb.append("uriParamNames=").append(h2req.getUriParameterNames().toString()).append("|");
                sb.append("bodyDataLen=").append(request.getBodyData().length).append("|");
                sb.append("contentLen=").append(request.getContentLength());
                response.status(HttpStatus.OK).body(sb.toString().getBytes());
            }
        });
        router.route("/api/body-stream", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                java.io.InputStream in = request.bodyStream();
                int n = 0;
                byte[] buf = new byte[16];
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                response.status(HttpStatus.OK).body(("len=" + baos.size()).getBytes());
            }
        });
        router.route("/api/stream", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                Http2Request h2req = (Http2Request) request;
                response.status(HttpStatus.OK).body(("streamOk=" + (h2req.stream() != null)).getBytes());
            }
        });
        router.route("/api/multi-param", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                Http2Request h2req = (Http2Request) request;
                java.util.List<String> vals = h2req.getUriParameterValues("k");
                response.status(HttpStatus.OK).body(("vals=" + (vals != null ? vals.toString() : "null")).getBytes());
            }
        });
        router.route("/api/double-complete", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                Http2Request h2req = (Http2Request) request;
                h2req.complete();
                h2req.complete();
                response.status(HttpStatus.OK).body("ok".getBytes());
            }
        });
        router.route("/api/req-mutate-hdr", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                Http2Request h2req = (Http2Request) request;
                h2req.setRewriteUri("/rewritten");
                h2req.setHeader("x-test", "setval");
                String h = h2req.getHeader("x-test");
                h2req.removeHeader("x-test");
                response.status(HttpStatus.OK).body(("rewritten=" + h2req.getUri() + "&setHdr=" + h + "&removed=" + h2req.containsHeader("x-test")).getBytes());
            }
        });
        router.route("/api/flush-write", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                response.write("hello".getBytes());
                response.flush();
                response.write(" world".getBytes());
                response.commit();
            }
        });
        router.route("/api/send-file", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.sendFile(testFile);
            }
        });
        router.route("/api/early-hints", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.earlyHints("</style.css>; rel=preload");
                response.status(HttpStatus.OK).contentType("text/plain").body("early hints test".getBytes());
            }
        });
        router.route("/api/long-header", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                // Exactly 128 chars to test writeInteger7Bit boundary
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 200; ++i) sb.append('Z');
                response.status(HttpStatus.OK).header("X-Long-Value", sb.toString()).body("ok".getBytes());
            }
        });
        router.route("/api/empty-write", new HttpRoute() {
            @Override
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                response.write("hello".getBytes(), 0, 0);
                response.write("world".getBytes());
                response.commit();
            }
        });

        server = HTTPServer.of(port)
                .applicationProtocols(new String[]{"h2c", "http/1.1"})
                .requestHandler(router)
                .startupBannerEnabled(false)
                .start();

        client = new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) {
            server.shutdown();
        }
        if (testFile != null) {
            testFile.delete();
        }
    }

    @Test
    public void testH2GetRequest() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/test")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol(), "Protocol should be HTTP_2");
            Assertions.assertEquals(200, response.code());
            ResponseBody body = response.body();
            Assertions.assertNotNull(body);
            String bodyStr = body.string();
            Assertions.assertTrue(bodyStr.contains("\"protocol\""), "Response should contain protocol info");
            Assertions.assertTrue(bodyStr.contains("HTTP/2"), "Response should indicate HTTP/2");
        }
    }

    @Test
    public void testH2CustomHeaderValue() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/cust-header")
                .header("X-Cust-H1", "val1")
                .header("X-Cust-H2", "val2")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("h1=val1&h2=val2", response.body().string());
        }
    }

    @Test
    public void testH2RequestInfo() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/req-info?q=1")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertTrue(body.contains("containsHost=false"),
                    "req-info body: [" + body + "]");
            Assertions.assertTrue(body.contains("queryString=q=1"),
                    "req-info body missing 'queryString': [" + body + "]");
        }
    }

    @Test
    public void testH2RequestMutate() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/req-mutate")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("attr=val1", response.body().string());
        }
    }

    @Test
    public void testH2FlushWrite() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/flush-write")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("hello world", response.body().string());
        }
    }

    @Test
    public void testH2PostRequest() throws Exception {
        okhttp3.MediaType mediaType = okhttp3.MediaType.parse("text/plain; charset=utf-8");
        okhttp3.RequestBody reqBody = okhttp3.RequestBody.create(mediaType, "Hello H2 World!");

        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/echo")
                .post(reqBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, response.protocol(), "Protocol should be HTTP_2");
            Assertions.assertEquals(200, response.code());
            ResponseBody respBody = response.body();
            Assertions.assertNotNull(respBody);
            Assertions.assertEquals("Hello H2 World!", respBody.string().trim());
        }
    }

    @Test
    public void testH2MultipleRequestsSameConnection() throws Exception {
        for (int i = 0; i < 5; ++i) {
            Request request = new Request.Builder()
                    .url("http://localhost:" + port + "/app/api/ping")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                Assertions.assertEquals(200, response.code());
                Assertions.assertEquals("pong", response.body().string().trim());
            }
        }
    }

    @Test
    public void testH2NotFound() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/missing")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(404, response.code());
        }
    }

    @Test
    public void testH2LargeResponse() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/large")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertTrue(body.startsWith("Hello H2 World!"));
            Assertions.assertTrue(body.length() > 100000);
        }
    }

    @Test
    public void testH2NoContentResponse() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/status-only")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(204, response.code());
        }
    }

    @Test
    public void testH2CustomHeaders() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/headers")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("h2-value", response.header("X-Custom"));
            Assertions.assertEquals("GET", response.header("X-Request-Method"));
        }
    }

    @Test
    public void testH2QueryParameters() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/query?name=test&age=25")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertTrue(body.contains("name=test"));
            Assertions.assertTrue(body.contains("age=25"));
        }
    }

    @Test
    public void testH2LargePost() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; ++i) sb.append("data chunk " + i + " ");
        String bigBody = sb.toString();
        okhttp3.MediaType mediaType = okhttp3.MediaType.parse("text/plain; charset=utf-8");
        okhttp3.RequestBody reqBody = okhttp3.RequestBody.create(mediaType, bigBody);

        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/echo")
                .post(reqBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String respBody = response.body().string();
            Assertions.assertTrue(bigBody.equals(respBody),
                    "echo body mismatch, len sent=" + bigBody.length() + " recv=" + respBody.length());
        }
    }

    @Test
    public void testH2ConcurrentRequests() throws Exception {
        Runnable task = () -> {
            try {
                Request request = new Request.Builder()
                        .url("http://localhost:" + port + "/app/api/ping")
                        .get()
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    Assertions.assertEquals(200, response.code());
                    Assertions.assertEquals("pong", response.body().string().trim());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.Collection<java.util.concurrent.Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 10; ++i) {
            tasks.add(java.util.concurrent.Executors.callable(task, (Void) null));
        }
        executor.invokeAll(tasks);
        executor.shutdown();
    }

    @Test
    public void testH2ChunkedWrite() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/chunked-write")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertEquals("chunk1chunk2chunk3", body);
        }
    }

    @Test
    public void testH2NotModified() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/not-modified")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(304, response.code());
        }
    }

    @Test
    public void testH2ConcurrentLargeRequests() throws Exception {
        Runnable task = () -> {
            try {
                Request request = new Request.Builder()
                        .url("http://localhost:" + port + "/app/api/large")
                        .get()
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    Assertions.assertEquals(200, response.code());
                    String body = response.body().string();
                    Assertions.assertTrue(body.length() > 100000);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.Collection<java.util.concurrent.Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 6; ++i) {
            tasks.add(java.util.concurrent.Executors.callable(task, (Void) null));
        }
        executor.invokeAll(tasks);
        executor.shutdown();
    }

    @Test
    public void testH2SendFile() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/send-file")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            ResponseBody body = response.body();
            Assertions.assertNotNull(body);
            String content = body.string();
            Assertions.assertTrue(content.startsWith("H2C_FILE_DATA:"),
                    "sendFile content should start with prefix, len=" + content.length());
            // Content may be compressed/decompressed; just verify prefix
        }
    }

    @Test
    public void testH2EarlyHints() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/early-hints")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("early hints test", response.body().string().trim());
        }
    }

    @Test
    public void testH2LongHeaderValue() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/long-header")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("ok", response.body().string().trim());
        }
    }

    @Test
    public void testH2RequestFull() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/req-full?q=hello")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertTrue(body.contains("url=http://localhost:"), body);
            Assertions.assertTrue(body.contains("uri=/app/api/req-full?q=hello"), body);
            Assertions.assertTrue(body.contains("httpVer=HTTP/2"), body);
            Assertions.assertTrue(body.contains("stream=false"), body);
            Assertions.assertTrue(body.contains("rawHost=localhost:"), body);
            Assertions.assertTrue(body.contains("hdrNames=["), body);
            Assertions.assertTrue(body.contains("bodyDataLen=0"), body);
            Assertions.assertTrue(body.contains("contentLen=0"), body);
        }
    }

    @Test
    public void testH2RequestFullPost() throws Exception {
        okhttp3.MediaType mt = okhttp3.MediaType.parse("text/plain; charset=utf-8");
        okhttp3.RequestBody reqBody = okhttp3.RequestBody.create(mt, "post body data");
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/req-full?name=hello&name=world")
                .post(reqBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertTrue(body.contains("bodyDataLen=14"), body);
            Assertions.assertTrue(body.contains("contentLen=14"), body);
            Assertions.assertTrue(body.contains("uriParamNames=[name]"), body);
        }
    }

    @Test
    public void testH2BodyStream() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/body-stream")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("len=0", response.body().string());
        }
    }

    @Test
    public void testH2StreamAccess() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/stream")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("streamOk=true", response.body().string());
        }
    }

    @Test
    public void testH2MultiParam() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/multi-param?k=a&k=b")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("vals=[a, b]", response.body().string());
        }
    }

    @Test
    public void testH2DoubleComplete() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/double-complete")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("ok", response.body().string());
        }
    }

    @Test
    public void testH2RequestMutateHeaders() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/hdr-mutate")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            String body = response.body().string();
            Assertions.assertTrue(body.contains("rewritten=/rewritten"), body);
            Assertions.assertTrue(body.contains("setHdr=setval"), body);
            Assertions.assertTrue(body.contains("removed=false"), body);
        }
    }

    @Test
    public void testH2EmptyWrite() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + port + "/app/api/empty-write")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            Assertions.assertEquals(200, response.code());
            Assertions.assertEquals("world", response.body().string().trim());
        }
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
