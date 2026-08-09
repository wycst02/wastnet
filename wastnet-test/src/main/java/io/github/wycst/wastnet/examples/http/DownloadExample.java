package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.HttpStatus;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 文件下载示例 — 演示 sendFile() 的多种用法
 *
 * <p>启动后访问以下 URL：
 * <ul>
 *   <li>http://localhost:8084/files/example.txt — 直接下载，sendFile 自动推断 Content-Type</li>
 *   <li>http://localhost:8084/download/doc.pdf — 自定义响应头后下载</li>
 *   <li>http://localhost:8084/inline/image.png — 浏览器内联显示，非附件下载</li>
 * </ul>
 *
 * @author wangyc
 */
public class DownloadExample {

    private static final Log log = LogFactory.getLog(DownloadExample.class);

    public static void main(String[] args) throws Exception {
        prepareTestFiles();
        startServer();
    }

    private static void prepareTestFiles() throws IOException {
        File testDir = getTestDir();
        testDir.mkdirs();

        writeFile(new File(testDir, "example.txt"),
                "This is a sample text file for download testing.\n" +
                "You can download this file via sendFile().\n");

        writeFile(new File(testDir, "doc.pdf"),
                "Fake PDF content for download demo.\n");

        writeFile(new File(testDir, "image.png"),
                "Fake PNG content for inline display demo.\n");

        log.info("Test files prepared in: {}", testDir.getAbsolutePath());
    }

    private static void writeFile(File file, String content) throws IOException {
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private static File getTestDir() {
        return new File("test-files/download-demo").getAbsoluteFile();
    }

    private static void startServer() throws Exception {
        NioConfig config = new NioConfig();
        config.testMode();

        HTTPServer.of(8084, config)
                .requestHandler(new DownloadHandler())
                .start();

        log.info("Download server started on http://localhost:8084");
        log.info("  Direct sendFile:    http://localhost:8084/files/example.txt");
        log.info("  Custom header:      http://localhost:8084/download/doc.pdf");
        log.info("  Inline display:     http://localhost:8084/inline/image.png");
    }

    /**
     * 下载请求处理器
     */
    static class DownloadHandler implements HttpRequestHandler {

        private final File baseDir = getTestDir();

        @Override
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();
            log.info("Received request: {}", uri);
            if (uri == null) {
                sendError(response, "Invalid request");
                return;
            }
            if (uri.startsWith("/files/")) {
                // sendFile 自动处理 status/Content-Type/Content-Length/commit，文件不存在返回 404
                handleDirectDownload(request, response);
            } else if (uri.startsWith("/download/")) {
                handleAttachmentDownload(request, response);
            } else if (uri.startsWith("/inline/")) {
                handleInlineDisplay(request, response);
            } else {
                sendHelpPage(response);
            }
        }

        private void handleDirectDownload(HttpRequest request, HttpResponse response) throws Throwable {
            String filename = request.getRequestUri().substring("/files/".length());
            File file = new File(baseDir, filename);
            response.sendFile(file);
        }

        private void handleAttachmentDownload(HttpRequest request, HttpResponse response) throws Throwable {
            String filename = request.getRequestUri().substring("/download/".length());
            File file = new File(baseDir, filename);
            if (!file.exists()) {
                sendError(response, "File not found: " + filename);
                return;
            }
            // sendFile 会覆盖 status(200) 和 Content-Type，但保留已设置的 Content-Disposition
            response.header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .sendFile(file);
        }

        private void handleInlineDisplay(HttpRequest request, HttpResponse response) throws Throwable {
            String filename = request.getRequestUri().substring("/inline/".length());
            File file = new File(baseDir, filename);
            if (!file.exists()) {
                sendError(response, "File not found: " + filename);
                return;
            }
            response.header("Content-Disposition", "inline")
                    .header("Cache-Control", "public, max-age=3600")
                    .sendFile(file);
        }

        private void sendError(HttpResponse response, String message) throws Throwable {
            response.status(HttpStatus.BAD_REQUEST)
                    .contentType("text/plain;charset=utf-8")
                    .body(message.getBytes());
        }

        private void sendHelpPage(HttpResponse response) throws Throwable {
            String html = "<html><body>" +
                    "<h2>File Download Demo</h2>" +
                    "<ul>" +
                    "<li><a href='/files/example.txt'>/files/example.txt</a> — Direct sendFile</li>" +
                    "<li><a href='/download/doc.pdf'>/download/doc.pdf</a> — Attachment download</li>" +
                    "<li><a href='/inline/image.png'>/inline/image.png</a> — Inline display</li>" +
                    "</ul>" +
                    "<p>sendFile \u5185\u90e8\u81ea\u52a8\u5904\u7406 status(200)\u3001Content-Type\uff08\u4ece\u6587\u4ef6\u540d\u63a8\u65ad\uff09\u3001Content-Length\u3001commit\u3002</p>" +
                    "</body></html>";
            response.status(HttpStatus.OK)
                    .contentType("text/html;charset=utf-8")
                    .body(html.getBytes());
        }
    }
}
