package io.github.wycst.wastnet.http.integration;

import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpOptions;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRoute;
import io.github.wycst.wastnet.http.handler.HttpRouterHandler;
import okhttp3.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.ServerSocket;
import java.util.zip.GZIPInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.DisabledOnJre(org.junit.jupiter.api.condition.JRE.JAVA_8)
public class HttpIntegrationTest {

    static {
        // Enable GZIP before HttpConf loads
        System.setProperty("wastnet.http.gzip", "true");
    }

    private static HTTPServer server;
    private static int port;
    private static File testFile;
    private static File htmlFile;
    private static File largeHtmlFile;
    private static File smallFile;
    private static OkHttpClient httpClient;
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");
    private static final String BOUNDARY = "----TestBoundary12345";

    @BeforeAll
    public static void startServer() throws Exception {
        port = findFreePort();

        testFile = File.createTempFile("http-sendfile-", ".dat");
        try (FileOutputStream fos = new FileOutputStream(testFile)) {
            for (int i = 0; i < 5000; ++i) fos.write("HTTP_TEST_DATA\n".getBytes());
        }
        // .html file for gzip (compressible MIME type)
        htmlFile = File.createTempFile("http-sendfile-", ".html");
        try (FileOutputStream fos = new FileOutputStream(htmlFile)) {
            for (int i = 0; i < 5000; ++i) fos.write("<p>HTML content for gzip testing</p>\n".getBytes());
        }
        // Small file (< bufferSize 1024) for sendFileBuffered path
        smallFile = File.createTempFile("http-small-", ".txt");
        try (FileOutputStream fos = new FileOutputStream(smallFile)) {
            fos.write("small content".getBytes());
        }
        // Large html file (> default BODY_MEMORY_THRESHOLD 512KB) for compressAndSendFile
        // streaming-gzip branch (fileSize > bodyMemoryThreshold).
        largeHtmlFile = File.createTempFile("http-sendfile-large-", ".html");
        try (FileOutputStream fos = new FileOutputStream(largeHtmlFile)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 600; ++i) sb.append("<p>large html content for streaming gzip testing</p>\n");
            // repeat to exceed 512KB
            byte[] line = sb.toString().getBytes();
            for (int i = 0; i < 1100; ++i) fos.write(line);
        }

        // 共享 OkHttpClient（自动复用连接）
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        HttpRouterHandler router = new HttpRouterHandler("/app");

        // Basic status
        router.get("/status", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body("OK".getBytes());
            }
        });

        // Parameters
        router.get("/params", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String name = request.getParameter("name");
                java.util.List<String> vals = request.getParameterValues("tag");
                String body = "name=" + (name != null ? name : "null") + "|tags=" + (vals != null ? String.join(",", vals) : "");
                response.status(HttpStatus.OK).body(body.getBytes());
            }
        });

        // Headers: multi-value response headers
        router.get("/multi-header", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("X-Multi", "val1");
                response.addHeader("X-Multi", "val2");
                response.header("X-Single", "single");
                response.body("ok".getBytes());
            }
        });

        // Remote info
        router.get("/remote-info", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String body = "remoteHost=" + request.getRemoteHost() + "|remotePort=" + request.getRemotePort()
                        + "|serverHost=" + request.getServerHost() + "|serverPort=" + request.getServerPort()
                        + "|requestId=" + request.getRequestId() + "|charset=" + request.getCharset()
                        + "|upgrade=" + request.isUpgrade();
                response.status(HttpStatus.OK).body(body.getBytes());
            }
        });

        // Attributes
        router.get("/attr", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                request.setAttribute("k1", "v1");
                response.status(HttpStatus.OK).body(("k1=" + request.getAttribute("k1")).getBytes());
            }
        });

        // sendFile
        router.get("/send-file", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(testFile);
            }
        });

        // earlyHints
        router.get("/early-hints", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.earlyHints("</style.css>; rel=preload");
                response.status(HttpStatus.OK).body("early".getBytes());
            }
        });

        // POST echo
        router.post("/echo", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body(request.getBodyData());
            }
        });

        // form-urlencoded
        router.post("/form", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String val = request.getParameter("field1");
                response.status(HttpStatus.OK).body(("field1=" + val).getBytes());
            }
        });

        // Large form-urlencoded (for HttpBodyStreamDecoder)
        router.post("/large-form", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                String val = request.getParameter("big");
                response.status(HttpStatus.OK).body(("len=" + (val != null ? val.length() : 0)).getBytes());
            }
        });

        // Chunked write
        router.get("/chunked", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                byte[] data = "chunked".getBytes();
                response.write(data);
                response.write(data);
                response.commit();
            }
        });

        // Remove header
        router.get("/remove-header", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.header("X-Remove", "toremove");
                response.removeHeader("X-Remove");
                response.body("ok".getBytes());
            }
        });

        // SSE endpoint
        router.get("/sse", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.sse("sse data");
            }
        });

        // SSE with event
        router.get("/sse-event", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.sse("update", "event data");
            }
        });

        // setLastModified
        router.get("/last-modified", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).header("Last-Modified", "Thu, 01 Jan 2025 00:00:00 GMT").body("ok".getBytes());
            }
        });

        // Large response write (triggers flush path)
        router.get("/large-write", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("application/octet-stream");
                byte[] big = new byte[65536];
                new java.util.Random().nextBytes(big);
                response.write(big);
                response.commit();
            }
        });

        // Connection: close
        router.get("/keep-alive", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body("ok".getBytes());
            }
        });

        // 404
        router.get("/not-found", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.NOT_FOUND).write("404".getBytes());
            }
        });

        // Version
        router.get("/version", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).body(request.getHttpVersion().toString().getBytes());
            }
        });

        // addHeader with 3+ values (list path)
        router.get("/multi3", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("X-List", "a");
                response.addHeader("X-List", "b");
                response.addHeader("X-List", "c");
                response.body("ok".getBytes());
            }
        });

        // sendFile with Last-Modified
        router.get("/last-mod-file", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(testFile);
            }
        });

        // sendFile gzip (html file = compressible MIME type)
        router.get("/send-file-gzip", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(htmlFile);
            }
        });

        // sendFile gzip large (file > BODY_MEMORY_THRESHOLD -> streaming gzip branch in compressAndSendFile)
        router.get("/send-file-gzip-large", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(largeHtmlFile);
            }
        });

        // auto-gzip: plain body >= GZIP_MIN_SIZE with client Accept-Encoding gzip triggers attemptAutoGzipAndSend true branch
        router.get("/auto-gzip", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 200; ++i) sb.append("auto gzip plain body content line\n");
                // length > 2048 (GZIP_MIN_SIZE default)
                response.status(HttpStatus.OK).body(sb.toString().getBytes());
            }
        });

        // sendFile small file (triggers sendFileBuffered, file < bufferSize)
        router.get("/send-file-small", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(smallFile);
            }
        });

        // getHeader in handler
        router.get("/get-header", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).header("X-Echo", "echoVal").body(("got=" + response.getHeader("X-Echo")).getBytes());
            }
        });

        // removeHeader(key, value)
        router.get("/remove-val", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("X-Remove", "keep");
                response.addHeader("X-Remove", "remove");
                response.removeHeader("X-Remove", "remove");
                response.body(("val=" + response.getHeader("X-Remove")).getBytes());
            }
        });

        // Large write (flush triggers direct channel write)
        router.get("/large-write-flush", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).contentType("text/plain");
                byte[] data = "hello".getBytes();
                response.write(data);
                response.flush();
                response.write(data);
                response.commit();
            }
        });

        // Non-existent file for 404 in sendFile path
        router.get("/send-nonexist", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(new File("/nonexistent/test.dat"));
            }
        });

        // Large file for sendFileZeroCopy
        router.get("/send-large-file", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK).sendFile(testFile);
            }
        });

        // setContentLength explicitly
        router.get("/content-length", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                response.status(HttpStatus.OK);
                response.addHeader("Content-Length", "2");
                response.body("ok".getBytes());
            }
        });

        // Multipart upload
        router.post("/upload", new HttpRoute() {
            public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
                StringBuilder sb = new StringBuilder();
                for (String n : request.getMultipartFieldNames()) {
                    sb.append(n).append("=").append(request.getMultipartFieldValue(n)).append(",");
                }
                response.status(HttpStatus.OK).body(sb.toString().getBytes());
            }
        });

        server = new HTTPServer(port);
        server.requestHandler(router).bufferSize(1024).option(HttpOptions.GZIP, true).startupBannerEnabled(false).start();
    }

    // ==================== Tests ====================

    @Test
    public void testServerConfig() throws Exception {
        HTTPServer s2 = new HTTPServer(findFreePort());
        s2.workerNum(4).bufferSize(8192).localOnly(true).printSSLErrorLog(true).printReadErrorLog(true).startupBannerEnabled(false);
        assertNotNull(s2);
        s2.shutdown();
    }

    @Test
    public void testGetStatus() throws Exception {
        try (Response resp = get("/app/status")) {
            assertEquals(200, resp.code());
            assertEquals("OK", resp.body().string().trim());
        }
    }

    @Test
    public void testParameters() throws Exception {
        try (Response resp = get("/app/params?name=hello&tag=a&tag=b")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("name=hello"), body);
            assertTrue(body.contains("tags=a,b"), body);
        }
    }

    @Test
    public void testRemoteInfo() throws Exception {
        try (Response resp = get("/app/remote-info")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("remoteHost="), body);
            assertTrue(body.contains("remotePort="), body);
            assertTrue(body.contains("serverPort=" + port), body);
            assertTrue(body.contains("requestId="), body);
        }
    }

    @Test
    public void testAttributes() throws Exception {
        try (Response resp = get("/app/attr")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("k1=v1"), body);
        }
    }

    @Test
    public void testSendFile() throws Exception {
        try (Response resp = get("/app/send-file")) {
            assertEquals(200, resp.code());
            byte[] body = resp.body().bytes();
            assertTrue(body.length > 1000, "sendFile body too small: " + body.length);
        }
    }

    @Test
    public void testNotFound() throws Exception {
        try (Response resp = get("/app/not-found")) {
            assertEquals(404, resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testPostEcho() throws Exception {
        try (Response resp = post("/app/echo", "text/plain", "hello world")) {
            assertEquals(200, resp.code());
            assertEquals("hello world", resp.body().string().trim());
        }
    }

    @Test
    public void testFormUrlEncoded() throws Exception {
        try (Response resp = post("/app/form", "application/x-www-form-urlencoded", "field1=testValue")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("field1=testValue"), body);
        }
    }

    @Test
    public void testHttpVersion() throws Exception {
        try (Response resp = get("/app/version")) {
            assertEquals(200, resp.code());
            assertEquals("HTTP/1.1", resp.body().string().trim());
        }
    }

    // ==================== New tests ====================

    @Test
    public void testMultiValueHeaders() throws Exception {
        try (Response resp = get("/app/multi-header")) {
            assertEquals(200, resp.code());
            assertEquals("ok", resp.body().string());
        }
    }

    @Test
    public void testEarlyHints() throws Exception {
        try (Response resp = get("/app/early-hints")) {
            assertTrue(resp.code() == 200 || resp.code() == 103,
                    "expected 200 or 103, got " + resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testChunkedWrite() throws Exception {
        try (Response resp = get("/app/chunked")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("chunked"), body);
        }
    }

    @Test
    public void testRemoveHeader() throws Exception {
        try (Response resp = get("/app/remove-header")) {
            assertEquals(200, resp.code());
            assertEquals("ok", resp.body().string());
        }
    }

    @Test
    public void testLargeWrite() throws Exception {
        try (Response resp = get("/app/large-write")) {
            assertEquals(200, resp.code());
            byte[] body = resp.body().bytes();
            assertEquals(65536, body.length, "large write should return 65536 bytes");
        }
    }

    @Test
    public void testSse() throws Exception {
        try (Response resp = get("/app/sse")) {
            resp.code();
            resp.body().bytes();
        }
    }

    @Test
    public void testSseEvent() throws Exception {
        try (Response resp = get("/app/sse-event")) {
            resp.code();
            resp.body().bytes();
        }
    }

    @Test
    public void testLastModified() throws Exception {
        try (Response resp = get("/app/last-modified")) {
            assertEquals(200, resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testKeepAlive() throws Exception {
        try (Response resp = get("/app/keep-alive", "Connection", "close")) {
            assertEquals(200, resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testLargeFormUrlEncoded() throws Exception {
        StringBuilder sb = new StringBuilder("big=");
        for (int i = 0; i < 5000; ++i) sb.append("data");
        String bodyData = sb.toString();

        try (Response resp = post("/app/large-form", "application/x-www-form-urlencoded", bodyData)) {
            assertEquals(200, resp.code());
            String r = resp.body().string();
            assertTrue(r.contains("len="), r);
        }
    }

    @Test
    public void testMultipartUpload() throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "value1\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"field2\"\r\n\r\n"
                + "value2\r\n"
                + "--" + BOUNDARY + "--\r\n";

        try (Response resp = postBytes("/app/upload", "multipart/form-data; boundary=" + BOUNDARY, body.getBytes())) {
            assertEquals(200, resp.code());
            String r = resp.body().string();
            assertTrue(r.contains("field1=value1"), r);
            assertTrue(r.contains("field2=value2"), r);
        }
    }

    @Test
    public void testSendFileGzip() throws Exception {
        try (Response resp = get("/app/send-file-gzip", "Accept-Encoding", "gzip")) {
            assertEquals(200, resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testSendFileGzipLarge() throws Exception {
        try (Response resp = get("/app/send-file-gzip-large", "Accept-Encoding", "gzip")) {
            assertEquals(200, resp.code());
            assertTrue(resp.body().bytes().length > 0, "streaming gzip large file body should not be empty");
        }
    }

    @Test
    public void testAutoGzip() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; ++i) sb.append("auto gzip plain body content line\n");
        String expected = sb.toString();
        try (Response resp = get("/app/auto-gzip", "Accept-Encoding", "gzip")) {
            assertEquals(200, resp.code());
            // networkResponse carries the raw Content-Encoding header (before OkHttp transparent decompression)
            assertEquals("gzip", resp.networkResponse().header("Content-Encoding"),
                    "auto-gzip should set Content-Encoding: gzip");
            // We passed Accept-Encoding manually, so OkHttp does NOT transparently decompress;
            // decompress the gzip payload ourselves to verify the original body survived.
            byte[] raw = resp.body().bytes();
            ByteArrayInputStream bais = new ByteArrayInputStream(raw);
            try (GZIPInputStream gzis = new GZIPInputStream(bais)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = gzis.read(buf)) != -1) out.write(buf, 0, n);
                String decoded = new String(out.toByteArray(), StandardCharsets.UTF_8);
                assertEquals(expected, decoded);
            }
        }
    }

    @Test
    public void testSendFileSmall() throws Exception {
        try (Response resp = get("/app/send-file-small")) {
            assertEquals(200, resp.code());
            assertTrue(resp.body().bytes().length > 0, "small file body should not be empty");
        }
    }

    @Test
    public void testMultiHeaderListPath() throws Exception {
        try (Response resp = get("/app/multi3")) {
            assertEquals(200, resp.code());
            assertEquals("ok", resp.body().string().trim());
        }
    }

    @Test
    public void testSendFileWithLastModified() throws Exception {
        try (Response resp = get("/app/last-mod-file")) {
            assertEquals(200, resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testGetHeader() throws Exception {
        try (Response resp = get("/app/get-header")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("got=echoVal"), body);
        }
    }

    @Test
    public void testRemoveHeaderValue() throws Exception {
        try (Response resp = get("/app/remove-val")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("val=keep"), body);
        }
    }

    @Test
    public void testLargeWriteFlush() throws Exception {
        try (Response resp = get("/app/large-write-flush")) {
            assertEquals(200, resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testSendNonExistentFile() throws Exception {
        try (Response resp = get("/app/send-nonexist")) {
            assertTrue(resp.code() >= 400, "expected error status for non-existent file, got " + resp.code());
            resp.body().bytes();
        }
    }

    @Test
    public void testContentLength() throws Exception {
        try (Response resp = get("/app/content-length")) {
            assertEquals(200, resp.code());
            String cl = resp.header("Content-Length");
            assertNotNull(cl, "Content-Length should be set");
            resp.body().bytes();
        }
    }

    @Test
    public void testLargeMultipartUpload() throws Exception {
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 100000; ++i) largeContent.append("LARGE_DATA_CHUNK_");
        String body = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + largeContent.toString() + "\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "hello\r\n"
                + "--" + BOUNDARY + "--\r\n";

        try (Response resp = postBytes("/app/upload", "multipart/form-data; boundary=" + BOUNDARY, body.getBytes())) {
            assertEquals(200, resp.code());
            String r = resp.body().string();
            assertTrue(r.contains("name=hello"), r);
        }
    }

    @AfterAll
    public static void stopServer() {
        if (server != null) server.shutdown();
        if (testFile != null) testFile.delete();
        if (htmlFile != null) htmlFile.delete();
        if (smallFile != null) smallFile.delete();
    }

    // ==================== OkHttp helpers ====================

    /** GET 请求，支持可选的请求头键值对 */
    private static Response get(String path, String... headers) throws IOException {
        Request.Builder rb = new Request.Builder().url("http://localhost:" + port + path);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            rb.addHeader(headers[i], headers[i + 1]);
        }
        return httpClient.newCall(rb.build()).execute();
    }

    /** POST 请求（文本 body） */
    private static Response post(String path, String contentType, String body) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url("http://localhost:" + port + path)
                .post(RequestBody.create(MediaType.parse(contentType), body));
        return httpClient.newCall(rb.build()).execute();
    }

    /** POST 请求（二进制 body，如 multipart） */
    private static Response postBytes(String path, String contentType, byte[] body) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url("http://localhost:" + port + path)
                .post(RequestBody.create(MediaType.parse(contentType), body));
        return httpClient.newCall(rb.build()).execute();
    }

    private static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
