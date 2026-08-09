package io.github.wycst.wastnet.examples.http;

import io.github.wycst.wast.log.Log;
import io.github.wycst.wast.log.LogFactory;
import io.github.wycst.wastnet.http.*;
import io.github.wycst.wastnet.http.handler.HttpRequestHandler;
import io.github.wycst.wastnet.socket.tcp.NioConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件上传功能测试类
 * 演示HTTP文件上传的处理方式
 *
 * @author wangyc
 */
public class UploadTest {

    private static final Log log = LogFactory.getLog(UploadTest.class);
    private static final String UPLOAD_DIR = "uploads";

    public static void main(String[] args) throws Exception {
        // 仅测试：不限制请求体大小（-1 = 无限制），避免大文件上传被 413 拒绝
        System.setProperty("wastnet.http.body-max-size", "-1");

        // 确保上传目录存在
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        NioConfig nioConfig = new NioConfig();
        nioConfig.testMode();
        nioConfig.setWorkerNum(4);

        int targetPort = 8083;
        HTTPServer httpServer = HTTPServer.of(8083, nioConfig)
                .bufferSize(8192)
                .requestHandler(new UploadRequestHandler())
                .start();

        log.info("Upload server started on port " + httpServer.getPort());
        log.info("Upload endpoint: http://localhost:" + httpServer.getPort() + "/upload");
        log.info("File list endpoint: http://localhost:" + httpServer.getPort() + "/files");
    }

    /**
     * 上传请求处理器
     */
    static class UploadRequestHandler implements HttpRequestHandler {
        public void handle(HttpRequest request, HttpResponse response) throws Throwable {
            String uri = request.getRequestUri();
            if ("/upload".equals(uri) && HttpMethod.POST == request.getMethod()) {
                handleFileUpload(request, response);
            } else if ("/files".equals(uri)) {
                handleFileList(request, response);
            } else if (uri.startsWith("/download/")) {
                handleFileDownload(request, response);
            } else {
                showUploadForm(request, response);
            }
        }

        /**
         * 处理文件上传
         */
        private void handleFileUpload(HttpRequest request, HttpResponse response) throws Throwable {
            try {
                MultipartField fileData = request.getMultipartField("file");

                if (fileData == null || fileData.getFileName() == null) {
                    sendErrorResponse(response, "No file uploaded");
                    return;
                }

                Path filePath = Paths.get(UPLOAD_DIR, fileData.getFileName());
                fileData.transferTo(filePath.toFile());

                String path = filePath.toAbsolutePath().toString().replace("\\", "/");
                String jsonResponse = String.format(
                        "{\"status\":\"success\",\"filename\":\"%s\",\"size\":%d,\"path\":\"%s\"}",
                        fileData.getFileName(), fileData.size(), path
                );

                response.status(HttpStatus.OK)
                        .contentType("application/json;charset=utf-8")
                        .body(jsonResponse.getBytes());

                log.info("File uploaded: " + fileData.getFileName() + " (" + fileData.size() + " bytes)");

            } catch (Exception e) {
                log.error("File upload failed", e);
                sendErrorResponse(response, "Upload failed: " + e.getMessage());
            }
        }

        /**
         * 从请求中获取文件数据
         */
        private byte[] getFileData(HttpRequest request) throws IOException {
            return request.getBodyData();
        }

        /**
         * 显示文件列表
         */
        private void handleFileList(HttpRequest request, HttpResponse response) throws Throwable {
            try {
                StringBuilder html = new StringBuilder();
                html.append("<html><head><title>Uploaded Files</title></head><body>");
                html.append("<h1>Uploaded Files</h1>");
                html.append("<a href='/'>Back to Upload</a><br/><br/>");

                File uploadDir = new File(UPLOAD_DIR);
                File[] files = uploadDir.listFiles();

                if (files != null && files.length > 0) {
                    html.append("<table border='1'><tr><th>Filename</th><th>Size</th><th>Actions</th></tr>");
                    for (File file : files) {
                        if (file.isFile()) {
                            html.append(String.format(
                                    "<tr><td>%s</td><td>%d bytes</td><td><a href='/download/%s'>Download</a></td></tr>",
                                    file.getName(), file.length(), file.getName()
                            ));
                        }
                    }
                    html.append("</table>");
                } else {
                    html.append("<p>No files uploaded yet.</p>");
                }

                html.append("</body></html>");

                response.status(HttpStatus.OK)
                        .contentType("text/html;charset=utf-8")
                        .body(html.toString().getBytes());

            } catch (Exception e) {
                log.error("File list failed", e);
                sendErrorResponse(response, "Failed to list files: " + e.getMessage());
            }
        }

        /**
         * 处理文件下载
         */
        private void handleFileDownload(HttpRequest request, HttpResponse response) throws Throwable {
            try {
                String fileName = request.getRequestUri().substring("/download/".length());
                Path filePath = Paths.get(UPLOAD_DIR, fileName);

                if (!Files.exists(filePath)) {
                    sendErrorResponse(response, "File not found: " + fileName);
                    return;
                }

                byte[] fileData = Files.readAllBytes(filePath);

                response.status(HttpStatus.OK)
                        .contentType("application/octet-stream")
                        .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                        .contentLength(fileData.length)
                        .body(fileData);

                log.info("File downloaded: " + fileName + " (" + fileData.length + " bytes)");

            } catch (Exception e) {
                log.error("File download failed", e);
                sendErrorResponse(response, "Download failed: " + e.getMessage());
            }
        }

        /**
         * 显示上传表单
         */
        private void showUploadForm(HttpRequest request, HttpResponse response) throws Throwable {
            String html = "<html>" +
                    "<head>" +
                    "<title>File Upload Test</title>" +
                    "<style>" +
                    "body { font-family: Arial, sans-serif; margin: 20px; }" +
                    ".container { max-width: 600px; margin: 0 auto; }" +
                    ".form-group { margin-bottom: 15px; }" +
                    "label { display: block; margin-bottom: 5px; }" +
                    "input[type=\"file\"] { width: 100%; padding: 8px; }" +
                    "input[type=\"text\"] { width: 100%; padding: 8px; }" +
                    "button { background-color: #007bff; color: white; padding: 10px 20px; border: none; cursor: pointer; }" +
                    "button:hover { background-color: #0056b3; }" +
                    ".info { background-color: #f8f9fa; padding: 15px; border-radius: 5px; margin-top: 20px; }" +
                    "</style>" +
                    "</head>" +
                    "<body>" +
                    "<div class=\"container\">" +
                    "<h1>File Upload Test</h1>" +
                    "<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">" +
                    "<div class=\"form-group\">" +
                    "<label for=\"file\">Select file to upload:</label>" +
                    "<input type=\"file\" id=\"file\" name=\"file\" required>" +
                    "</div>" +
                    "<button type=\"submit\">Upload File</button>" +
                    "</form>" +
                    "<div class=\"info\">" +
                    "<h3>Test Information:</h3>" +
                    "<ul>" +
                    "<li>Endpoint: <code>POST /upload</code></li>" +
                    "<li>File list: <a href=\"/files\">View uploaded files</a></li>" +
                    "<li>Supports any file type</li>" +
                    "<li>Files are stored in <code>uploads/</code> directory</li>" +
                    "</ul>" +
                    "</div>" +
                    "</div>" +
                    "</body>" +
                    "</html>";

            response.status(HttpStatus.OK)
                    .contentType("text/html;charset=utf-8")
                    .body(html.getBytes());
        }

        /**
         * 发送错误响应
         */
        private void sendErrorResponse(HttpResponse response, String message) throws Throwable {
            String jsonResponse = String.format("{\"status\":\"error\",\"message\":\"%s\"}", message);
            response.status(HttpStatus.BAD_REQUEST)
                    .contentType("application/json;charset=utf-8")
                    .body(jsonResponse.getBytes());
        }
    }

    /**
     * 测试大文件上传
     */
    public static void testLargeFileUpload() throws Exception {
        // 仅测试：不限制请求体大小（-1 = 无限制），便于大文件上传测试
        System.setProperty("wastnet.http.body-max-size", "-1");

        NioConfig config = new NioConfig();
        config.testMode();

        HTTPServer.of(8084, config)
                .bufferSize(16384)
                .requestHandler(new HttpRequestHandler() {
                    public void handle(HttpRequest request, HttpResponse response) throws Throwable {
                        try {
                            byte[] data = getFileDataStatic(request);
                            String info = String.format("Received %d bytes", data != null ? data.length : 0);

                            response.status(HttpStatus.OK)
                                    .contentType("text/plain;charset=utf-8")
                                    .body(info.getBytes());

                            log.info(info);
                        } catch (Exception e) {
                            log.error("Large file upload test failed", e);
                            response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .body("Upload failed".getBytes());
                        }
                    }

                    private byte[] getFileDataStatic(HttpRequest request) throws IOException {
                        if (request instanceof HttpRequest) {
                            try {
                                return request.getBodyData();
                            } catch (Exception e) {
                                log.warn("Cannot access bodyData field", e);
                            }
                        }

                        InputStream inputStream = request.bodyStream();
                        if (inputStream != null) {
                            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            byte[] data = new byte[8192];
                            int nRead;
                            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                                buffer.write(data, 0, nRead);
                            }
                            return buffer.toByteArray();
                        }

                        return null;
                    }
                })
                .start();

        log.info("Large file upload test server started on port 8084");
    }
}
