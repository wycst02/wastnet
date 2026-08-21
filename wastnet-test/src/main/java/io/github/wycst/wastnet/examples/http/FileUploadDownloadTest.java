package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.http.h2.H2Monitor;
import io.github.wycst.wastnet.log.LogLevel;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP/2 file upload & download demo.
 *
 * <p>Demonstrates streaming large-file upload via multipart/form-data, and serving
 * the uploaded file back for download. The upload response returns an HTML page with
 * a clickable download link; before the file is uploaded the download endpoint returns 404.</p>
 *
 * <p>Usage (HTTPS server on port 8080, replace HOST with the actual host):</p>
 * <pre>
 *   # Upload a file
 *   curl -k -X POST -F "file=@/path/to/largefile.zip" HOST:8080/upload
 *   # Download it back (404 if not yet uploaded)
 *   curl -k -O -J "HOST:8080/download?name=largefile.zip"
 * </pre>
 */
public class FileUploadDownloadTest {

    private static final Log log = LogFactory.getLog(FileUploadDownloadTest.class);

    /** Directory where uploaded files are stored. */
    private static final String UPLOAD_DIR = "e:/tmp/wastnet-upload-tmp";

    public static void main(String[] args) throws Exception {
        // Test only: disable body size limit (-1 = unlimited) so large uploads are never rejected.
        System.setProperty("wastnet.http.body-max-size", "-1");
        // Enable H2 connection/stream/window monitoring so the /monitor route returns live data.
        System.setProperty("wastnet.h2.monitor", "true");
        io.github.wycst.wastnet.log.LogFactory.setLevel(LogLevel.DEBUG);

        File uploadDir = new File(UPLOAD_DIR);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();

        HTTPServer httpServer = HTTPServer.of(8080, nioConfig)
                .sslContext(createSslContext())
                .h2()
                .printApplicationMessage(false)
                .printReadErrorLog(true)
                .printStackTraceError(true)
                .requestHandler(new HttpRequestHandler() {
                    @Override
                    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                        String uri = request.getRequestUri();
                        System.out.println("uri " + uri);

                        if ("/monitor".equals(uri)) {
                            handleMonitor(response);
                            return;
                        }

                        if ("/upload".equals(uri) && request.getMethod() == HttpMethod.POST) {
                            handleUpload(request, response);
                            return;
                        }

                        if ("/download".equals(uri)) {
                            handleDownload(request, response);
                            return;
                        }

                        showUploadForm(response);
                    }
                }).start();

        log.info("File server started at https://localhost:{}", httpServer.getPort());
        log.info("Upload directory: {}", UPLOAD_DIR);
    }

    /** Handle file upload (multipart/form-data), then respond with a page of download links. */
    private static void handleUpload(HttpRequest request, HttpResponse response) throws Exception {
        long startTime = System.currentTimeMillis();
        if (!request.isMultipart()) {
            response.status(400).body("Content-Type must be multipart/form-data");
            return;
        }

        Set<String> fieldNames = request.getMultipartFieldNames();
        StringBuilder result = new StringBuilder();
        result.append("Upload result:\n");

        // Record the names of uploaded files so we can render download links.
        List<String> uploadedNames = new ArrayList<String>();

        for (String fieldName : fieldNames) {
            MultipartField field = request.getMultipartField(fieldName);

            if (field.isFile()) {
                long fieldStart = System.currentTimeMillis();
                String originalName = field.getFileName();
                File targetFile = new File(UPLOAD_DIR, originalName);

                // Guard against path traversal: reject files resolving outside UPLOAD_DIR.
                if (!isUnderUploadDir(targetFile)) {
                    result.append("  File: ").append(originalName)
                          .append(" -> rejected (path traversal)\n");
                    log.warn("Rejected upload escaping upload dir: {}", targetFile.getAbsolutePath());
                    continue;
                }

                // Stream the upload straight to disk (no in-memory buffering).
                field.transferTo(targetFile);
                long fieldEnd = System.currentTimeMillis();

                result.append("  File: ").append(originalName)
                      .append(" -> ").append(targetFile.getAbsolutePath())
                      .append(" (").append(targetFile.length()).append(" bytes, ")
                      .append(fieldEnd - fieldStart).append("ms)\n");

                uploadedNames.add(originalName);
                log.info("File saved: {} -> {}, size: {}, time: {}ms",
                        originalName, targetFile.getAbsolutePath(), targetFile.length(), fieldEnd - fieldStart);
            } else {
                String value = request.getMultipartFieldValue(fieldName);
                result.append("  Field: ").append(fieldName).append(" = ").append(value).append("\n");
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;
        result.append("Total time: ").append(totalTime).append("ms\n");
        log.info("Upload completed, total time: {}ms", totalTime);

        // Build an HTML response containing a clickable download link per uploaded file.
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"><title>Upload Done</title></head>\n<body>\n");
        html.append("<h1>Upload Done</h1>\n<pre>").append(result.toString()).append("</pre>\n");
        if (!uploadedNames.isEmpty()) {
            html.append("<h2>Download links</h2>\n<ul>\n");
            for (String name : uploadedNames) {
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
                html.append("  <li><a href=\"/download?name=").append(encoded).append("\">")
                    .append(name).append("</a></li>\n");
            }
            html.append("</ul>\n");
        }
        html.append("</body>\n</html>");

        response.contentType("text/html;charset=utf-8")
                .body(html.toString());
    }

    /** True if the file resolves under UPLOAD_DIR (rejects ../ traversal and symlink escapes). */
    private static boolean isUnderUploadDir(File file) throws IOException {
        File canonicalDir = new File(UPLOAD_DIR).getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        return canonicalFile.getPath().startsWith(canonicalDir.getPath() + File.separator);
    }

    /** Serve an uploaded file for download; returns 404 if it has not been uploaded yet. */
    private static void handleDownload(HttpRequest request, HttpResponse response) throws Exception {
        String name = request.getParameter("name");
        if (name == null || name.isEmpty()) {
            response.status(404).body("Missing 'name' parameter");
            return;
        }

        // Strip any path components to prevent directory traversal.
        String safeName = new File(name).getName();
        File file = new File(UPLOAD_DIR, safeName);

        if (!file.exists() || !file.isFile()) {
            response.status(404).body("File not found: " + safeName);
            return;
        }

        // Tell the client to save under the original filename (RFC 5987 for non-ASCII names).
        String encodedName = URLEncoder.encode(safeName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.header("Content-Disposition",
                "attachment; filename=\"" + safeName + "\"; filename*=UTF-8''" + encodedName);

        response.sendFile(file);
    }

    /** Return a live H2 connection/stream/flow-control snapshot as JSON (via H2Monitor). */
    private static void handleMonitor(HttpResponse response) throws Exception {
        if (!H2Monitor.global().containsKey("enabled") || !(Boolean) H2Monitor.global().get("enabled")) {
            response.status(503).body("H2 monitor disabled (start with -Dwastnet.h2.monitor=true)");
            return;
        }
        response.contentType("application/json;charset=utf-8")
                .body(toJson(H2Monitor.global(), 0));
    }

    /** Minimal hand-rolled JSON serializer for H2Monitor's Map/List structure (no extra deps). */
    @SuppressWarnings("unchecked")
    private static String toJson(Object obj, int indent) {
        StringBuilder sb = new StringBuilder();
        String pad = repeat(' ', indent);
        String pad2 = repeat(' ', indent + 2);
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(pad2).append('"').append(e.getKey()).append("\": ").append(toJson(e.getValue(), indent + 2));
            }
            sb.append('\n').append(pad).append("}");
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(pad2).append(toJson(item, indent + 2));
            }
            sb.append('\n').append(pad).append("]");
        } else if (obj instanceof String) {
            sb.append('"').append(((String) obj).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj.toString());
        } else {
            sb.append("null");
        }
        return sb.toString();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** Render a simple HTML upload form for browser testing. */
    private static void showUploadForm(HttpResponse response) {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head><title>File Upload</title></head>\n" +
                "<body>\n" +
                "<h1>File Upload Test</h1>\n" +
                "<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">\n" +
                "  <p>File 1: <input type=\"file\" name=\"file1\" /></p>\n" +
                "  <p>File 2: <input type=\"file\" name=\"file2\" /></p>\n" +
                "  <p>File 3: <input type=\"file\" name=\"file3\" /></p>\n" +
                "  <p>File 4: <input type=\"file\" name=\"file4\" /></p>\n" +
                "  <p>Description: <input type=\"text\" name=\"description\" /></p>\n" +
                "  <p><input type=\"submit\" value=\"Upload\" /></p>\n" +
                "</form>\n" +
                "</body>\n" +
                "</html>";

        response.contentType("text/html;charset=utf-8")
                .body(html);
    }

    private static SSLContext createSslContext() throws Exception {
        char[] password = "123456".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("JKS");

        // Generate with: keytool -genkey -alias aliastest -keyalg RSA -keysize 1024 \
        //   -keypass 123456 -validity 365 -keystore server.keystore -storepass 123456
        InputStream in = FileUploadDownloadTest.class.getResourceAsStream("/server.keystore");
        keyStore.load(in, password);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext;
    }
}
